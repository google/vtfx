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

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class LibraryArtifactsTest {
    @Test
    fun violations_aarWithClassesAndPlainManifest_none() {
        val aar =
            zip(
                "AndroidManifest.xml" to MANIFEST.format(""),
                "classes.jar" to "",
                "R.txt" to "",
            )

        assertEquals(emptyList(), LibraryArtifacts.violations("library.aar", aar))
    }

    @Test
    fun violations_permission_reportsIt() {
        val aar =
            zip(
                "AndroidManifest.xml" to
                    MANIFEST.format(
                        """<uses-permission android:name="android.permission.INTERNET" />"""
                    )
            )

        val violations = LibraryArtifacts.violations("library.aar", aar)

        assertEquals(
            listOf(
                "library.aar: AndroidManifest.xml declares <uses-permission>, which the app's manifest " +
                    "would merge"
            ),
            violations,
        )
    }

    @Test
    fun violations_activity_reportsIt() {
        val aar =
            zip(
                "AndroidManifest.xml" to
                    MANIFEST.format(
                        """<application><activity android:name=".Leak" /></application>"""
                    )
            )

        assertEquals(1, LibraryArtifacts.violations("library.aar", aar).size)
    }

    @Test
    fun violations_service_reportsIt() {
        val aar =
            zip(
                "AndroidManifest.xml" to
                    MANIFEST.format(
                        """<application><service android:name=".Leak" /></application>"""
                    )
            )

        assertEquals(1, LibraryArtifacts.violations("library.aar", aar).size)
    }

    @Test
    fun violations_receiver_reportsIt() {
        val aar =
            zip(
                "AndroidManifest.xml" to
                    MANIFEST.format(
                        """<application><receiver android:name=".Leak" /></application>"""
                    )
            )

        assertEquals(1, LibraryArtifacts.violations("library.aar", aar).size)
    }

    @Test
    fun violations_provider_reportsIt() {
        val aar =
            zip(
                "AndroidManifest.xml" to
                    MANIFEST.format(
                        """<application><provider android:name=".Leak" android:authorities="a" /></application>"""
                    )
            )

        assertEquals(1, LibraryArtifacts.violations("library.aar", aar).size)
    }

    @Test
    fun violations_nativeLibrary_reportsIt() {
        val aar = zip("jni/arm64-v8a/libleak.so" to "")

        assertEquals(
            listOf("library.aar: jni/arm64-v8a/libleak.so is native code"),
            LibraryArtifacts.violations("library.aar", aar),
        )
    }

    @Test
    fun violations_serviceLoaderFile_reportsIt() {
        val jar = zip("META-INF/services/java.nio.file.spi.FileSystemProvider" to "Leak")

        assertEquals(
            listOf(
                "library.jar: META-INF/services/java.nio.file.spi.FileSystemProvider registers a " +
                    "service for ServiceLoader"
            ),
            LibraryArtifacts.violations("library.jar", jar),
        )
    }

    @Test
    fun violations_emptyDirectoryOfServices_none() {
        val jar = zip("META-INF/services/" to "")

        assertEquals(emptyList(), LibraryArtifacts.violations("library.jar", jar))
    }

    @Test
    fun violations_lintChecks_reportsIt() {
        val aar = zip("lint.jar" to "")

        assertEquals(1, LibraryArtifacts.violations("library.aar", aar).size)
    }

    @Test
    fun violations_embeddedJar_reportsIt() {
        val aar = zip("libs/leak.jar" to "")

        assertEquals(
            listOf("library.aar: libs/leak.jar is a jar whose classes are not checked"),
            LibraryArtifacts.violations("library.aar", aar),
        )
    }

    private companion object {
        const val MANIFEST =
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="a.b">%s</manifest>"""

        fun zip(vararg entries: Pair<String, String>): ByteArray {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                for ((name, content) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            return bytes.toByteArray()
        }
    }
}
