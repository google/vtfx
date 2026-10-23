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

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class AllowlistViolationsTest {
    private val boundaries =
        Boundaries.parse(
            """
            bootstrap_methods = []

            [baseline_references]
            classes = ["java/lang/Object"]
            packages = ["kotlin"]
            types = []
            members = []

            [modules.vtfx-core]
            allowed_dependencies = []
            check_classes = true

            [modules.vtfx-core.allowed_references]
            classes = ["java/util/Base64"]
            packages = []
            types = ["java/lang/Class"]
            members = ["android/content/Context.getSystemService(Ljava/lang/Class;)"]

            [modules.vtfx-sample]
            allowed_dependencies = ["vtfx-core"]
            check_classes = false
            """
        )

    /**
     * Object and Class, Context and a subclass of it, a view, the project's own subclass of the
     * view, and the project's interface ViewTunnel, which declares a method of the view, with a
     * subclass of the view and one of the own subclass that implement it with the view's method;
     * any other class stands for one that extends Object.
     */
    private val classFiles =
        mapOf(
            "java/lang/Object" to classFile("java/lang/Object", null, emptyList(), "hashCode()I"),
            "java/lang/Class" to
                classFile(
                    "java/lang/Class",
                    "java/lang/Object",
                    emptyList(),
                    "getName()Ljava/lang/String;",
                ),
            "android/content/Context" to
                classFile(
                    "android/content/Context",
                    "java/lang/Object",
                    emptyList(),
                    "getSystemService(Ljava/lang/Class;)Ljava/lang/Object;",
                    "getPackageName()Ljava/lang/String;",
                ),
            "android/app/Activity" to
                classFile("android/app/Activity", "android/content/Context", emptyList()),
            "android/view/View" to
                classFile(
                    "android/view/View",
                    "java/lang/Object",
                    emptyList(),
                    "getContext()Landroid/content/Context;",
                    "setKeepScreenOn(Z)V",
                ),
            "fixtures/OwnView" to classFile("fixtures/OwnView", "android/view/View", emptyList()),
            "fixtures/ViewTunnel" to interfaceFile("fixtures/ViewTunnel", "setKeepScreenOn(Z)V"),
            "fixtures/TunnelView" to
                classFile(
                    "fixtures/TunnelView",
                    "android/view/View",
                    listOf("fixtures/ViewTunnel"),
                ),
            "fixtures/DeeperTunnelView" to
                classFile(
                    "fixtures/DeeperTunnelView",
                    "fixtures/OwnView",
                    listOf("fixtures/ViewTunnel"),
                ),
        )

    private val hierarchy = ClassHierarchy {
        classFiles[it] ?: classFile(it, "java/lang/Object", emptyList())
    }

    @Test
    fun allowlistViolations_classNotListed_reportsIt() {
        val violations = violations("vtfx-core", naming("java/lang/System"))

        assertEquals(
            listOf(
                "fixtures/Uses refers to java/lang/System, which the allowlist of vtfx-core does not allow"
            ),
            violations,
        )
    }

    @Test
    fun allowlistViolations_classOfBaseline_none() {
        assertEquals(emptyList(), violations("vtfx-core", naming("java/lang/Object")))
    }

    @Test
    fun allowlistViolations_classOfModule_none() {
        assertEquals(emptyList(), violations("vtfx-core", naming("java/util/Base64")))
    }

    @Test
    fun allowlistViolations_nestedClassOfListedClass_reportsIt() {
        val violations = violations("vtfx-core", naming("java/util/Base64\$Decoder"))

        assertEquals(1, violations.size, violations.toString())
    }

    @Test
    fun allowlistViolations_classInListedPackage_none() {
        assertEquals(emptyList(), violations("vtfx-core", naming("kotlin/Unit")))
    }

    @Test
    fun allowlistViolations_classInSubpackageOfListedPackage_reportsIt() {
        val violations = violations("vtfx-core", naming("kotlin/io/FilesKt"))

        assertEquals(1, violations.size, violations.toString())
    }

    @Test
    fun allowlistViolations_typeOnlyClassNamed_none() {
        assertEquals(emptyList(), violations("vtfx-core", naming("java/lang/Class")))
    }

    @Test
    fun allowlistViolations_memberOfTypeOnlyClass_reportsIt() {
        val violations =
            violations("vtfx-core", using("java/lang/Class", "getName", "()Ljava/lang/String;"))

        assertEquals(
            listOf(
                "fixtures/Uses uses java/lang/Class.getName()Ljava/lang/String;, which the allowlist of " +
                    "vtfx-core does not allow"
            ),
            violations,
        )
    }

    @Test
    fun allowlistViolations_listedMemberThroughSubtype_none() {
        val references =
            using(
                "android/app/Activity",
                "getSystemService",
                "(Ljava/lang/Class;)Ljava/lang/Object;",
            )

        assertEquals(emptyList(), violations("vtfx-core", references))
    }

    @Test
    fun allowlistViolations_otherMemberOfListedOwner_reportsIt() {
        val references = using("android/content/Context", "getPackageName", "()Ljava/lang/String;")

        val violations = violations("vtfx-core", references)

        assertEquals(1, violations.size, violations.toString())
    }

    @Test
    fun allowlistViolations_memberOfListedClassThroughOtherClass_none() {
        val references = using("android/content/Context", "hashCode", "()I")

        assertEquals(emptyList(), violations("vtfx-core", references))
    }

    @Test
    fun allowlistViolations_memberInheritedByOwnClass_reportsDeclaringClass() {
        val references =
            ClassReferences(
                "fixtures/OwnView",
                emptySet(),
                setOf(method("fixtures/OwnView", "getContext", "()Landroid/content/Context;")),
                emptySet(),
            )

        val violations =
            boundaries.allowlistViolations("vtfx-core", listOf(references), emptySet(), hierarchy)

        assertEquals(
            listOf(
                "fixtures/OwnView uses android/view/View.getContext()Landroid/content/Context;, which " +
                    "the allowlist of vtfx-core does not allow"
            ),
            violations,
        )
    }

    @Test
    fun allowlistViolations_methodInheritedToImplementOwnInterface_reportsDeclaringClass() {
        // A call through the interface reaches the view's method, though nothing names it.
        val classes =
            listOf(
                ClassReferences("fixtures/TunnelView", emptySet(), emptySet(), emptySet()),
                ClassReferences(
                    "fixtures/Uses",
                    setOf("fixtures/ViewTunnel"),
                    setOf(method("fixtures/ViewTunnel", "setKeepScreenOn", "(Z)V")),
                    emptySet(),
                ),
            )

        val violations =
            boundaries.allowlistViolations(
                "vtfx-core",
                classes,
                setOf("fixtures/ViewTunnel"),
                hierarchy,
            )

        assertEquals(
            listOf(
                "fixtures/TunnelView uses android/view/View.setKeepScreenOn(Z)V, which the " +
                    "allowlist of vtfx-core does not allow"
            ),
            violations,
        )
    }

    @Test
    fun allowlistViolations_memberOfDeeperSuperclassAndOfOwnInterface_reportsSuperclass() {
        val references =
            ClassReferences(
                "fixtures/Uses",
                setOf("fixtures/DeeperTunnelView"),
                setOf(method("fixtures/DeeperTunnelView", "setKeepScreenOn", "(Z)V")),
                emptySet(),
            )

        val violations =
            boundaries.allowlistViolations(
                "vtfx-core",
                listOf(references),
                setOf("fixtures/DeeperTunnelView", "fixtures/OwnView", "fixtures/ViewTunnel"),
                hierarchy,
            )

        assertEquals(
            listOf(
                "fixtures/Uses uses android/view/View.setKeepScreenOn(Z)V, which the allowlist " +
                    "of vtfx-core does not allow"
            ),
            violations,
        )
    }

    @Test
    fun allowlistViolations_classOfDependency_none() {
        val violations =
            boundaries.allowlistViolations(
                "vtfx-core",
                listOf(naming("com/google/vtfx/gate/HostConnection")),
                setOf("com/google/vtfx/gate/HostConnection"),
                hierarchy,
            )

        assertEquals(emptyList(), violations)
    }

    @Test
    fun allowlistViolations_moduleWithoutClassCheck_none() {
        assertEquals(emptyList(), violations("vtfx-sample", naming("java/lang/System")))
    }

    private fun violations(module: String, references: ClassReferences): List<String> =
        boundaries.allowlistViolations(module, listOf(references), emptySet(), hierarchy)

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
            setOf(owner),
            setOf(method(owner, name, descriptor)),
            emptySet(),
        )

    private fun method(owner: String, name: String, descriptor: String) =
        MemberReference(owner, name, descriptor, MemberReference.Kind.METHOD)

    /**
     * A class file of [name], extending [superName] and implementing [interfaces], that declares
     * the [methods] (name and descriptor).
     */
    private fun classFile(
        name: String,
        superName: String?,
        interfaces: List<String>,
        vararg methods: String,
    ): ByteArray = writeClass(Opcodes.ACC_PUBLIC, name, superName, interfaces, methods)

    /** An interface [name] that declares the [methods] (name and descriptor). */
    private fun interfaceFile(name: String, vararg methods: String): ByteArray =
        writeClass(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            name,
            "java/lang/Object",
            emptyList(),
            methods,
        )

    private fun writeClass(
        access: Int,
        name: String,
        superName: String?,
        interfaces: List<String>,
        methods: Array<out String>,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, access, name, null, superName, interfaces.toTypedArray())
        for (method in methods) {
            val descriptor = method.substring(method.indexOf('('))
            writer
                .visitMethod(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                    method.removeSuffix(descriptor),
                    descriptor,
                    null,
                    null,
                )
                .visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
