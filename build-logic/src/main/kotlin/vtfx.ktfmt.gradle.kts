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

// Formatting of the Kotlin sources with ktfmt, in the style of the Kotlin coding conventions
// (docs/decisions/0088). The formatKotlin task formats the sources in place, and checkKotlinFormat,
// which check runs, fails if any of them is not formatted.
//
// Applied to the root project, so that one run of ktfmt covers the build scripts, build-logic, and
// the sources of every module. The separate build in tools/ime-probe and the reproduction tests
// kept in docs/reviews are not covered.

plugins {
    base
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

val ktfmt =
    configurations.create("ktfmt") {
        isCanBeConsumed = false
        isCanBeResolved = true
        // The attributes of a Java runtime classpath, which this project has no plugin to declare.
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                objects.named(LibraryElements.JAR),
            )
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                objects.named(TargetJvmEnvironment.STANDARD_JVM),
            )
            // ktfmt is also published as a jar with its dependencies inside. The plain jar keeps
            // each
            // dependency visible and resolved like any other.
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        }
    }

dependencies {
    ktfmt(libs.findLibrary("ktfmt").get())
}

// Generated sources are not formatted, because ktfmt breaks their tables of value pairs into one
// value per line. Their generators check them instead (docs/decisions/0088).
val generatedSources =
    files(
        "vtfx-core/src/main/kotlin/com/google/vtfx/core/UnicodeWidthTables.kt",
        "vtfx-gate/src/main/kotlin/com/google/vtfx/gate/UnicodeDefaultIgnorables.kt",
    )

val kotlinSources =
    files(
            "build.gradle.kts",
            "settings.gradle.kts",
            "build-logic/build.gradle.kts",
            "build-logic/settings.gradle.kts",
            fileTree("build-logic/src") { include("**/*.kt", "**/*.kts") },
            subprojects.map {
                files(it.file("build.gradle.kts"), fileTree(it.file("src")) { include("**/*.kt") })
            },
        )
        .minus(generatedSources)

tasks.register<FormatKotlinTask>("formatKotlin") {
    description = "Formats the Kotlin sources with ktfmt."
    group = "formatting"
    sources.from(kotlinSources)
    ktfmtClasspath.from(ktfmt)
}

val checkKotlinFormat =
    tasks.register<CheckKotlinFormatTask>("checkKotlinFormat") {
        description = "Checks that the Kotlin sources are formatted with ktfmt."
        group = "verification"
        sources.from(kotlinSources)
        ktfmtClasspath.from(ktfmt)
        marker.set(layout.buildDirectory.file("ktfmt/checked"))
    }

tasks.named("check") {
    dependsOn(checkKotlinFormat)
}
