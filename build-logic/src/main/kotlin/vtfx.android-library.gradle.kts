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

// Conventions for the Android library modules.

plugins {
    id("com.android.library")
    id("vtfx.dokka")
}

android {
    compileSdk = 37

    defaultConfig {
        // See docs/REQUIREMENTS.md section 6.4 and docs/decisions/0039-minsdk-29.md.
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        allWarningsAsErrors.set(true)
        // Results must be used, as the CheckReturnValue checks of other build systems require
        // (docs/decisions/0088). Only a check: the sources compile without it.
        freeCompilerArgs.add("-Xreturn-value-checker=full")
    }
}

// The public API's ABI is dumped to api/ and checked by `check` (requirement 6.5,
// docs/decisions/0087). After an intended change, run the updateKotlinAbi task and review the diff.
// Declarations marked with InternalVtfxApi are not public API. KGP's ABI validation, which the JVM
// libraries use, finds no Kotlin compilation here with AGP's built-in Kotlin, so these tasks dump
// the release classes with the Kotlin Build Tools API, in the same format.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

val abiTools =
    configurations.create("kotlinAbiTools") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

dependencies {
    // The implementation of the Build Tools API that KGP also uses, at the Kotlin version.
    abiTools("org.jetbrains.kotlin:kotlin-build-tools-impl:${libs.findVersion("kotlin").get()}")
}

val dumpAbi =
    tasks.register<DumpAbiTask>("dumpKotlinAbi") {
        classes.from(
            tasks
                .named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileReleaseKotlin")
                .flatMap { it.destinationDirectory }
        )
        toolsClasspath.from(abiTools)
        excludedAnnotations.add("com.google.vtfx.core.InternalVtfxApi")
        dump.set(layout.buildDirectory.file("kotlin/abi/${project.name}.api"))
    }

tasks.register<Copy>("updateKotlinAbi") {
    description = "Writes the public ABI to the reference dump in api/."
    from(dumpAbi.flatMap { it.dump })
    into(layout.projectDirectory.dir("api"))
}

val checkAbi =
    tasks.register<CheckAbiTask>("checkKotlinAbi") {
        description = "Checks that the public ABI matches the reference dump in api/."
        actual.set(dumpAbi.flatMap { it.dump })
        reference.from(layout.projectDirectory.file("api/${project.name}.api"))
    }

tasks.named("check") {
    dependsOn(checkAbi)
}

registerKotlinSignatures("compileReleaseKotlin")
