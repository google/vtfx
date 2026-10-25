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

// Conventions for the pure Kotlin/JVM modules, which must not depend on Android.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("vtfx.dokka")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    // The public API's ABI is dumped to api/ and checked by `check` (requirement 6.5,
    // docs/decisions/0087). After an intended change, run the updateKotlinAbi task and review the
    // diff. Declarations marked with InternalVtfxApi are not public API.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        filters {
            exclude {
                annotatedWith.add("com.google.vtfx.core.InternalVtfxApi")
            }
        }
    }
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
        // Results must be used, as the CheckReturnValue checks of other build systems require
        // (docs/decisions/0072). Only a check: the sources compile without it.
        freeCompilerArgs.add("-Xreturn-value-checker=full")
    }
}

registerKotlinSignatures("compileKotlin")

dependencies {
    // The tests run on JUnit 4, which builds of the sources with other build systems run too
    // (docs/decisions/0048).
    "testImplementation"(libs.findLibrary("junit4").get())
    "testImplementation"(libs.findLibrary("testparameterinjector").get())
    "testImplementation"(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
