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

/**
 * The supertypes of the classes that [find] reads the class files of, by internal name
 * (docs/decisions/0044). Fails on a class that [find] does not find, instead of guessing that it
 * has no supertypes.
 */
class ClassHierarchy(private val find: (String) -> ByteArray?) {
    private val supertypes = HashMap<String, List<String>>()

    /** Whether [name] is [other], or a class or interface that extends or implements it. */
    fun isSubtype(name: String, other: String): Boolean {
        val visited = mutableSetOf(name)
        val pending = ArrayDeque(listOf(name))
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current == other) return true
            for (supertype in directSupertypes(current)) {
                if (visited.add(supertype)) pending.addLast(supertype)
            }
        }
        return false
    }

    private fun directSupertypes(name: String): List<String> =
        supertypes.getOrPut(name) {
            val bytes = checkNotNull(find(name)) { "class $name is not on the class path" }
            val reader = ClassReader(bytes)
            listOfNotNull(reader.superName) + reader.interfaces
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
