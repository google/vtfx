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

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * What a library's artifact (a jar or an AAR) brings into an app besides classes, which the checks
 * of classes do not see (docs/decisions/0088): permissions and components in the manifest, which
 * the app's manifest merges, native code, services for ServiceLoader, lint checks, which run in the
 * app's build, rules for R8, which apply to the app's classes, jars embedded in an AAR, and class
 * files that the checks of classes did not read.
 */
object LibraryArtifacts {
    /** The manifest elements that a library must not declare. */
    private val FORBIDDEN_ELEMENTS =
        setOf(
            "uses-permission",
            "uses-permission-sdk-23",
            "permission",
            "activity",
            "activity-alias",
            "service",
            "receiver",
            "provider",
        )

    /** The entries under which a jar holds rules that R8 takes from it into the app's build. */
    private val RULE_DIRECTORIES = listOf("META-INF/proguard/", "META-INF/com.android.tools/")

    /**
     * The violations in the artifact whose bytes are [artifact], named [name] in the messages.
     * [checkedClasses] holds the class files that the check of classes read, by their paths: every
     * class file that the artifact ships, in it or in an AAR's `classes.jar`, must be one of them,
     * byte for byte, so that a class file that came in otherwise, such as from the resources, is
     * not shipped unchecked (docs/decisions/0088).
     */
    fun violations(
        name: String,
        artifact: ByteArray,
        checkedClasses: Map<String, ByteArray>,
    ): List<String> = entryViolations(artifact, "", checkedClasses).map { "$name: $it" }

    /** The violations in the zip [bytes], whose entries are named with [prefix] in the messages. */
    private fun entryViolations(
        bytes: ByteArray,
        prefix: String,
        checkedClasses: Map<String, ByteArray>,
    ): List<String> {
        val violations = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name
                val shown = "$prefix$path"
                when {
                    entry.isDirectory -> Unit
                    path.endsWith(".class") ->
                        if (!zip.readBytes().contentEquals(checkedClasses[path])) {
                            violations += "$shown is not a class that the check of classes read"
                        }
                    path.startsWith("jni/") || path.endsWith(".so") ->
                        violations += "$shown is native code"
                    path.startsWith("META-INF/services/") ->
                        violations += "$shown registers a service for ServiceLoader"
                    RULE_DIRECTORIES.any { path.startsWith(it) } || path == "proguard.txt" ->
                        violations += "$shown holds rules that R8 applies in the app's build"
                    path == "lint.jar" -> violations += "$shown runs lint checks in the app's build"
                    // An AAR's own classes are in classes.jar; any other jar is not checked.
                    path == "classes.jar" ->
                        violations += entryViolations(zip.readBytes(), "$shown!/", checkedClasses)
                    path.endsWith(".jar") ->
                        violations += "$shown is a jar whose classes are not checked"
                    path == "AndroidManifest.xml" ->
                        manifestViolations(zip.readBytes()).mapTo(violations) { "$prefix$it" }
                }
            }
        }
        return violations
    }

    private fun manifestViolations(manifest: ByteArray): List<String> {
        val factory = DocumentBuilderFactory.newInstance()
        // The manifest comes from the build, but a parser that fetches nothing is no harder to use.
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(manifest))
        val elements = document.getElementsByTagName("*")
        return (0 until elements.length)
            .map { elements.item(it) as Element }
            .filter { it.tagName in FORBIDDEN_ELEMENTS }
            .map {
                "AndroidManifest.xml declares <${it.tagName}>, which the app's manifest would merge"
            }
    }
}
