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

        assertEquals(emptyList(), LibraryArtifacts.violations("library.aar", aar, NO_CLASSES))
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

        val violations = LibraryArtifacts.violations("library.aar", aar, NO_CLASSES)

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

        assertEquals(1, LibraryArtifacts.violations("library.aar", aar, NO_CLASSES).size)
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

        assertEquals(1, LibraryArtifacts.violations("library.aar", aar, NO_CLASSES).size)
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

        assertEquals(1, LibraryArtifacts.violations("library.aar", aar, NO_CLASSES).size)
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

        assertEquals(1, LibraryArtifacts.violations("library.aar", aar, NO_CLASSES).size)
    }

    @Test
    fun violations_nativeLibrary_reportsIt() {
        val aar = zip("jni/arm64-v8a/libleak.so" to "")

        assertEquals(
            listOf("library.aar: jni/arm64-v8a/libleak.so is native code"),
            LibraryArtifacts.violations("library.aar", aar, NO_CLASSES),
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
            LibraryArtifacts.violations("library.jar", jar, NO_CLASSES),
        )
    }

    @Test
    fun violations_emptyDirectoryOfServices_none() {
        val jar = zip("META-INF/services/" to "")

        assertEquals(emptyList(), LibraryArtifacts.violations("library.jar", jar, NO_CLASSES))
    }

    @Test
    fun violations_lintChecks_reportsIt() {
        val aar = zip("lint.jar" to "")

        assertEquals(1, LibraryArtifacts.violations("library.aar", aar, NO_CLASSES).size)
    }

    @Test
    fun violations_embeddedJar_reportsIt() {
        val aar = zip("libs/leak.jar" to "")

        assertEquals(
            listOf("library.aar: libs/leak.jar is a jar whose classes are not checked"),
            LibraryArtifacts.violations("library.aar", aar, NO_CLASSES),
        )
    }

    @Test
    fun violations_classThatWasChecked_none() {
        val jar = zip("a/B.class" to "checked")

        assertEquals(
            emptyList(),
            LibraryArtifacts.violations("library.jar", jar, classes("a/B.class" to "checked")),
        )
    }

    @Test
    fun violations_classThatWasNotChecked_reportsIt() {
        // A class file among the resources, which the jar takes in besides the compiled classes.
        val jar = zip("a/B.class" to "checked", "com/google/vtfx/gate/C.class" to "unchecked")

        assertEquals(
            listOf(
                "library.jar: com/google/vtfx/gate/C.class is not a class that the check of " +
                    "classes read"
            ),
            LibraryArtifacts.violations("library.jar", jar, classes("a/B.class" to "checked")),
        )
    }

    @Test
    fun violations_classDifferentFromTheCheckedOne_reportsIt() {
        val jar = zip("a/B.class" to "replaced")

        assertEquals(
            listOf("library.jar: a/B.class is not a class that the check of classes read"),
            LibraryArtifacts.violations("library.jar", jar, classes("a/B.class" to "checked")),
        )
    }

    @Test
    fun violations_secondEntryOfACheckedClass_reportsIt() {
        // ZipOutputStream refuses a second entry of the same name, so the entry is renamed after.
        val jar = zip("a/B.class" to "checked", "a/D.class" to "replaced").renamed("a/D", "a/B")

        assertEquals(
            listOf("library.jar: a/B.class is not a class that the check of classes read"),
            LibraryArtifacts.violations("library.jar", jar, classes("a/B.class" to "checked")),
        )
    }

    @Test
    fun violations_classOfAMultiReleaseJar_reportsIt() {
        val jar = zip("META-INF/versions/21/a/B.class" to "unchecked")

        assertEquals(
            listOf(
                "library.jar: META-INF/versions/21/a/B.class is not a class that the check of " +
                    "classes read"
            ),
            LibraryArtifacts.violations("library.jar", jar, NO_CLASSES),
        )
    }

    @Test
    fun violations_checkedClassInClassesJar_none() {
        val aar = zipOf("classes.jar" to zip("a/B.class" to "checked"))

        assertEquals(
            emptyList(),
            LibraryArtifacts.violations("library.aar", aar, classes("a/B.class" to "checked")),
        )
    }

    @Test
    fun violations_uncheckedClassInClassesJar_reportsIt() {
        val aar = zipOf("classes.jar" to zip("a/B.class" to "unchecked"))

        assertEquals(
            listOf(
                "library.aar: classes.jar!/a/B.class is not a class that the check of classes read"
            ),
            LibraryArtifacts.violations("library.aar", aar, NO_CLASSES),
        )
    }

    @Test
    fun violations_proguardRules_reportsIt() {
        val jar = zip("META-INF/proguard/leak.pro" to "-assumevalues class a.B { boolean c(); }")

        assertEquals(
            listOf(
                "library.jar: META-INF/proguard/leak.pro holds rules that R8 applies in the " +
                    "app's build"
            ),
            LibraryArtifacts.violations("library.jar", jar, NO_CLASSES),
        )
    }

    @Test
    fun violations_r8Rules_reportsIt() {
        val jar = zip("META-INF/com.android.tools/r8/leak.pro" to "")

        assertEquals(1, LibraryArtifacts.violations("library.jar", jar, NO_CLASSES).size)
    }

    @Test
    fun violations_consumerRulesOfAnAar_reportsIt() {
        val aar = zip("proguard.txt" to "")

        assertEquals(
            listOf("library.aar: proguard.txt holds rules that R8 applies in the app's build"),
            LibraryArtifacts.violations("library.aar", aar, NO_CLASSES),
        )
    }

    @Test
    fun violations_rulesInClassesJar_reportsIt() {
        val aar = zipOf("classes.jar" to zip("META-INF/proguard/leak.pro" to ""))

        assertEquals(
            listOf(
                "library.aar: classes.jar!/META-INF/proguard/leak.pro holds rules that R8 " +
                    "applies in the app's build"
            ),
            LibraryArtifacts.violations("library.aar", aar, NO_CLASSES),
        )
    }

    private companion object {
        const val MANIFEST =
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="a.b">%s</manifest>"""

        val NO_CLASSES: Map<String, ByteArray> = emptyMap()

        fun classes(vararg classes: Pair<String, String>): Map<String, ByteArray> =
            classes.associate { (path, content) ->
                path to content.toByteArray()
            }

        /** This zip with every occurrence of [from] in its bytes replaced by [to], as long. */
        fun ByteArray.renamed(from: String, to: String): ByteArray =
            String(this, Charsets.ISO_8859_1).replace(from, to).toByteArray(Charsets.ISO_8859_1)

        fun zip(vararg entries: Pair<String, String>): ByteArray =
            zipOf(*entries.map { (name, content) -> name to content.toByteArray() }.toTypedArray())

        fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                for ((name, content) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
            return bytes.toByteArray()
        }
    }
}
