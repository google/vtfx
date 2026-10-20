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
 * of classes do not see (docs/decisions/0044): permissions and components in the manifest, which
 * the app's manifest merges, native code, services for ServiceLoader, lint checks, which run in the
 * app's build, and jars embedded in an AAR.
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

    /** The violations in the artifact whose bytes are [artifact], named [name] in the messages. */
    fun violations(name: String, artifact: ByteArray): List<String> {
        val violations = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(artifact)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name
                when {
                    entry.isDirectory -> Unit
                    path.startsWith("jni/") || path.endsWith(".so") ->
                        violations += "$name: $path is native code"
                    path.startsWith("META-INF/services/") ->
                        violations += "$name: $path registers a service for ServiceLoader"
                    path == "lint.jar" ->
                        violations += "$name: $path runs lint checks in the app's build"
                    // An AAR's own classes are in classes.jar; any other jar is not checked.
                    path.endsWith(".jar") && path != "classes.jar" ->
                        violations += "$name: $path is a jar whose classes are not checked"
                    path == "AndroidManifest.xml" ->
                        manifestViolations(zip.readBytes()).mapTo(violations) { "$name: $it" }
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
