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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class CheckedClassFilesTest {
    @TempDir lateinit var root: File

    @Test
    fun checkedClassFiles_classesOfDirectoriesAndJars_byPath() {
        val directory = directory("classes", "a/A.class" to classFile("a/A", "java/lang/Object"))
        val jar = jar("classes.jar", "a/B.class" to classFile("a/B", "java/lang/Object"))

        val classes = checkedClassFiles(listOf(directory), listOf(jar))

        assertEquals(setOf("a/A.class", "a/B.class"), classes.keys)
        assertContentEquals(classFile("a/B", "java/lang/Object"), classes["a/B.class"])
    }

    @Test
    fun checkedClassFiles_classesUnderMetaInf_leftOut() {
        // The check of classes does not read them, so the artifact must not ship them either.
        val directory =
            directory(
                "classes",
                "META-INF/versions/21/a/A.class" to classFile("a/A", "java/lang/Object"),
            )
        val jar =
            jar(
                "classes.jar",
                "META-INF/versions/21/a/B.class" to classFile("a/B", "java/lang/Object"),
            )

        assertEquals(emptyMap(), checkedClassFiles(listOf(directory), listOf(jar)))
    }

    @Test
    fun checkedClassFiles_samePathInTwoDirectories_fails() {
        val first = directory("first", "a/A.class" to classFile("a/A", "java/lang/Object"))
        val second = directory("second", "a/A.class" to classFile("a/A", "java/lang/Exception"))

        val failure =
            assertFailsWith<GradleException> {
                checkedClassFiles(listOf(first, second), emptyList())
            }

        assertContains(failure.message!!, "a/A.class is in more than one of [$first, $second]")
    }

    @Test
    fun checkedClassFiles_samePathInDirectoryAndJar_fails() {
        val directory = directory("classes", "a/A.class" to classFile("a/A", "java/lang/Object"))
        val jar = jar("classes.jar", "a/A.class" to classFile("a/A", "java/lang/Object"))

        val failure =
            assertFailsWith<GradleException> { checkedClassFiles(listOf(directory), listOf(jar)) }

        assertContains(failure.message!!, "a/A.class is in more than one of [$directory, $jar]")
    }

    @Test
    fun checkedClassFiles_classNotAtThePathOfItsName_fails() {
        val directory = directory("classes", "a/A.class" to classFile("a/B", "java/lang/Object"))
        val jar = jar("classes.jar", "a/C.class" to classFile("a/D", "java/lang/Object"))

        val failure =
            assertFailsWith<GradleException> { checkedClassFiles(listOf(directory), listOf(jar)) }

        assertContains(failure.message!!, "a/A.class in $directory holds the class a/B")
        assertContains(failure.message!!, "a/C.class in $jar holds the class a/D")
    }

    private fun directory(name: String, vararg files: Pair<String, ByteArray>): File {
        val directory = root.resolve(name)
        for ((path, bytes) in files) {
            directory.resolve(path).apply { parentFile.mkdirs() }.writeBytes(bytes)
        }
        return directory
    }

    private fun jar(name: String, vararg entries: Pair<String, ByteArray>): File {
        val jar = root.resolve(name)
        ZipOutputStream(jar.outputStream()).use { zip ->
            for ((path, bytes) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return jar
    }

    /** A class file of [name], extending [superName]. */
    private fun classFile(name: String, superName: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null)
        writer.visitEnd()
        return writer.toByteArray()
    }
}
