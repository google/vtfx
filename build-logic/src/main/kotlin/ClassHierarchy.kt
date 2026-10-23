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
    private class ClassInfo(val supertypes: List<String>, val members: Set<String>)

    private val infos = HashMap<String, ClassInfo>()

    /** Whether [name] is [other], or a class or interface that extends or implements it. */
    fun isSubtype(name: String, other: String): Boolean = other in supertypes(name)

    /**
     * The class that declares the field or method [name] with [descriptor] that a use through
     * [owner] reaches: [owner] itself or the nearest of its supertypes, superclasses before
     * interfaces. Fails if none declares it.
     */
    fun declaringClass(owner: String, name: String, descriptor: String): String {
        val member = name + descriptor
        return supertypes(owner).firstOrNull { member in info(it).members }
            ?: error("no supertype of $owner, nor $owner itself, declares $member")
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
            for (supertype in info(current).supertypes) {
                if (visited.add(supertype)) pending.addLast(supertype)
            }
        }
    }

    private fun info(name: String): ClassInfo =
        infos.getOrPut(name) {
            val bytes = checkNotNull(find(name)) { "class $name is not on the class path" }
            val members = mutableSetOf<String>()
            var supertypes = emptyList<String>()
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
                            supertypes = listOfNotNull(superName) + interfaces.orEmpty()
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
                            return null
                        }
                    },
                    ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
                )
            ClassInfo(supertypes, members)
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
