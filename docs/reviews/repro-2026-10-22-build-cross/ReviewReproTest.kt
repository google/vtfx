/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(InternalVtfxApi::class)

import android.os.Looper
import com.google.vtfx.compose.TerminalDisplay
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.session.SessionObserver
import com.google.vtfx.session.TerminalSession
import com.google.vtfx.session.TerminalSessionListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.metadata.KmFunction
import kotlin.metadata.KmPackage
import kotlin.metadata.KmType
import kotlin.metadata.Modality
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.JvmMetadataVersion
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.signature
import kotlin.metadata.modality
import kotlin.metadata.visibility
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(Enclosed::class)
class ReviewReproTest {
    class JvmReproTest {
        private val repoRoot: File
            get() = File(checkNotNull(System.getProperty("vtfx.repoRoot")))

        /**
         * R1 (case A): Calling an inherited external method (both a restricted member and an
         * unallowed member) through a project-internal interface that declares the same signature
         * bypasses both `classViolations` and `allowlistViolations` (docs/design/boundaries.md
         * sections 4.2, 4.3, 5.1; docs/decisions/0082).
         */
        @Test
        fun r1a_interfaceTunnelBypassesRestrictedMemberAndAllowlist() {
            val boundaries = Boundaries.parse(File(repoRoot, "config/boundaries.toml").readText())
            val viewClass =
                classBytes(
                    name = "android/view/View",
                    methods =
                        listOf(
                            MethodSpec("performHapticFeedback", "(I)Z"),
                            MethodSpec("setKeepScreenOn", "(Z)V"),
                        ),
                )
            val tunnelInterface =
                classBytes(
                    name = "com/google/vtfx/compose/ViewTunnel",
                    access =
                        Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
                    methods =
                        listOf(
                            MethodSpec(
                                "performHapticFeedback",
                                "(I)Z",
                                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                            ),
                            MethodSpec(
                                "setKeepScreenOn",
                                "(Z)V",
                                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                            ),
                        ),
                )
            // SubView extends android/view/View and implements ViewTunnel without overriding the
            // methods: the JVM satisfies ViewTunnel's methods with the inherited View methods.
            val subView =
                classBytes(
                    name = "com/google/vtfx/compose/SubView",
                    superName = "android/view/View",
                    interfaces = arrayOf("com/google/vtfx/compose/ViewTunnel"),
                )
            val caller =
                classBytes(
                    name = "com/google/vtfx/compose/Caller",
                    calls =
                        listOf(
                            CallSpec(
                                Opcodes.INVOKEINTERFACE,
                                "com/google/vtfx/compose/ViewTunnel",
                                "performHapticFeedback",
                                "(I)Z",
                                isInterface = true,
                            ),
                            CallSpec(
                                Opcodes.INVOKEINTERFACE,
                                "com/google/vtfx/compose/ViewTunnel",
                                "setKeepScreenOn",
                                "(Z)V",
                                isInterface = true,
                            ),
                        ),
                )

            val moduleClasses = listOf(tunnelInterface, subView, caller)
            val byName =
                (moduleClasses + viewClass).associateBy { ClassReferences.read(it).name }
            val hierarchy = ClassHierarchy { name ->
                byName[name]
                    ?: ClassLoader.getSystemResourceAsStream("$name.class")?.use { it.readBytes() }
            }
            val refs = moduleClasses.map { ClassReferences.read(it) }
            val violations =
                boundaries.classViolations("vtfx-compose", refs, hierarchy) +
                    boundaries.allowlistViolations("vtfx-compose", refs, emptySet(), hierarchy)

            assertTrue(
                "Expected restricted_members and allowlist violations for inherited View methods " +
                    "called through ViewTunnel, but got: $violations",
                violations.any { it.contains("performHapticFeedback") } &&
                    violations.any { it.contains("setKeepScreenOn") },
            )
        }

        /**
         * R1 (case B): `ClassHierarchy.declaringClass` searches supertypes with a single BFS queue,
         * so an interface implemented directly by a subclass (depth 1) is visited before a
         * superclass at depth 2, violating "superclasses before interfaces"
         * (docs/design/boundaries.md section 4.2, JVMS 5.4.3.3) and masking external
         * restricted/unallowed inherited methods on direct calls.
         */
        @Test
        fun r1b_declaringClassVisitsShallowInterfaceBeforeDeeperSuperclass() {
            val boundaries = Boundaries.parse(File(repoRoot, "config/boundaries.toml").readText())
            val viewClass =
                classBytes(
                    name = "android/view/View",
                    methods =
                        listOf(
                            MethodSpec("performHapticFeedback", "(I)Z"),
                            MethodSpec("setKeepScreenOn", "(Z)V"),
                        ),
                )
            val subView1 =
                classBytes(
                    name = "com/google/vtfx/compose/SubView1",
                    superName = "android/view/View",
                )
            val maskInterface =
                classBytes(
                    name = "com/google/vtfx/compose/Mask",
                    access =
                        Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
                    methods =
                        listOf(
                            MethodSpec(
                                "performHapticFeedback",
                                "(I)Z",
                                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                            ),
                            MethodSpec(
                                "setKeepScreenOn",
                                "(Z)V",
                                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                            ),
                        ),
                )
            val subView2 =
                classBytes(
                    name = "com/google/vtfx/compose/SubView2",
                    superName = "com/google/vtfx/compose/SubView1",
                    interfaces = arrayOf("com/google/vtfx/compose/Mask"),
                )
            val caller =
                classBytes(
                    name = "com/google/vtfx/compose/Caller",
                    calls =
                        listOf(
                            CallSpec(
                                Opcodes.INVOKEVIRTUAL,
                                "com/google/vtfx/compose/SubView2",
                                "performHapticFeedback",
                                "(I)Z",
                                isInterface = false,
                            ),
                            CallSpec(
                                Opcodes.INVOKEVIRTUAL,
                                "com/google/vtfx/compose/SubView2",
                                "setKeepScreenOn",
                                "(Z)V",
                                isInterface = false,
                            ),
                        ),
                )

            val moduleClasses = listOf(subView1, maskInterface, subView2, caller)
            val byName =
                (moduleClasses + viewClass).associateBy { ClassReferences.read(it).name }
            val hierarchy = ClassHierarchy { name ->
                byName[name]
                    ?: ClassLoader.getSystemResourceAsStream("$name.class")?.use { it.readBytes() }
            }
            val declaring =
                hierarchy.declaringClass(
                    "com/google/vtfx/compose/SubView2",
                    "performHapticFeedback",
                    "(I)Z",
                )
            val refs = moduleClasses.map { ClassReferences.read(it) }
            val violations =
                boundaries.classViolations("vtfx-compose", refs, hierarchy) +
                    boundaries.allowlistViolations("vtfx-compose", refs, emptySet(), hierarchy)

            assertEquals("android/view/View", declaring)
            assertTrue(
                "Expected violations for SubView2 inherited View methods, got: $violations",
                violations.any { it.contains("performHapticFeedback") } &&
                    violations.any { it.contains("setKeepScreenOn") },
            )
        }

        /**
         * R2 (case A): `CheckClassReferencesTask` skips `META-INF/` `.class` entries in
         * `variantJars`, while `CheckLibraryArtifactTask` includes `META-INF/` `.class` entries
         * from `variantJars` in `checkedClasses` (BoundaryTasks.kt:192 vs 250). A class under
         * `META-INF/versions/` in a variant jar is therefore neither checked for boundary
         * violations nor rejected in the shipped AAR (docs/design/boundaries.md section 6,
         * docs/decisions/0088).
         */
        @Test
        fun r2a_checkLibraryArtifactAllowsUncheckedMetaInfClassFromVariantJar() {
            val tempDir = Files.createTempDirectory("repro-r2a").toFile()
            try {
                val uncheckedClass =
                    classBytes(
                        name = "com/google/vtfx/compose/Unchecked",
                        calls =
                            listOf(
                                CallSpec(
                                    Opcodes.INVOKESTATIC,
                                    "java/lang/System",
                                    "exit",
                                    "(I)V",
                                    isInterface = false,
                                )
                            ),
                    )
                val entryPath = "META-INF/versions/21/com/google/vtfx/compose/Unchecked.class"
                val variantJar = File(tempDir, "variant.jar")
                variantJar.writeBytes(zipOf(entryPath to uncheckedClass))

                val aarFile = File(tempDir, "library.aar")
                val manifest =
                    """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.google.vtfx.compose"/>"""
                aarFile.writeBytes(
                    zipOf(
                        "AndroidManifest.xml" to manifest.toByteArray(),
                        "classes.jar" to zipOf(entryPath to uncheckedClass),
                    )
                )

                var rejected = false
                try {
                    runCheckClassReferencesTask(
                        moduleName = "vtfx-compose",
                        boundariesFile = File(repoRoot, "config/boundaries.toml"),
                        classDirs = emptySet(),
                        variantJarsList = listOf(variantJar),
                    )
                    runCheckLibraryArtifactTask(
                        artifactFile = aarFile,
                        classDirs = emptySet(),
                        variantJarsList = listOf(variantJar),
                    )
                } catch (_: GradleException) {
                    rejected = true
                }
                assertTrue(
                    "Neither CheckClassReferencesTask nor CheckLibraryArtifactTask rejected " +
                        "$entryPath calling System.exit(0)",
                    rejected,
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }

        /**
         * R2 (case B): When two class directories contain the same relative `.class` path,
         * `ClassPath.find` in `CheckClassReferencesTask` resolves the first directory's class for
         * `ClassHierarchy`, whereas `CheckLibraryArtifactTask` builds `checkedClasses` with
         * `.toMap()`, keeping the last directory's class (BoundaryTasks.kt:239-258).
         */
        @Test
        fun r2b_duplicateClassPathAcrossInputsUsesDifferentClassInHierarchyAndArtifact() {
            val tempDir = Files.createTempDirectory("repro-r2b").toFile()
            try {
                val dir1 = File(tempDir, "dir1")
                val dir2 = File(tempDir, "dir2")
                val stdlibDir = File(tempDir, "stdlib")
                for (sysClass in
                    listOf(
                        "java/lang/Object",
                        "java/lang/Throwable",
                        "java/lang/Exception",
                        "java/lang/RuntimeException",
                    )) {
                    val bytes =
                        ClassLoader.getSystemResourceAsStream("$sysClass.class")!!.use {
                            it.readBytes()
                        }
                    File(stdlibDir, "$sysClass.class").apply {
                        parentFile!!.mkdirs()
                        writeBytes(bytes)
                    }
                }
                // In dir1, Sub overrides printStackTrace() itself, so ClassHierarchy sees Sub as
                // the declaring class.
                val subWithOverride =
                    classBytes(
                        name = "com/google/vtfx/core/Sub",
                        superName = "java/lang/RuntimeException",
                        methods = listOf(MethodSpec("printStackTrace", "()V")),
                    )
                // In dir2 (and in the shipped library.jar), Sub does NOT override
                // printStackTrace(), so calling Sub.printStackTrace() invokes
                // java/lang/Throwable.printStackTrace()V, which is not on vtfx-core's allowlist.
                val subWithoutOverride =
                    classBytes(
                        name = "com/google/vtfx/core/Sub",
                        superName = "java/lang/RuntimeException",
                    )
                val caller =
                    classBytes(
                        name = "com/google/vtfx/core/Caller",
                        calls =
                            listOf(
                                CallSpec(
                                    Opcodes.INVOKEVIRTUAL,
                                    "com/google/vtfx/core/Sub",
                                    "printStackTrace",
                                    "()V",
                                    isInterface = false,
                                )
                            ),
                    )

                File(dir1, "com/google/vtfx/core/Sub.class").apply {
                    parentFile!!.mkdirs()
                    writeBytes(subWithOverride)
                }
                File(dir2, "com/google/vtfx/core/Sub.class").apply {
                    parentFile!!.mkdirs()
                    writeBytes(subWithoutOverride)
                }
                File(dir2, "com/google/vtfx/core/Caller.class").writeBytes(caller)

                val jarFile = File(tempDir, "vtfx-core.jar")
                jarFile.writeBytes(
                    zipOf(
                        "com/google/vtfx/core/Sub.class" to subWithoutOverride,
                        "com/google/vtfx/core/Caller.class" to caller,
                    )
                )

                var rejected = false
                try {
                    runCheckClassReferencesTask(
                        moduleName = "vtfx-core",
                        boundariesFile = File(repoRoot, "config/boundaries.toml"),
                        classDirs = linkedSetOf(dir1, dir2),
                        variantJarsList = emptyList(),
                        classpathFiles = setOf(stdlibDir),
                    )
                    runCheckLibraryArtifactTask(
                        artifactFile = jarFile,
                        classDirs = linkedSetOf(dir1, dir2),
                        variantJarsList = emptyList(),
                    )
                } catch (_: GradleException) {
                    rejected = true
                }
                assertTrue(
                    "Neither CheckClassReferencesTask nor CheckLibraryArtifactTask rejected " +
                        "the shadowed Sub.class that inherits unallowed Throwable.printStackTrace()",
                    rejected,
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }

        /**
         * R3: `scripts/check.sh` runs `scripts/check_kdoc.py`, `scripts/check_doc_links.py`,
         * `scripts/generate_unicode_tables.py`, and `python3 -I -m unittest discover -s
         * scripts/tests`, but `config/protected-paths.toml` does not list them under
         * `security_review` (docs/design/architecture.md section 5, docs/design/testing.md section
         * 11).
         */
        @Test
        fun r3_protectedPathsCoverAllScriptsExecutedByCheckSh() {
            val pythonCheck =
                """
                import fnmatch, sys, tomllib
                from pathlib import Path
                config = tomllib.loads(Path('config/protected-paths.toml').read_text())
                patterns = config['security_review']['paths']
                paths = [
                    'scripts/check_kdoc.py',
                    'scripts/check_doc_links.py',
                    'scripts/generate_unicode_tables.py',
                    'scripts/tests/test_check_kdoc.py',
                ]
                unprotected = [
                    p for p in paths
                    if not any(fnmatch.fnmatchcase(p, pat) for pat in patterns)
                ]
                if unprotected:
                    print('UNPROTECTED:' + ','.join(unprotected))
                    sys.exit(1)
                """.trimIndent()
            val process =
                ProcessBuilder("python3", "-I", "-c", pythonCheck)
                    .directory(repoRoot)
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            assertEquals(
                "Scripts executed by scripts/check.sh are not protected: $output",
                0,
                exitCode,
            )
        }

        /**
         * R4 (case A): `TerminalSession.input` calls `stickyModifiersNotifier.tell()`
         * unconditionally (TerminalSession.kt:428), so a concurrent `feed` that moves the cursor to
         * a new line calls `input {}` and delivers `onStickyModifiersChanged` on the `feed` thread
         * instead of the thread that changed the sticky modifiers
         * (docs/design/session-and-threads.md section 2.5).
         */
        @Test
        fun r4a_onStickyModifiersChangedArrivesOnTheThreadThatChangedThem() {
            val inRestartInput = CountDownLatch(1)
            val feedDone = CountDownLatch(1)
            val notifyingThread = AtomicReference<Thread>()
            lateinit var session: TerminalSession

            val listener =
                object : TerminalSessionListener {
                    override fun onStickyModifiersChanged(modifiers: KeyModifiers) {
                        notifyingThread.set(Thread.currentThread())
                    }
                }
            session = TerminalSession({}, listener = listener)
            // Put text in the edit buffer first, then arm sticky Ctrl so consuming Ctrl with a key
            // press triggers onRestartInput.
            session.edit { it.commitText("a") }
            session.stickyModifiers = KeyModifiers.of(ctrl = true)
            assertEquals(Thread.currentThread(), notifyingThread.get())
            notifyingThread.set(null)

            var intercept = true
            session.addObserver(
                object : SessionObserver {
                    override fun onOutput(
                        scrolledLines: Long,
                        scrollbackShrank: Boolean,
                        synchronizing: Boolean,
                    ) {}

                    override fun onScreenChanged() {}

                    override fun onUserInput() {}

                    override fun onRestartInput() {
                        if (!intercept) return
                        intercept = false
                        inRestartInput.countDown()
                        feedDone.await(5, TimeUnit.SECONDS)
                    }
                }
            )

            val feeder =
                Thread(
                    {
                        inRestartInput.await(5, TimeUnit.SECONDS)
                        // Moves the cursor to the next line, so feed() calls `if (moved) input {}`.
                        session.feed("\r\n".toByteArray(Charsets.UTF_8))
                        feedDone.countDown()
                    },
                    "background-feeder",
                )
            feeder.start()

            val uiThread = Thread.currentThread()
            session.edit { it.onKeyDown(KeyStroke.Character('c'.code)) }
            feeder.join(5_000)

            assertEquals(uiThread, notifyingThread.get())
        }

        /**
         * R4 (case B): `TerminalSession.feed` calls `titleNotifier.tell()` unconditionally
         * (TerminalSession.kt:509), so a concurrent `feed` that sets no title steals the pending
         * title notification from the `feed` thread that set the title
         * (docs/design/session-and-threads.md section 2.5).
         */
        @Test
        fun r4b_onTitleChangedArrivesOnTheFeedThreadThatSetTheTitle() {
            val inBell = CountDownLatch(1)
            val secondFeedDone = CountDownLatch(1)
            val titleThread = AtomicReference<Thread>()
            lateinit var session: TerminalSession

            val listener =
                object : TerminalSessionListener {
                    override fun onBell() {
                        inBell.countDown()
                        secondFeedDone.await(5, TimeUnit.SECONDS)
                    }

                    override fun onTitleChanged(title: String) {
                        titleThread.set(Thread.currentThread())
                    }
                }
            session = TerminalSession({}, listener = listener)

            val plainFeeder =
                Thread(
                    {
                        inBell.await(5, TimeUnit.SECONDS)
                        session.feed("plain".toByteArray(Charsets.UTF_8))
                        secondFeedDone.countDown()
                    },
                    "plain-feeder",
                )
            plainFeeder.start()

            val titleSetterThread = Thread.currentThread()
            session.feed("\u001b]2;Title\u0007\u0007".toByteArray(Charsets.UTF_8))
            plainFeeder.join(5_000)

            assertEquals(titleSetterThread, titleThread.get())
        }

        /**
         * R6: `KotlinSignatures` does not exclude file facades carrying an excluded annotation
         * (`@file:InternalVtfxApi`), nor typealiases carrying `@InternalVtfxApi`
         * (KotlinSignatures.kt:326-340, 427-436; docs/design/testing.md section 9).
         */
        @Test
        fun r6_kotlinSignaturesExcludesInternalVtfxApiFileFacade() {
            val kmPackage = KmPackage()
            val fn =
                KmFunction("internalTopLevel").apply {
                    visibility = Visibility.PUBLIC
                    modality = Modality.FINAL
                    returnType =
                        KmType().apply {
                            classifier = kotlin.metadata.KmClassifier.Class("kotlin/Unit")
                        }
                    signature = JvmMethodSignature("internalTopLevel", "()V")
                }
            kmPackage.functions += fn
            val metadata =
                KotlinClassMetadata.FileFacade(
                        kmPackage,
                        JvmMetadataVersion.LATEST_STABLE_SUPPORTED,
                        0,
                    )
                    .write()

            val writer = ClassWriter(0)
            writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
                "com/google/vtfx/core/InternalFacadeKt",
                null,
                "java/lang/Object",
                null,
            )
            writer.visitAnnotation("Lcom/google/vtfx/core/InternalVtfxApi;", false).visitEnd()
            val mv = writer.visitAnnotation("Lkotlin/Metadata;", true)
            mv.visit("k", metadata.kind)
            mv.visit("mv", metadata.metadataVersion)
            val d1 = mv.visitArray("d1")
            metadata.data1.forEach { d1.visit(null, it) }
            d1.visitEnd()
            val d2 = mv.visitArray("d2")
            metadata.data2.forEach { d2.visit(null, it) }
            d2.visitEnd()
            mv.visitEnd()
            writer
                .visitMethod(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                    "internalTopLevel",
                    "()V",
                    null,
                    null,
                )
                .visitEnd()
            writer.visitEnd()

            val result =
                KotlinSignatures.dump(
                    listOf(writer.toByteArray()),
                    setOf("com.google.vtfx.core.InternalVtfxApi"),
                )
            assertEquals("", result.text)
        }
    }

    @RunWith(RobolectricTestRunner::class)
    @Config(sdk = [36])
    class AndroidReproTest {
        /**
         * R5: `TerminalDisplay.detach()` cancels callbacks on `mainHandler` without resetting
         * `invalidationPending` or `synchronizationTimeoutPending` (TerminalDisplay.kt:104-109). If
         * background output posted an invalidation before `detach()`, re-attaching the display
         * leaves `invalidationPending` stuck at `true`, so subsequent output never updates
         * `observeVersion()`.
         */
        @Test
        fun r5_reattachingDisplayAfterDetachWithPendingBackgroundOutputStillUpdatesVersion() {
            val session = TerminalSession({})
            val display = TerminalDisplay(session)
            display.attach()

            // Background output posts an invalidation to the main looper without running it yet.
            val reader = Thread { session.feed("first".toByteArray(Charsets.UTF_8)) }
            reader.start()
            reader.join()

            // Detaching drops the pending mainHandler callbacks, then re-attaches.
            display.detach()
            display.attach()
            val versionAtReattach = display.observeVersion()

            session.feed("second".toByteArray(Charsets.UTF_8))
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(versionAtReattach + 1, display.observeVersion())
        }
    }
}

private class MethodSpec(
    val name: String,
    val descriptor: String,
    val access: Int = Opcodes.ACC_PUBLIC,
)

private class CallSpec(
    val opcode: Int,
    val owner: String,
    val name: String,
    val descriptor: String,
    val isInterface: Boolean,
)

private fun classBytes(
    name: String,
    superName: String = "java/lang/Object",
    interfaces: Array<String> = emptyArray(),
    access: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
    methods: List<MethodSpec> = emptyList(),
    calls: List<CallSpec> = emptyList(),
): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V17, access, name, null, superName, interfaces)
    for (m in methods) {
        val mv = writer.visitMethod(m.access, m.name, m.descriptor, null, null)
        if (m.access and Opcodes.ACC_ABSTRACT == 0) {
            mv.visitCode()
            if (m.descriptor.endsWith("Z")) {
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitInsn(Opcodes.IRETURN)
            } else {
                mv.visitInsn(Opcodes.RETURN)
            }
            mv.visitMaxs(1, 2)
        }
        mv.visitEnd()
    }
    if (calls.isNotEmpty()) {
        val mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null)
        mv.visitCode()
        for (c in calls) {
            if (c.opcode != Opcodes.INVOKESTATIC) {
                mv.visitInsn(Opcodes.ACONST_NULL)
            }
            if (c.descriptor.startsWith("(I)") || c.descriptor.startsWith("(Z)")) {
                mv.visitInsn(Opcodes.ICONST_0)
            }
            mv.visitMethodInsn(c.opcode, c.owner, c.name, c.descriptor, c.isInterface)
            if (c.descriptor.endsWith("Z")) {
                mv.visitInsn(Opcodes.POP)
            }
        }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(2, 1)
        mv.visitEnd()
    }
    writer.visitEnd()
    return writer.toByteArray()
}

private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

object TaskPropHolder {
    @JvmStatic var current: Map<String, Any> = emptyMap()

    @JvmStatic fun get(name: String): Any = current.getValue(name)
}

private fun runStandaloneTaskCheck(taskClassName: String, props: Map<String, Any>) {
    val rawBytes =
        CheckClassReferencesTask::class
            .java
            .classLoader
            .getResourceAsStream("$taskClassName.class")!!
            .use { it.readBytes() }
    val writer = ClassWriter(0)
    ClassReader(rawBytes)
        .accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    super.visit(
                        version,
                        access and Opcodes.ACC_ABSTRACT.inv(),
                        name,
                        signature,
                        "java/lang/Object",
                        interfaces,
                    )
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if ((access and Opcodes.ACC_ABSTRACT) != 0) {
                        val mv =
                            super.visitMethod(
                                access and Opcodes.ACC_ABSTRACT.inv(),
                                name,
                                descriptor,
                                signature,
                                exceptions,
                            )
                        mv.visitCode()
                        mv.visitLdcInsn(name)
                        mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "TaskPropHolder",
                            "get",
                            "(Ljava/lang/String;)Ljava/lang/Object;",
                            false,
                        )
                        mv.visitTypeInsn(
                            Opcodes.CHECKCAST,
                            descriptor.substringAfter(")L").removeSuffix(";"),
                        )
                        mv.visitInsn(Opcodes.ARETURN)
                        mv.visitMaxs(1, 1)
                        mv.visitEnd()
                        return null
                    }
                    val newAccess =
                        if (name == "check") {
                            (access and Opcodes.ACC_PROTECTED.inv()) or Opcodes.ACC_PUBLIC
                        } else {
                            access
                        }
                    val base = super.visitMethod(newAccess, name, descriptor, signature, exceptions)
                    return object : MethodVisitor(Opcodes.ASM9, base) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            val newOwner =
                                if (owner == "BoundaryCheckTask" && name == "<init>") {
                                    "java/lang/Object"
                                } else {
                                    owner
                                }
                            super.visitMethodInsn(opcode, newOwner, name, descriptor, isInterface)
                        }
                    }
                }
            },
            0,
        )
    val transformed = writer.toByteArray()
    val loader =
        object : ClassLoader(CheckClassReferencesTask::class.java.classLoader) {
            private var defined: MutableMap<String, Class<*>>? = null

            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                val defined =
                    this.defined
                        ?: mutableMapOf<String, Class<*>>().also { this.defined = it }
                if (name == taskClassName && taskClassName !in defined) {
                    defined[name] = defineClass(taskClassName, transformed, 0, transformed.size)
                }
                defined[name]?.let {
                    return it
                }
                if (name.startsWith("$taskClassName$")) {
                    val innerBytes =
                        parent.getResourceAsStream("$name.class")!!.use { it.readBytes() }
                    return defineClass(name, innerBytes, 0, innerBytes.size).also {
                        defined[name] = it
                    }
                }
                return super.loadClass(name, resolve)
            }
        }
    TaskPropHolder.current = props
    try {
        val cls = loader.loadClass(taskClassName)
        val instance = cls.getDeclaredConstructor().newInstance()
        cls.getMethod("check").invoke(instance)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
}

private inline fun <reified T : Any> proxyOf(crossinline handler: (String) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        handler(method.name)
    } as T

private fun fileCollectionOf(files: Set<File>): ConfigurableFileCollection =
    proxyOf { if (it == "getFiles") files else null }

private fun regularFilePropertyOf(file: File): RegularFileProperty {
    val reg = proxyOf<RegularFile> { if (it == "getAsFile") file else null }
    return proxyOf { if (it == "get") reg else null }
}

private fun <T : Any> listPropertyOf(items: List<T>): ListProperty<T> =
    proxyOf { if (it == "get") items else null }

private fun <T : Any> propertyOf(value: T): Property<T> =
    proxyOf { if (it == "get") value else null }

private fun runCheckClassReferencesTask(
    moduleName: String,
    boundariesFile: File,
    classDirs: Set<File>,
    variantJarsList: List<File>,
    classpathFiles: Set<File> = emptySet(),
) {
    runStandaloneTaskCheck(
        "CheckClassReferencesTask",
        mapOf(
            "getModule" to propertyOf(moduleName),
            "getBoundaries" to regularFilePropertyOf(boundariesFile),
            "getClassDirectories" to fileCollectionOf(classDirs),
            "getVariantDirectories" to listPropertyOf<Directory>(emptyList()),
            "getVariantJars" to
                listPropertyOf(
                    variantJarsList.map { f ->
                        proxyOf<RegularFile> { if (it == "getAsFile") f else null }
                    }
                ),
            "getClasspath" to fileCollectionOf(classpathFiles),
            "getProjectClasspath" to fileCollectionOf(emptySet()),
        ),
    )
}

private fun runCheckLibraryArtifactTask(
    artifactFile: File,
    classDirs: Set<File>,
    variantJarsList: List<File>,
) {
    runStandaloneTaskCheck(
        "CheckLibraryArtifactTask",
        mapOf(
            "getArtifact" to regularFilePropertyOf(artifactFile),
            "getClassDirectories" to fileCollectionOf(classDirs),
            "getVariantDirectories" to listPropertyOf<Directory>(emptyList()),
            "getVariantJars" to
                listPropertyOf(
                    variantJarsList.map { f ->
                        proxyOf<RegularFile> { if (it == "getAsFile") f else null }
                    }
                ),
        ),
    )
}
