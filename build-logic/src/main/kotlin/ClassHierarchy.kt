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

import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * The supertypes of the classes that [find] reads the class files of, by internal name
 * (docs/decisions/0044). Fails on a class that [find] does not find, instead of guessing that it
 * has no supertypes.
 */
class ClassHierarchy(private val find: (String) -> ByteArray?) {
    /**
     * @property superclass null for `Object` only; `Object` for an interface.
     * @property members the fields and methods declared, as name and descriptor.
     * @property instanceMethods the methods declared that a class may implement or inherit: neither
     *   static nor private, nor constructors.
     */
    private class ClassInfo(
        val isInterface: Boolean,
        val superclass: String?,
        val interfaces: List<String>,
        val members: Set<String>,
        val instanceMethods: List<Pair<String, String>>,
    )

    private val infos = HashMap<String, ClassInfo>()

    /** Whether [name] is [other], or a class or interface that extends or implements it. */
    fun isSubtype(name: String, other: String): Boolean = other in supertypes(name)

    /**
     * The class that declares the field or method [name] with [descriptor] that a use through
     * [owner] reaches, as the JVM resolves it (JVMS 5.4.3.2, 5.4.3.3, 5.4.3.4): for a method,
     * [owner] itself or the nearest of its superclasses, and then the nearest of its
     * superinterfaces; for a field, [owner] itself, then its superinterfaces, then its superclass,
     * looked up in the same way. Fails if none declares it.
     */
    fun declaringClass(owner: String, name: String, descriptor: String): String {
        val member = name + descriptor
        val candidates =
            if (descriptor.startsWith("(")) methodLookup(owner)
            else fieldLookup(owner, mutableSetOf())
        return candidates.firstOrNull { member in info(it).members }
            ?: error("no supertype of $owner, nor $owner itself, declares $member")
    }

    /**
     * The methods that the class [name] inherits from its superclasses to implement the methods of
     * the interfaces that it implements and its superclass does not, each as a use through the
     * superclass that declares it. A call through such an interface reaches the method, though no
     * class names it there (docs/design/boundaries.md section 4.3). None for an interface or
     * `Object`.
     */
    fun inheritedImplementations(name: String): List<MemberReference> {
        val info = info(name)
        if (info.isInterface) return emptyList()
        val superclass = info.superclass ?: return emptyList()
        val inherited = supertypes(superclass).toSet()
        return supertypes(name)
            .filter { it != name && it !in inherited }
            .flatMap { info(it).instanceMethods }
            .distinct()
            .filter { (method, descriptor) -> method + descriptor !in info.members }
            .mapNotNull { (method, descriptor) ->
                superclasses(superclass)
                    .firstOrNull { method + descriptor in info(it).members }
                    ?.let { MemberReference(it, method, descriptor, MemberReference.Kind.METHOD) }
            }
            .toList()
    }

    /**
     * [start] and then its supertypes, breadth first, superclasses before interfaces. Reads the
     * supertypes of a type only after yielding it, so a caller that stops there does not need its
     * class file.
     */
    private fun supertypes(start: String): Sequence<String> = sequence {
        val visited = mutableSetOf(start)
        val pending = ArrayDeque(listOf(start))
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            yield(current)
            val info = info(current)
            for (supertype in listOfNotNull(info.superclass) + info.interfaces) {
                if (visited.add(supertype)) pending.addLast(supertype)
            }
        }
    }

    /** [start] and then its superclasses, nearest first. */
    private fun superclasses(start: String): Sequence<String> =
        generateSequence(start) { info(it).superclass }

    /**
     * The types in which a method is looked up: [start] and its superclasses, nearest first, and
     * then the interfaces of all of them with their superinterfaces, breadth first.
     */
    private fun methodLookup(start: String): Sequence<String> = sequence {
        val classes = mutableListOf<String>()
        for (current in superclasses(start)) {
            yield(current)
            classes += current
        }
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<String>()
        for (current in classes) {
            for (supertype in info(current).interfaces) {
                if (visited.add(supertype)) pending.addLast(supertype)
            }
        }
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            yield(current)
            for (supertype in info(current).interfaces) {
                if (visited.add(supertype)) pending.addLast(supertype)
            }
        }
    }

    /**
     * The types in which a field is looked up: [start], then each of its interfaces in turn, looked
     * up in the same way, then its superclass. Skips the types in [visited].
     */
    private fun fieldLookup(start: String, visited: MutableSet<String>): Sequence<String> =
        sequence {
            if (!visited.add(start)) return@sequence
            yield(start)
            val info = info(start)
            for (supertype in info.interfaces) yieldAll(fieldLookup(supertype, visited))
            info.superclass?.let { yieldAll(fieldLookup(it, visited)) }
        }

    private fun info(name: String): ClassInfo =
        infos.getOrPut(name) {
            val bytes = checkNotNull(find(name)) { "class $name is not on the class path" }
            val members = mutableSetOf<String>()
            val instanceMethods = mutableListOf<Pair<String, String>>()
            var isInterface = false
            var superclass: String? = null
            var directInterfaces = emptyList<String>()
            ClassReader(bytes)
                .accept(
                    object : ClassVisitor(Opcodes.ASM9) {
                        override fun visit(
                            version: Int,
                            access: Int,
                            name: String,
                            signature: String?,
                            superName: String?,
                            interfaces: Array<out String>?,
                        ) {
                            isInterface = access and Opcodes.ACC_INTERFACE != 0
                            superclass = superName
                            directInterfaces = interfaces.orEmpty().toList()
                        }

                        override fun visitField(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            value: Any?,
                        ): FieldVisitor? {
                            members += name + descriptor
                            return null
                        }

                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor? {
                            members += name + descriptor
                            if (
                                access and (Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE) == 0 &&
                                    !name.startsWith("<")
                            ) {
                                instanceMethods += name to descriptor
                            }
                            return null
                        }
                    },
                    ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
                )
            ClassInfo(isInterface, superclass, directInterfaces, members, instanceMethods)
        }
}

/**
 * The class files in [files], directories and jars, in order, followed by those of the platform
 * that runs the build. Close it to close the jars.
 */
class ClassPath(files: List<File>) : Closeable {
    private val entries = files.filter { it.exists() }
    private val jars = HashMap<File, ZipFile>()

    /** The class file of the class with the internal name [name], or null if there is none. */
    fun find(name: String): ByteArray? {
        val path = "$name.class"
        for (entry in entries) {
            if (entry.isDirectory) {
                val file = entry.resolve(path)
                if (file.isFile) return file.readBytes()
            } else {
                val jar = jars.getOrPut(entry) { ZipFile(entry) }
                val zipEntry = jar.getEntry(path)
                if (zipEntry != null) return jar.getInputStream(zipEntry).use { it.readBytes() }
            }
        }
        return ClassLoader.getPlatformClassLoader().getResourceAsStream(path)?.use {
            it.readBytes()
        }
    }

    override fun close() {
        jars.values.forEach(ZipFile::close)
    }
}
