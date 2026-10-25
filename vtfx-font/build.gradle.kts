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

// Reads fonts that users choose and writes them again in one canonical form (docs/decisions/0086).
// What reaches native code is decided by the verifier of vtfx-font-gate, so this module is not
// under
// security review (docs/decisions/0091).
plugins {
    id("vtfx.fuzz")
}

fuzzTarget { className.set("com.google.vtfx.font.FontFuzzer") }

dependencies {
    // The public API returns SanitizedFont and takes FontLimits.
    api(project(":vtfx-font-gate"))
}

// Real fonts under the SIL Open Font License, which ThirdPartyFontsTest sanitizes
// (docs/decisions/0086): one of each kind of font file, TrueType, CID-keyed CFF, and a collection.
// They are on no classpath, so that no artifact can contain them, and the test reads them as files.
// gradle/verification-metadata.xml pins their checksums.
val thirdPartyFonts: Configuration =
    configurations.create("thirdPartyFonts") {
        isCanBeConsumed = false
        isTransitive = false
    }

dependencies {
    thirdPartyFonts("googlefonts:morisawa-biz-ud-gothic:18934af56b9c003ca58c54bffbf226848cb11032") {
        artifact {
            name = "fonts/ttf/BIZUDGothic-Regular"
            extension = "ttf"
        }
    }
    thirdPartyFonts("notofonts:noto-cjk:523d033d6cb47f4a80c58a35753646f5c3608a78") {
        artifact {
            name = "Sans/SubsetOTF/JP/NotoSansJP-Regular"
            extension = "otf"
        }
        artifact {
            name = "Sans/OTC/NotoSansCJK-Regular"
            extension = "ttc"
        }
    }
}

// CanonicalFixturesTest checks that this module writes the canonical fonts that vtfx-font-gate's
// tests check the verifier with. It reads them as files: those tests cannot depend on this module
// (config/boundaries.toml), and the files are inputs, so that changing one runs the test again.
tasks.named<Test>("test") {
    val fixtures =
        layout.settingsDirectory.dir(
            "vtfx-font-gate/src/fuzz/resources/com/google/vtfx/font/gate/canonical"
        )
    inputs
        .dir(fixtures)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("canonicalFixtures")
    systemProperty("vtfx.canonicalFixtures", fixtures.asFile.absolutePath)
    systemProperty(
        "vtfx.canonicalFixturesActual",
        layout.buildDirectory.dir("canonical-fixtures").get().asFile.absolutePath,
    )
    val fonts: FileCollection = thirdPartyFonts
    inputs
        .files(fonts)
        .withPathSensitivity(PathSensitivity.NAME_ONLY)
        .withPropertyName("thirdPartyFonts")
    // The directories of the files, each named by its checksum, are known only once they are
    // downloaded, so the test is given the path of each font when it runs, by the name of the file
    // without the commit that Gradle adds to it.
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            fonts.files.map {
                val name = it.name.replace(Regex("-[0-9a-f]{40}(?=\\.)"), "")
                "-Dvtfx.thirdPartyFont.$name=${it.absolutePath}"
            }
        }
    )
}
