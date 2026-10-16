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

import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmConstructor
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeAlias
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmValueParameter
import kotlin.metadata.KmVariance
import kotlin.metadata.Modality
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isConst
import kotlin.metadata.isCrossinline
import kotlin.metadata.isData
import kotlin.metadata.isDefinitelyNonNull
import kotlin.metadata.isFunInterface
import kotlin.metadata.isInfix
import kotlin.metadata.isInline
import kotlin.metadata.isInner
import kotlin.metadata.isLateinit
import kotlin.metadata.isNoinline
import kotlin.metadata.isNullable
import kotlin.metadata.isOperator
import kotlin.metadata.isReified
import kotlin.metadata.isSuspend
import kotlin.metadata.isValue
import kotlin.metadata.isVar
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.Metadata
import kotlin.metadata.jvm.annotations
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.jvm.syntheticMethodForAnnotations
import kotlin.metadata.kind
import kotlin.metadata.modality
import kotlin.metadata.visibility
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Renders the Kotlin signatures of the public API from compiled classes, as a complement to the ABI
 * dump (docs/decisions/0016).
 *
 * The ABI dump lists JVM descriptors, which leave out what only the Kotlin metadata holds: type
 * arguments, nullability, parameter names, which parameters have default values, and modifiers such
 * as `sealed` and `fun interface`. Changes to those break apps too, either their compilation or,
 * for default values and type arguments, apps compiled earlier.
 *
 * Declarations that are not public or protected, or that carry one of the excluded annotations (or
 * are nested in a class that does), are left out, like in the ABI dump.
 */
object KotlinSignatures {
    /** The rendered signatures, and the public `const val`s found (docs/decisions/0037). */
    class Result(val text: String, val publicConstants: List<String>)

    fun dump(classFiles: Collection<ByteArray>, excludedAnnotations: Set<String>): Result {
        val excluded = excludedAnnotations.mapTo(HashSet()) { "L${it.replace('.', '/')};" }
        val parsed = classFiles.map(::parse)
        val classes = HashMap<String, Parsed>()
        for (p in parsed) {
            val metadata = p.metadata ?: continue
            if (metadata is KotlinClassMetadata.Class) classes[metadata.kmClass.name] = p
        }
        val renderer = Renderer(excluded, classes)
        val sections = parsed.mapNotNull { renderer.section(it) }.sortedBy { it.first }
        return Result(
            sections.joinToString("") { it.second },
            renderer.publicConstants.sorted(),
        )
    }

    /** What the renderer needs of a class file: its metadata, annotations, and members. */
    private class Parsed(val internalName: String) {
        var metadata: KotlinClassMetadata? = null
        val annotations = HashSet<String>()
        var deprecation: String? = null
        val methods = HashMap<String, Member>()
        val fields = HashMap<String, Member>()
    }

    private class Member {
        val annotations = HashSet<String>()
        var deprecation: String? = null
    }

    private fun parse(bytes: ByteArray): Parsed {
        val reader = ClassReader(bytes)
        val parsed = Parsed(reader.className)
        val metadata = HashMap<String, Any>()
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor? {
                    parsed.annotations += descriptor
                    return when (descriptor) {
                        "Lkotlin/Metadata;" -> MetadataVisitor(metadata)
                        DEPRECATED -> DeprecationVisitor { parsed.deprecation = it }
                        else -> null
                    }
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val member = Member()
                    parsed.methods[name + descriptor] = member
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(
                            descriptor: String,
                            visible: Boolean,
                        ): AnnotationVisitor? = member.visitAnnotation(descriptor)
                    }
                }

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor {
                    val member = Member()
                    parsed.fields[name + ":" + descriptor] = member
                    return object : FieldVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(
                            descriptor: String,
                            visible: Boolean,
                        ): AnnotationVisitor? = member.visitAnnotation(descriptor)
                    }
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        if (metadata.isNotEmpty()) {
            @Suppress("UNCHECKED_CAST")
            parsed.metadata =
                KotlinClassMetadata.readStrict(
                    Metadata(
                        kind = metadata["k"] as Int?,
                        metadataVersion = metadata["mv"] as IntArray?,
                        data1 = (metadata["d1"] as List<String>?)?.toTypedArray(),
                        data2 = (metadata["d2"] as List<String>?)?.toTypedArray(),
                        extraString = metadata["xs"] as String?,
                        packageName = metadata["pn"] as String?,
                        extraInt = metadata["xi"] as Int?,
                    )
                )
        }
        return parsed
    }

    private fun Member.visitAnnotation(descriptor: String): AnnotationVisitor? {
        annotations += descriptor
        return if (descriptor == DEPRECATED) DeprecationVisitor { deprecation = it } else null
    }

    /** Collects the values of `@kotlin.Metadata`; ASM passes arrays of ints as one value. */
    private class MetadataVisitor(private val values: MutableMap<String, Any>) :
        AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(name: String, value: Any) {
            values[name] = value
        }

        override fun visitArray(name: String): AnnotationVisitor {
            val list = ArrayList<String>()
            values[name] = list
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String?, value: Any) {
                    list += value as String
                }
            }
        }
    }

    /** Reads the level of `@kotlin.Deprecated`, which is WARNING unless given. */
    private class DeprecationVisitor(private val result: (String) -> Unit) :
        AnnotationVisitor(Opcodes.ASM9) {
        private var level = "WARNING"

        override fun visitEnum(name: String, descriptor: String, value: String) {
            if (name == "level") level = value
        }

        override fun visitEnd() = result(level)
    }

    private class Renderer(
        private val excluded: Set<String>,
        private val classes: Map<String, Parsed>,
    ) {
        val publicConstants = ArrayList<String>()

        /** Returns the name and the text of the section of a class or file facade, if public. */
        fun section(parsed: Parsed): Pair<String, String>? =
            when (val metadata = parsed.metadata) {
                is KotlinClassMetadata.Class -> classSection(parsed, metadata.kmClass)
                is KotlinClassMetadata.FileFacade ->
                    facadeSection(
                        parsed,
                        metadata.kmPackage.functions,
                        metadata.kmPackage.properties,
                        metadata.kmPackage.typeAliases,
                    )
                is KotlinClassMetadata.MultiFileClassPart ->
                    facadeSection(
                        parsed,
                        metadata.kmPackage.functions,
                        metadata.kmPackage.properties,
                        metadata.kmPackage.typeAliases,
                    )
                else -> null
            }

        private fun isPublic(visibility: Visibility) =
            visibility == Visibility.PUBLIC || visibility == Visibility.PROTECTED

        private fun isIncluded(kmClass: KmClass): Boolean {
            val parsed = classes[kmClass.name] ?: return false
            if (!isPublic(kmClass.visibility)) return false
            if (parsed.annotations.any { it in excluded }) return false
            val outer = kmClass.name.substringBeforeLast('.', "")
            if (outer.isEmpty()) return true
            val outerClass = classes[outer]?.metadata as? KotlinClassMetadata.Class ?: return false
            return isIncluded(outerClass.kmClass)
        }

        /**
         * The type parameters in scope of [kmClass]: its own and those of the classes it is inner
         * to.
         */
        private fun scope(kmClass: KmClass): Map<Int, String> {
            val names = HashMap<Int, String>()
            kmClass.typeParameters.forEach { names[it.id] = it.name }
            val outer = kmClass.name.substringBeforeLast('.', "")
            if (kmClass.isInner && outer.isNotEmpty()) {
                (classes[outer]?.metadata as? KotlinClassMetadata.Class)?.let {
                    names.putAll(scope(it.kmClass))
                }
            }
            return names
        }

        private fun classSection(parsed: Parsed, kmClass: KmClass): Pair<String, String>? {
            if (!isIncluded(kmClass)) return null
            val name = kmClass.name.replace('/', '.')
            val types = scope(kmClass)
            val header = buildString {
                append(deprecation(parsed.deprecation))
                append(visibility(kmClass.visibility)).append(' ')
                val kind = kmClass.kind
                if (kind == ClassKind.CLASS || kind == ClassKind.INTERFACE) {
                    val modality = kmClass.modality
                    if (kind == ClassKind.CLASS || modality == Modality.SEALED) {
                        append(modality.name.lowercase()).append(' ')
                    }
                }
                if (kmClass.isData) append("data ")
                if (kmClass.isValue) append("value ")
                if (kmClass.isInner) append("inner ")
                if (kmClass.isFunInterface) append("fun ")
                append(
                    when (kind) {
                        ClassKind.CLASS -> "class"
                        ClassKind.INTERFACE -> "interface"
                        ClassKind.ENUM_CLASS -> "enum class"
                        ClassKind.ENUM_ENTRY -> "enum entry"
                        ClassKind.ANNOTATION_CLASS -> "annotation class"
                        ClassKind.OBJECT -> "object"
                        ClassKind.COMPANION_OBJECT -> "companion object"
                    }
                )
                append(' ').append(name)
                append(typeParameters(kmClass.typeParameters, types))
                val supertypes =
                    kmClass.supertypes.filter {
                        (it.classifier as? KmClassifier.Class)?.name != "kotlin/Any"
                    }
                if (supertypes.isNotEmpty()) {
                    append(" : ").append(supertypes.joinToString { type(it, types) })
                }
            }
            val members = ArrayList<String>()
            if (kmClass.kind != ClassKind.OBJECT && kmClass.kind != ClassKind.COMPANION_OBJECT) {
                kmClass.constructors.forEach { constructor(parsed, it, types)?.let(members::add) }
            }
            kmClass.properties.forEach { property(parsed, name, it, types)?.let(members::add) }
            kmClass.functions.forEach { function(parsed, it, types)?.let(members::add) }
            members.sort()
            // Enum entries keep their order, which `entries` and `values()` expose.
            kmClass.kmEnumEntries.forEach { members += "entry ${it.name}" }
            return name to render(header, members)
        }

        private fun facadeSection(
            parsed: Parsed,
            functions: List<KmFunction>,
            properties: List<KmProperty>,
            typeAliases: List<KmTypeAlias>,
        ): Pair<String, String>? {
            val name = parsed.internalName.replace('/', '.')
            val members = ArrayList<String>()
            properties.forEach { property(parsed, name, it, emptyMap())?.let(members::add) }
            functions.forEach { function(parsed, it, emptyMap())?.let(members::add) }
            typeAliases.forEach { typeAlias(it)?.let(members::add) }
            if (members.isEmpty()) return null
            members.sort()
            return name to render("file $name", members)
        }

        private fun render(header: String, members: List<String>) = buildString {
            append(header).append(" {\n")
            members.forEach { append("    ").append(it).append('\n') }
            append("}\n\n")
        }

        private fun method(parsed: Parsed, signature: JvmMethodSignature?): Member? =
            signature?.let {
                parsed.methods[it.name + it.descriptor]
            }

        private fun isExcluded(member: Member?) =
            member?.annotations?.any { it in excluded } == true

        private fun constructor(
            parsed: Parsed,
            c: KmConstructor,
            types: Map<Int, String>,
        ): String? {
            if (!isPublic(c.visibility)) return null
            val member = method(parsed, c.signature)
            if (isExcluded(member)) return null
            return deprecation(member?.deprecation) +
                visibility(c.visibility) +
                " constructor" +
                parameters(c.valueParameters, types)
        }

        private fun function(parsed: Parsed, f: KmFunction, outer: Map<Int, String>): String? {
            if (!isPublic(f.visibility)) return null
            val member = method(parsed, f.signature)
            if (isExcluded(member)) return null
            val types = outer + f.typeParameters.associate { it.id to it.name }
            return buildString {
                append(deprecation(member?.deprecation))
                if (member?.annotations?.contains(COMPOSABLE) == true) append("@Composable ")
                append(visibility(f.visibility)).append(' ')
                append(f.modality.name.lowercase()).append(' ')
                if (f.isOperator) append("operator ")
                if (f.isInfix) append("infix ")
                if (f.isInline) append("inline ")
                if (f.isSuspend) append("suspend ")
                append("fun ")
                if (f.typeParameters.isNotEmpty()) {
                    append(typeParameters(f.typeParameters, types)).append(' ')
                }
                f.receiverParameterType?.let { append(type(it, types)).append('.') }
                append(f.name)
                append(parameters(f.valueParameters, types))
                append(": ").append(type(f.returnType, types))
            }
        }

        private fun property(
            parsed: Parsed,
            owner: String,
            p: KmProperty,
            outer: Map<Int, String>,
        ): String? {
            if (!isPublic(p.visibility)) return null
            val annotationsMethod = method(parsed, p.syntheticMethodForAnnotations)
            val getter = method(parsed, p.getterSignature)
            val field = p.fieldSignature?.let { parsed.fields[it.name + ":" + it.descriptor] }
            if (listOf(annotationsMethod, getter, field).any(::isExcluded)) return null
            if (p.isConst) publicConstants += "$owner.${p.name}"
            val types = outer + p.typeParameters.associate { it.id to it.name }
            return buildString {
                append(deprecation(annotationsMethod?.deprecation ?: getter?.deprecation))
                append(visibility(p.visibility)).append(' ')
                append(p.modality.name.lowercase()).append(' ')
                if (p.isConst) append("const ")
                if (p.isLateinit) append("lateinit ")
                append(if (p.isVar) "var " else "val ")
                if (p.typeParameters.isNotEmpty()) {
                    append(typeParameters(p.typeParameters, types)).append(' ')
                }
                p.receiverParameterType?.let { append(type(it, types)).append('.') }
                append(p.name).append(": ").append(type(p.returnType, types))
                val setter = p.setter
                if (p.isVar && setter != null && setter.visibility != p.visibility) {
                    append(" { ").append(visibility(setter.visibility)).append(" set }")
                }
            }
        }

        private fun typeAlias(t: KmTypeAlias): String? {
            if (!isPublic(t.visibility)) return null
            val types = t.typeParameters.associate { it.id to it.name }
            return visibility(t.visibility) +
                " typealias " +
                t.name +
                typeParameters(t.typeParameters, types) +
                " = " +
                type(t.underlyingType, types)
        }

        private fun parameters(parameters: List<KmValueParameter>, types: Map<Int, String>) =
            parameters.joinToString(prefix = "(", postfix = ")") { p ->
                buildString {
                    if (p.varargElementType != null) append("vararg ")
                    if (p.isCrossinline) append("crossinline ")
                    if (p.isNoinline) append("noinline ")
                    append(p.name).append(": ")
                    append(type(p.varargElementType ?: p.type, types))
                    // The value is in the body of the function, so a change of it needs no review;
                    // whether there is one does (docs/reviews/2026-10-16-abi.md, R1).
                    if (p.declaresDefaultValue) append(" = ...")
                }
            }

        private fun typeParameters(parameters: List<KmTypeParameter>, types: Map<Int, String>) =
            if (parameters.isEmpty()) ""
            else
                parameters.joinToString(prefix = "<", postfix = ">") { t ->
                    buildString {
                        if (t.isReified) append("reified ")
                        variance(t.variance)?.let { append(it).append(' ') }
                        append(t.name)
                        val bounds =
                            t.upperBounds.filterNot {
                                (it.classifier as? KmClassifier.Class)?.name == "kotlin/Any" &&
                                    it.isNullable
                            }
                        if (bounds.isNotEmpty()) {
                            append(" : ").append(bounds.joinToString(" & ") { type(it, types) })
                        }
                    }
                }

        private fun type(t: KmType, types: Map<Int, String>): String = buildString {
            t.annotations.forEach { append('@').append(it.className.replace('/', '.')).append(' ') }
            if (t.isSuspend) append("suspend ")
            append(
                when (val c = t.classifier) {
                    is KmClassifier.Class -> c.name.replace('/', '.')
                    is KmClassifier.TypeAlias -> c.name.replace('/', '.')
                    is KmClassifier.TypeParameter -> types[c.id] ?: "T${c.id}"
                }
            )
            if (t.arguments.isNotEmpty()) {
                append(
                    t.arguments.joinToString(prefix = "<", postfix = ">") { projection(it, types) }
                )
            }
            if (t.isNullable) append('?')
            if (t.isDefinitelyNonNull) append(" & Any")
        }

        private fun projection(p: KmTypeProjection, types: Map<Int, String>): String {
            val type = p.type ?: return "*"
            val variance = p.variance?.let(::variance)
            return if (variance == null) type(type, types) else "$variance ${type(type, types)}"
        }

        private fun variance(v: KmVariance) =
            when (v) {
                KmVariance.INVARIANT -> null
                KmVariance.IN -> "in"
                KmVariance.OUT -> "out"
            }

        private fun visibility(v: Visibility) = v.name.lowercase()

        private fun deprecation(level: String?) = if (level == null) "" else "@Deprecated($level) "
    }

    private const val DEPRECATED = "Lkotlin/Deprecated;"
    private const val COMPOSABLE = "Landroidx/compose/runtime/Composable;"
}
