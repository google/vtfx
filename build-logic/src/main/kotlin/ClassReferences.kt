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

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import org.objectweb.asm.Type
import org.objectweb.asm.TypePath
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor

/**
 * What one compiled class refers to, for the boundary check (docs/decisions/0044).
 *
 * @property name the internal name of the class (e.g. `com/google/vtfx/gate/HostConnection`).
 * @property types every class that the class names anywhere: as its supertypes, in descriptors and
 *   generic signatures, in annotations, in instructions, in constants, in the owners of members it
 *   uses, and in bootstrap methods. Arrays are reduced to their element types; primitive types are
 *   left out.
 * @property members every field and method that the class uses: in instructions, method handles,
 *   and the arguments of bootstrap methods.
 * @property bootstraps the bootstrap methods of dynamic call sites and constants, such as the
 *   factory of lambdas that the compiler uses. The types of their own descriptors, which name
 *   classes of `java/lang/invoke`, are not in [types].
 */
class ClassReferences(
    val name: String,
    val types: Set<String>,
    val members: Set<MemberReference>,
    val bootstraps: Set<MemberReference>,
) {
    companion object {
        /** Reads the references of the class file [bytes]. Debug information is not read. */
        fun read(bytes: ByteArray): ClassReferences {
            val collector = Collector()
            ClassReader(bytes).accept(collector, ClassReader.SKIP_DEBUG)
            return ClassReferences(
                collector.name,
                collector.types - collector.name,
                collector.members,
                collector.bootstraps,
            )
        }
    }
}

/** A use of the field or method [name] with [descriptor], through [owner]. */
data class MemberReference(
    val owner: String,
    val name: String,
    val descriptor: String,
    val kind: Kind,
) {
    enum class Kind {
        FIELD,
        METHOD,
    }

    override fun toString(): String =
        when (kind) {
            Kind.FIELD -> "$owner.$name:$descriptor"
            Kind.METHOD -> "$owner.$name$descriptor"
        }
}

private const val API = Opcodes.ASM9

private class Collector : ClassVisitor(API) {
    lateinit var name: String
    val types = mutableSetOf<String>()
    val members = mutableSetOf<MemberReference>()
    val bootstraps = mutableSetOf<MemberReference>()

    private val annotations =
        object : AnnotationVisitor(API) {
            override fun visit(name: String?, value: Any?) {
                if (value is Type) addType(value)
            }

            override fun visitEnum(name: String?, descriptor: String, value: String?) {
                addDescriptor(descriptor)
            }

            override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor {
                addDescriptor(descriptor)
                return this
            }

            override fun visitArray(name: String?): AnnotationVisitor = this
        }

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        this.name = name
        superName?.let(types::add)
        interfaces?.let { types.addAll(it) }
        addSignature(signature)
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor =
        annotation(descriptor)

    override fun visitTypeAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        descriptor: String,
        visible: Boolean,
    ): AnnotationVisitor = annotation(descriptor)

    override fun visitRecordComponent(
        name: String,
        descriptor: String,
        signature: String?,
    ): RecordComponentVisitor {
        addDescriptor(descriptor)
        addSignature(signature)
        return object : RecordComponentVisitor(API) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor =
                annotation(descriptor)

            override fun visitTypeAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                descriptor: String,
                visible: Boolean,
            ): AnnotationVisitor = annotation(descriptor)
        }
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?,
    ): FieldVisitor {
        addDescriptor(descriptor)
        addSignature(signature)
        return object : FieldVisitor(API) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor =
                annotation(descriptor)

            override fun visitTypeAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                descriptor: String,
                visible: Boolean,
            ): AnnotationVisitor = annotation(descriptor)
        }
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        addDescriptor(descriptor)
        addSignature(signature)
        exceptions?.forEach(types::add)
        return Code()
    }

    private inner class Code : MethodVisitor(API) {
        override fun visitAnnotationDefault(): AnnotationVisitor = annotations

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor =
            annotation(descriptor)

        override fun visitTypeAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor = annotation(descriptor)

        override fun visitParameterAnnotation(
            parameter: Int,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor = annotation(descriptor)

        override fun visitTypeInsn(opcode: Int, type: String) {
            addInternalName(type)
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
            addMember(owner, name, descriptor, MemberReference.Kind.FIELD)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            addMember(owner, name, descriptor, MemberReference.Kind.METHOD)
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any?,
        ) {
            addDescriptor(descriptor)
            addBootstrap(bootstrapMethodHandle)
            bootstrapMethodArguments.forEach(::addConstant)
        }

        override fun visitLdcInsn(value: Any?) {
            addConstant(value)
        }

        override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
            addDescriptor(descriptor)
        }

        override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
            type?.let(types::add)
        }

        override fun visitInsnAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor = annotation(descriptor)

        override fun visitTryCatchAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor = annotation(descriptor)
    }

    /** Adds the type of an annotation, and returns the visitor of its values. */
    private fun annotation(descriptor: String): AnnotationVisitor {
        addDescriptor(descriptor)
        return annotations
    }

    private fun addMember(
        owner: String,
        name: String,
        descriptor: String,
        kind: MemberReference.Kind,
    ) {
        addInternalName(owner)
        addDescriptor(descriptor)
        // The owner of a method of an array (such as clone) is the array's descriptor.
        val ownerType = if (owner.startsWith("[")) elementType(Type.getType(owner)) else owner
        if (ownerType != null) members += MemberReference(ownerType, name, descriptor, kind)
    }

    private fun addHandle(handle: Handle) {
        val kind =
            if (handle.tag <= Opcodes.H_PUTSTATIC) MemberReference.Kind.FIELD
            else MemberReference.Kind.METHOD
        addMember(handle.owner, handle.name, handle.desc, kind)
    }

    private fun addBootstrap(handle: Handle) {
        bootstraps +=
            MemberReference(handle.owner, handle.name, handle.desc, MemberReference.Kind.METHOD)
    }

    private fun addConstant(value: Any?) {
        when (value) {
            is Type -> addType(value)
            is Handle -> addHandle(value)
            is ConstantDynamic -> {
                addDescriptor(value.descriptor)
                addBootstrap(value.bootstrapMethod)
                for (index in 0 until value.bootstrapMethodArgumentCount) {
                    addConstant(value.getBootstrapMethodArgument(index))
                }
            }
        }
    }

    /** Adds an internal name, which is an array's descriptor for array types. */
    private fun addInternalName(name: String) {
        if (name.startsWith("[")) addDescriptor(name) else types += name
    }

    private fun addDescriptor(descriptor: String) {
        addType(Type.getType(descriptor))
    }

    private fun addType(type: Type) {
        when (type.sort) {
            Type.METHOD -> {
                addType(type.returnType)
                type.argumentTypes.forEach(::addType)
            }
            else -> elementType(type)?.let(types::add)
        }
    }

    private fun addSignature(signature: String?) {
        if (signature == null) return
        SignatureReader(signature)
            .accept(
                object : SignatureVisitor(API) {
                    // The class types being visited: a type argument visits its own inside them.
                    private val open = ArrayDeque<String>()

                    override fun visitClassType(name: String) {
                        open.addLast(name)
                        types += name
                    }

                    override fun visitInnerClassType(name: String) {
                        val inner = "${open.removeLast()}$$name"
                        open.addLast(inner)
                        types += inner
                    }

                    override fun visitEnd() {
                        open.removeLast()
                    }
                }
            )
    }
}

/** The internal name of the class that [type] is, or is an array of; null for primitive types. */
private fun elementType(type: Type): String? {
    val element = if (type.sort == Type.ARRAY) type.elementType else type
    return if (element.sort == Type.OBJECT) element.internalName else null
}
