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
    // docs/decisions/0016). After an intended change, run the updateKotlinAbi task and review the
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
    }
}

registerKotlinSignatures("compileKotlin")

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(kotlin("test"))
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
    // The tests are moving to JUnit 4 (docs/decisions/0048). Until all of them have moved, the
    // vintage engine runs the JUnit 4 tests on the JUnit Platform beside the Jupiter tests.
    "testImplementation"(libs.findLibrary("junit4").get())
    "testImplementation"(libs.findLibrary("testparameterinjector").get())
    "testRuntimeOnly"(libs.findLibrary("junit-vintage-engine").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
