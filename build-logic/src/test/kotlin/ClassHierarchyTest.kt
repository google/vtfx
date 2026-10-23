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

class ClassHierarchyTest {
    /**
     * A view with a subclass of its own, the project's interfaces Haptics (which declares a method
     * of the view) and Constants (which declares a field of the view's subclass), and the view's
     * own interface Callback; any other class stands for one that extends Object.
     */
    private val classFiles =
        mapOf(
            "java/lang/Object" to classFile("java/lang/Object", null, emptyList(), "hashCode()I"),
            "android/view/View" to
                classFile(
                    "android/view/View",
                    "java/lang/Object",
                    listOf("android/view/Callback"),
                    "performHapticFeedback(I)Z",
                    "invalidate()V",
                    "TAG:Ljava/lang/String;",
                ),
            "android/view/Callback" to
                interfaceFile("android/view/Callback", emptyList(), "invalidate()V"),
            "android/view/ViewGroup" to
                classFile(
                    "android/view/ViewGroup",
                    "android/view/View",
                    emptyList(),
                    "ID:Ljava/lang/String;",
                ),
            "fixtures/Haptics" to
                interfaceFile(
                    "fixtures/Haptics",
                    emptyList(),
                    "performHapticFeedback(I)Z",
                    "hashCode()I",
                ),
            "fixtures/MoreHaptics" to
                interfaceFile("fixtures/MoreHaptics", listOf("fixtures/Haptics"), "invalidate()V"),
            "fixtures/Constants" to
                interfaceFile(
                    "fixtures/Constants",
                    emptyList(),
                    "ID:Ljava/lang/String;",
                    "TAG:Ljava/lang/String;",
                ),
        )

    @Test
    fun declaringClass_methodOfDeeperSuperclassAndOfOwnInterface_isTheSuperclass() {
        val hierarchy =
            hierarchy(classFile("fixtures/Own", "fixtures/Middle", listOf("fixtures/Haptics")))

        assertEquals(
            "android/view/View",
            hierarchy.declaringClass("fixtures/Own", "performHapticFeedback", "(I)Z"),
        )
    }

    @Test
    fun declaringClass_methodOnlyInInterface_isTheInterface() {
        val hierarchy =
            hierarchy(classFile("fixtures/Own", "java/lang/Object", listOf("fixtures/MoreHaptics")))

        assertEquals(
            "fixtures/Haptics",
            hierarchy.declaringClass("fixtures/Own", "performHapticFeedback", "(I)Z"),
        )
    }

    @Test
    fun declaringClass_methodOfObjectThroughInterface_isObject() {
        // An interface's superclass is Object, which the JVM looks in before the superinterfaces.
        val hierarchy = hierarchy()

        assertEquals(
            "java/lang/Object",
            hierarchy.declaringClass("fixtures/MoreHaptics", "hashCode", "()I"),
        )
    }

    @Test
    fun declaringClass_fieldOfSuperclassAndOfOwnInterface_isTheInterface() {
        // The JVM looks up a field in the interfaces of a class before its superclass.
        val hierarchy =
            hierarchy(
                classFile("fixtures/Own", "android/view/ViewGroup", listOf("fixtures/Constants"))
            )

        assertEquals(
            "fixtures/Constants",
            hierarchy.declaringClass("fixtures/Own", "ID", "Ljava/lang/String;"),
        )
    }

    @Test
    fun declaringClass_fieldOfSuperclassOfSuperclassAndOfInterfaceOfSuperclass_isTheInterface() {
        val hierarchy =
            hierarchy(
                classFile("fixtures/Own", "fixtures/WithConstants", emptyList()),
                classFile(
                    "fixtures/WithConstants",
                    "android/view/ViewGroup",
                    listOf("fixtures/Constants"),
                ),
            )

        assertEquals(
            "fixtures/Constants",
            hierarchy.declaringClass("fixtures/Own", "TAG", "Ljava/lang/String;"),
        )
    }

    @Test
    fun inheritedImplementations_ownInterfaceImplementedBySuperclass_isTheSuperclassMethod() {
        val hierarchy =
            hierarchy(classFile("fixtures/Own", "fixtures/Middle", listOf("fixtures/MoreHaptics")))

        assertEquals(
            setOf(
                method("android/view/View", "performHapticFeedback", "(I)Z"),
                method("android/view/View", "invalidate", "()V"),
                method("java/lang/Object", "hashCode", "()I"),
            ),
            hierarchy.inheritedImplementations("fixtures/Own").toSet(),
        )
    }

    @Test
    fun inheritedImplementations_methodsImplementedByTheClass_none() {
        val hierarchy =
            hierarchy(
                classFile(
                    "fixtures/Own",
                    "android/view/View",
                    listOf("fixtures/Haptics"),
                    "performHapticFeedback(I)Z",
                    "hashCode()I",
                )
            )

        assertEquals(emptyList(), hierarchy.inheritedImplementations("fixtures/Own"))
    }

    @Test
    fun inheritedImplementations_privateOrStaticMethodOfNearerClass_isTheInstanceMethodBeyond() {
        // Neither implements the interface's method, so the JVM selects the view's. Compilers
        // reject such code; the bytecode is made by hand.
        val hierarchy =
            hierarchy(
                classFile(
                    "fixtures/Own",
                    "fixtures/Hiding",
                    listOf("fixtures/Haptics"),
                    "static performHapticFeedback(I)Z",
                    "hashCode()I",
                ),
                classFile(
                    "fixtures/Hiding",
                    "android/view/View",
                    emptyList(),
                    "private performHapticFeedback(I)Z",
                ),
            )

        assertEquals(
            listOf(method("android/view/View", "performHapticFeedback", "(I)Z")),
            hierarchy.inheritedImplementations("fixtures/Own"),
        )
    }

    @Test
    fun inheritedImplementations_interfaceThatTheSuperclassImplements_none() {
        // The view implements Callback itself, so a subclass that names it opens up nothing.
        val hierarchy =
            hierarchy(
                classFile("fixtures/Own", "android/view/View", listOf("android/view/Callback"))
            )

        assertEquals(emptyList(), hierarchy.inheritedImplementations("fixtures/Own"))
    }

    @Test
    fun inheritedImplementations_interfaceOfProjectSuperclass_none() {
        // The superclass inherits the methods itself, and is checked for them on its own.
        val hierarchy =
            hierarchy(
                classFile("fixtures/Own", "fixtures/Tunnel", emptyList()),
                classFile("fixtures/Tunnel", "android/view/View", listOf("fixtures/Haptics")),
            )

        assertEquals(emptyList(), hierarchy.inheritedImplementations("fixtures/Own"))
        assertEquals(
            setOf(
                method("android/view/View", "performHapticFeedback", "(I)Z"),
                method("java/lang/Object", "hashCode", "()I"),
            ),
            hierarchy.inheritedImplementations("fixtures/Tunnel").toSet(),
        )
    }

    @Test
    fun inheritedImplementations_methodThatNoSuperclassDeclares_none() {
        val hierarchy =
            hierarchy(classFile("fixtures/Own", "java/lang/Object", listOf("fixtures/MoreHaptics")))

        assertEquals(
            listOf(method("java/lang/Object", "hashCode", "()I")),
            hierarchy.inheritedImplementations("fixtures/Own"),
        )
    }

    @Test
    fun inheritedImplementations_interface_none() {
        assertEquals(emptyList(), hierarchy().inheritedImplementations("fixtures/MoreHaptics"))
    }

    /** The hierarchy of [classFiles] and [more], with fixtures/Middle a subclass of the view. */
    private fun hierarchy(vararg more: ByteArray): ClassHierarchy {
        val all =
            classFiles +
                ("fixtures/Middle" to
                    classFile("fixtures/Middle", "android/view/View", emptyList())) +
                more.associateBy { ClassReferences.read(it).name }
        return ClassHierarchy { all[it] ?: classFile(it, "java/lang/Object", emptyList()) }
    }

    private fun method(owner: String, name: String, descriptor: String) =
        MemberReference(owner, name, descriptor, MemberReference.Kind.METHOD)

    /**
     * A class file of [name], extending [superName] and implementing [interfaces], that declares
     * the [members]: methods by name and descriptor, public and abstract unless `private ` or
     * `static ` comes first, and fields by name, `:`, and descriptor.
     */
    private fun classFile(
        name: String,
        superName: String?,
        interfaces: List<String>,
        vararg members: String,
    ): ByteArray = writeClass(Opcodes.ACC_PUBLIC, name, superName, interfaces, members)

    /** An interface [name], extending [superinterfaces], that declares the [members]. */
    private fun interfaceFile(
        name: String,
        superinterfaces: List<String>,
        vararg members: String,
    ): ByteArray =
        writeClass(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            name,
            "java/lang/Object",
            superinterfaces,
            members,
        )

    private fun writeClass(
        access: Int,
        name: String,
        superName: String?,
        interfaces: List<String>,
        members: Array<out String>,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, access, name, null, superName, interfaces.toTypedArray())
        for (member in members) {
            if (':' in member) {
                writer
                    .visitField(
                        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                        member.substringBefore(':'),
                        member.substringAfter(':'),
                        null,
                        null,
                    )
                    .visitEnd()
            } else {
                val modifier = member.substringBefore(' ', missingDelimiterValue = "")
                val method = member.substringAfter(' ')
                val descriptor = method.substring(method.indexOf('('))
                val methodAccess =
                    when (modifier) {
                        "" -> Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT
                        "private" -> Opcodes.ACC_PRIVATE
                        "static" -> Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC
                        else -> error("unknown modifier $modifier")
                    }
                writer
                    .visitMethod(
                        methodAccess,
                        method.removeSuffix(descriptor),
                        descriptor,
                        null,
                        null,
                    )
                    .visitEnd()
            }
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
