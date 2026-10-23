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

import java.io.File
import java.util.stream.Stream
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Checks the class rules of the repository's config/boundaries.toml against references that they
 * must catch, including those that regular expressions over the sources caught before.
 */
class RepositoryClassRulesTest {
    private val boundaries = Boundaries.parse(File("../config/boundaries.toml").readText())

    /**
     * Android's hierarchy of Activity, which the rules on Context must see through, and the classes
     * of the platform; any other class stands for one that extends Object.
     */
    private val hierarchy = ClassHierarchy { name ->
        ANDROID_CLASSES[name]
            ?: ClassLoader.getPlatformClassLoader().getResourceAsStream("$name.class")?.use {
                it.readBytes()
            }
            ?: classFile(name, "java/lang/Object")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun classViolations_outsideAllowedModules_reportsIt(
        description: String,
        references: ClassReferences,
        allowedModule: String?,
    ) {
        val violations = boundaries.classViolations("vtfx-core", listOf(references), hierarchy)

        assertEquals(1, violations.size, violations.toString())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("casesWithAllowedModule")
    fun classViolations_inAllowedModule_none(
        description: String,
        references: ClassReferences,
        allowedModule: String,
    ) {
        assertEquals(
            emptyList(),
            boundaries.classViolations(allowedModule, listOf(references), hierarchy),
        )
    }

    @Test
    fun classViolations_verifierPackageInFont_reportsIt() {
        val violations =
            boundaries.classViolations(
                "vtfx-font",
                listOf(declaring("com/google/vtfx/font/gate/Copy")),
                hierarchy,
            )

        assertEquals(1, violations.size, violations.toString())
    }

    @Test
    fun classViolations_verifierPackageInFontGate_none() {
        val violations =
            boundaries.classViolations(
                "vtfx-font-gate",
                listOf(declaring("com/google/vtfx/font/gate/CanonicalVerifier")),
                hierarchy,
            )

        assertEquals(emptyList(), violations)
    }

    @Test
    fun classViolations_typefacesPackageInFontGateAndroid_none() {
        val violations =
            boundaries.classViolations(
                "vtfx-font-gate-android",
                listOf(declaring("com/google/vtfx/font/gate/android/SanitizedTypefaces")),
                hierarchy,
            )

        assertEquals(emptyList(), violations)
    }

    @Test
    fun classViolations_ownPackageInFont_none() {
        val violations =
            boundaries.classViolations(
                "vtfx-font",
                listOf(declaring("com/google/vtfx/font/FontFile")),
                hierarchy,
            )

        assertEquals(emptyList(), violations)
    }

    @Test
    fun classViolations_systemServiceByClass_none() {
        val references =
            using(
                "android/content/Context",
                "getSystemService",
                "(Ljava/lang/Class;)Ljava/lang/Object;",
            )

        assertEquals(
            emptyList(),
            boundaries.classViolations("vtfx-compose", listOf(references), hierarchy),
        )
    }

    @Test
    fun classViolations_bootstrapOfLambda_none() {
        val references =
            bootstrapping(
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                    "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                    "Ljava/lang/invoke/CallSite;",
            )

        assertEquals(
            emptyList(),
            boundaries.classViolations("vtfx-core", listOf(references), hierarchy),
        )
    }

    @Test
    fun classViolations_otherBootstrap_reportsIt() {
        val references =
            bootstrapping(
                "java/lang/runtime/ObjectMethods",
                "bootstrap",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/TypeDescriptor;" +
                    "Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;",
            )

        val violations = boundaries.classViolations("vtfx-core", listOf(references), hierarchy)

        assertEquals(1, violations.size, violations.toString())
    }

    companion object {
        @JvmStatic
        fun cases(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "LocalClipboardManager",
                    using(LOCALS, "getLocalClipboardManager", LOCAL),
                    GATE_ANDROID,
                ),
                Arguments.of(
                    "LocalClipboard",
                    using(LOCALS, "getLocalClipboard", LOCAL),
                    GATE_ANDROID,
                ),
                Arguments.of(
                    "ClipEntry",
                    naming("androidx/compose/ui/platform/ClipEntry"),
                    GATE_ANDROID,
                ),
                Arguments.of(
                    "Compose's ClipboardManager",
                    naming("androidx/compose/ui/platform/ClipboardManager"),
                    GATE_ANDROID,
                ),
                Arguments.of(
                    "ClipData.Item",
                    naming("android/content/ClipData\$Item"),
                    GATE_ANDROID,
                ),
                Arguments.of(
                    "LocalUriHandler",
                    using(LOCALS, "getLocalUriHandler", LOCAL),
                    GATE_ANDROID,
                ),
                Arguments.of(
                    "UriHandler",
                    naming("androidx/compose/ui/platform/UriHandler"),
                    GATE_ANDROID,
                ),
                Arguments.of(
                    "LinkAnnotation.Url",
                    naming("androidx/compose/ui/text/LinkAnnotation\$Url"),
                    GATE_ANDROID,
                ),
                Arguments.of(
                    "ShareCompat.IntentBuilder",
                    naming("androidx/core/app/ShareCompat\$IntentBuilder"),
                    GATE_ANDROID,
                ),
                Arguments.of(
                    "CustomTabsIntent.Builder",
                    naming("androidx/browser/customtabs/CustomTabsIntent\$Builder"),
                    GATE_ANDROID,
                ),
                Arguments.of(
                    "startActivity through Activity",
                    using("android/app/Activity", "startActivity", "(Landroid/os/Bundle;)V"),
                    GATE_ANDROID,
                ),
                Arguments.of(
                    "AndroidClipboard",
                    naming("com/google/vtfx/gate/android/AndroidClipboard"),
                    GATE_ANDROID,
                ),
                Arguments.of(
                    "AndroidUrlOpener",
                    naming("com/google/vtfx/gate/android/AndroidUrlOpener"),
                    GATE_ANDROID,
                ),
                Arguments.of("SSLSocketFactory", naming("javax/net/ssl/SSLSocketFactory"), null),
                Arguments.of("SocketFactory", naming("javax/net/SocketFactory"), null),
                Arguments.of("DownloadManager", naming("android/app/DownloadManager"), null),
                Arguments.of("MethodHandles", naming("java/lang/invoke/MethodHandles"), null),
                Arguments.of(
                    "kotlin.reflect.jvm",
                    naming("kotlin/reflect/jvm/KCallablesJvm"),
                    null,
                ),
                Arguments.of("kotlin.reflect.full", naming("kotlin/reflect/full/KClasses"), null),
                Arguments.of(
                    "Class.forName",
                    using("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;"),
                    null,
                ),
                Arguments.of(
                    "Class.getMethod",
                    using(
                        "java/lang/Class",
                        "getMethod",
                        "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                    ),
                    null,
                ),
                Arguments.of(
                    "getSystemService by name",
                    using(
                        "android/content/Context",
                        "getSystemService",
                        "(Ljava/lang/String;)Ljava/lang/Object;",
                    ),
                    null,
                ),
                Arguments.of("ProcessBuilder", naming("java/lang/ProcessBuilder"), null),
                Arguments.of("BitmapFactory", naming("android/graphics/BitmapFactory"), null),
                Arguments.of(
                    "Typeface.createFromFile",
                    using(
                        "android/graphics/Typeface",
                        "createFromFile",
                        "(Ljava/io/File;)Landroid/graphics/Typeface;",
                    ),
                    FONT_GATE_ANDROID,
                ),
                Arguments.of(
                    "FontFamily(Typeface)",
                    using(
                        "androidx/compose/ui/text/font/AndroidTypeface_androidKt",
                        "FontFamily",
                        "(Landroid/graphics/Typeface;)Landroidx/compose/ui/text/font/FontFamily;",
                    ),
                    FONT_GATE_ANDROID,
                ),
                Arguments.of(
                    "Font(File)",
                    naming("androidx/compose/ui/text/font/AndroidFontKt"),
                    FONT_GATE_ANDROID,
                ),
            )

        @JvmStatic
        fun casesWithAllowedModule(): Stream<Arguments> = cases().filter { it.get()[2] != null }

        private val ANDROID_CLASSES =
            mapOf(
                "android/app/Activity" to
                    classFile("android/app/Activity", "android/view/ContextThemeWrapper"),
                "android/view/ContextThemeWrapper" to
                    classFile("android/view/ContextThemeWrapper", "android/content/ContextWrapper"),
                "android/content/ContextWrapper" to
                    classFile("android/content/ContextWrapper", "android/content/Context"),
                "android/content/Context" to
                    classFile("android/content/Context", "java/lang/Object"),
            )

        private const val GATE_ANDROID = "vtfx-gate-android"
        private const val FONT_GATE_ANDROID = "vtfx-font-gate-android"
        private const val LOCALS = "androidx/compose/ui/platform/CompositionLocalsKt"
        private const val LOCAL = "()Landroidx/compose/runtime/ProvidableCompositionLocal;"

        private fun naming(type: String) =
            ClassReferences(
                "fixtures/Uses",
                setOf(type),
                emptySet(),
                emptySet(),
            )

        private fun using(owner: String, name: String, descriptor: String) =
            ClassReferences(
                "fixtures/Uses",
                emptySet(),
                setOf(MemberReference(owner, name, descriptor, MemberReference.Kind.METHOD)),
                emptySet(),
            )

        private fun bootstrapping(owner: String, name: String, descriptor: String) =
            ClassReferences(
                "fixtures/Uses",
                emptySet(),
                emptySet(),
                setOf(MemberReference(owner, name, descriptor, MemberReference.Kind.METHOD)),
            )

        private fun declaring(name: String) =
            ClassReferences(
                name,
                emptySet(),
                emptySet(),
                emptySet(),
            )

        /** A class file of an empty class [name] that extends [superName]. */
        private fun classFile(name: String, superName: String): ByteArray {
            val writer = ClassWriter(0)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null)
            writer.visitEnd()
            return writer.toByteArray()
        }
    }
}
