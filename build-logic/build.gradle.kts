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

plugins {
    `kotlin-dsl`
}

// All Gradle plugins are put on the classpath here, so that they share one class loader. If the
// Kotlin plugin were loaded by another class loader (e.g. via a plugin declared in the root
// project), it could not see AGP and would fall back to the legacy AGP API, which AGP 9 removed.
dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    implementation(libs.roborazzi.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)
    // Read the Kotlin signatures of the public API from the compiled classes (AbiDump.kt).
    implementation(libs.kotlin.metadata.jvm)
    implementation(libs.asm)
    // Reads config/boundaries.toml (Boundaries.kt).
    implementation(libs.jackson.dataformat.toml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test", libs.versions.kotlin.get()))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
