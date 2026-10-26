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

// Conventions for Android modules, libraries and the sample app, with unit tests on Robolectric.

import com.android.build.api.dsl.CommonExtension

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<CommonExtension> {
    // Robolectric needs the merged resources and manifest.
    testOptions.unitTests.isIncludeAndroidResources = true
}

// The Android jars that Robolectric runs the tests on, one for each SDK that the tests use. Gradle
// resolves them, so that dependency verification covers them, and Robolectric reads them offline
// instead of downloading them with its own downloader into ~/.m2 (docs/decisions/0088). A test on
// an SDK without a jar here fails, naming the missing file. Each jar has a configuration of its
// own: they are versions of one module, of which one configuration would resolve only the newest.
val robolectricAndroidJars =
    listOf(29, 36).map { sdk ->
        configurations.create("robolectricAndroidSdk$sdk") {
            isCanBeConsumed = false
            isCanBeResolved = true
            // The jars have no dependencies.
            isTransitive = false
            dependencies.addLater(libs.findLibrary("robolectric-android-sdk$sdk").get())
        }
    }

// Robolectric's offline mode looks up each jar by its file name in one directory, while Gradle's
// cache keeps each artifact in a directory of its own.
val robolectricAndroidJarsDirectory = layout.buildDirectory.dir("robolectric/android-all")
val syncRobolectricAndroidJars =
    tasks.register<Sync>("syncRobolectricAndroidJars") {
        from(robolectricAndroidJars)
        into(robolectricAndroidJarsDirectory)
    }

tasks.withType<Test>().configureEach {
    // Robolectric sets up file descriptors through JDK internals.
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    // The jars are declared by name only, and the directory is passed as an argument that is not
    // an input, to keep the build cache relocatable.
    inputs
        .files(syncRobolectricAndroidJars)
        .withPathSensitivity(PathSensitivity.NAME_ONLY)
        .withPropertyName("robolectricAndroidJars")
    val directory = robolectricAndroidJarsDirectory
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Drobolectric.offline=true",
                "-Drobolectric.dependency.dir=${directory.get().asFile}",
            )
        }
    )
}

dependencies {
    "testImplementation"(libs.findLibrary("junit4").get())
    "testImplementation"(libs.findLibrary("robolectric").get())
}
