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

// The check of the module boundaries declared in config/boundaries.toml (docs/decisions/0088). The
// root project applies it, and it adds the check to every other project, so that a module cannot
// leave itself out by not applying a convention plugin. The root project itself is not checked: it
// only aggregates the API reference, and ships nothing.

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.artifacts.ArtifactView
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    base
}

val androidPlugins = listOf("com.android.library", "com.android.application")
val artifactType = Attribute.of("artifactType", String::class.java)

val boundariesFile = layout.projectDirectory.file("config/boundaries.toml")

// Collects the checks that pass, so that checkBoundaries fails if one did not run.
gradle.sharedServices.registerIfAbsent(BOUNDARY_CHECKS_SERVICE, BoundaryChecks::class) {}

val checkBoundaries =
    tasks.register<CheckModulesTask>("checkBoundaries") {
        description = "Checks the module boundaries declared in config/boundaries.toml."
        boundaries.set(boundariesFile)
        projectPaths.set(provider { subprojects.map { it.path } })
    }

tasks.named("check") { dependsOn(checkBoundaries) }

subprojects {
    val projectPath = path

    /** Makes [check] a check that checkBoundaries runs and requires to pass. */
    fun requireCheck(check: TaskProvider<out BoundaryCheckTask>) {
        checkBoundaries.configure {
            dependsOn(check)
            requiredChecks.add("$projectPath:${check.name}")
        }
    }

    val checkDependencies =
        tasks.register<CheckDependenciesTask>("checkDependencies") {
            description = "Checks the dependencies of the module against config/boundaries.toml."
            boundaries.set(boundariesFile)
        }
    requireCheck(checkDependencies)

    // What the main classes refer to. Test classes are not checked: they are not shipped.
    val moduleName = name
    pluginManager.withPlugin("java") {
        val main = extensions.getByType<SourceSetContainer>().named("main")
        val checkClasses =
            tasks.register<CheckClassReferencesTask>("checkClassReferences") {
                description =
                    "Checks what the main classes refer to against config/boundaries.toml."
                boundaries.set(boundariesFile)
                module.set(moduleName)
                classDirectories.from(main.map { it.output.classesDirs })
                classpath.from(main.map { it.compileClasspath })
                projectClasspath.from(
                    configurations.named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME).flatMap {
                        projectFiles(it.incoming.artifactView {})
                    }
                )
            }
        requireCheck(checkClasses)
        val checkArtifact =
            tasks.register<CheckLibraryArtifactTask>("checkLibraryArtifact") {
                description = "Checks what the jar brings into apps besides classes."
                artifact.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
                classDirectories.from(main.map { it.output.classesDirs })
            }
        requireCheck(checkArtifact)
    }
    pluginManager.withPlugin("com.android.library") {
        // The AAR that is published; the debug one is built from the same sources.
        val components =
            extensions.getByName("androidComponents") as AndroidComponentsExtension<*, *, *>
        components.onVariants(components.selector().withBuildType("release")) { variant ->
            val checkArtifact =
                tasks.register<CheckLibraryArtifactTask>("checkLibraryArtifact") {
                    description = "Checks what the AAR brings into apps besides classes."
                    artifact.set(variant.artifacts.get(SingleArtifact.AAR))
                }
            variant.artifacts
                .forScope(ScopedArtifacts.Scope.PROJECT)
                .use(checkArtifact)
                .toGet(
                    ScopedArtifact.CLASSES,
                    CheckLibraryArtifactTask::variantJars,
                    CheckLibraryArtifactTask::variantDirectories,
                )
            requireCheck(checkArtifact)
        }
    }
    for (android in androidPlugins) {
        pluginManager.withPlugin(android) {
            val components =
                extensions.getByName("androidComponents") as AndroidComponentsExtension<*, *, *>
            components.onVariants { variant ->
                val checkClasses =
                    tasks.register<CheckClassReferencesTask>(
                        "checkClassReferences${variant.name.replaceFirstChar { it.uppercase() }}"
                    ) {
                        description =
                            "Checks what the classes of the ${variant.name} variant refer to " +
                                "against config/boundaries.toml."
                        boundaries.set(boundariesFile)
                        module.set(moduleName)
                        classpath.from(
                            variant.compileClasspath,
                            components.sdkComponents.bootClasspath,
                        )
                        projectClasspath.from(
                            projectFiles(
                                variant.compileConfiguration.incoming.artifactView {
                                    // What AGP makes of the classes of a library for compiling.
                                    attributes.attribute(artifactType, "android-classes-jar")
                                }
                            )
                        )
                    }
                variant.artifacts
                    .forScope(ScopedArtifacts.Scope.PROJECT)
                    .use(checkClasses)
                    .toGet(
                        ScopedArtifact.CLASSES,
                        CheckClassReferencesTask::variantJars,
                        CheckClassReferencesTask::variantDirectories,
                    )
                requireCheck(checkClasses)
            }
        }
    }
}

// Once every project is evaluated, the plugins have created all their configurations (AGP creates
// those of the variants after the build file is evaluated).
gradle.projectsEvaluated {
    subprojects {
        val module = this
        if ((androidPlugins + "java").none { module.pluginManager.hasPlugin(it) }) {
            throw GradleException(
                "Project ${module.path} applies neither the java plugin nor an Android plugin, so " +
                    "the boundary check cannot read its classes (docs/decisions/0088)."
            )
        }
        tasks.named<CheckDependenciesTask>("checkDependencies") {
            edges.addAll(module.declaredDependencyEdges())
            // The resolved graphs show what the declarations do not: dependencies added when a
            // configuration is resolved (withDependencies, defaultDependencies) and substitutions.
            for (configuration in module.configurations.filter { it.isCanBeResolved }) {
                edges.addAll(
                    configuration.incoming.resolutionResult.rootComponent.map {
                        resolvedDependencyEdges(configuration.name, it)
                    }
                )
            }
        }
    }
}

/** The files of [view] that come from the projects of the build. */
fun projectFiles(view: ArtifactView): Provider<List<File>> =
    view.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts.filter { it.id.componentIdentifier is ProjectComponentIdentifier }.map { it.file }
    }
