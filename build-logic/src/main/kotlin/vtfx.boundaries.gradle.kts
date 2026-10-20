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

// The check of the module boundaries declared in config/boundaries.toml (docs/decisions/0044). The
// root project applies it, and it adds the check to every other project, so that a module cannot
// leave itself out by not applying a convention plugin. The root project itself is not checked: it
// only aggregates the API reference, and ships nothing.

plugins {
    base
}

val boundariesFile = layout.projectDirectory.file("config/boundaries.toml")

val checkBoundaries =
    tasks.register<CheckModulesTask>("checkBoundaries") {
        description = "Checks the module boundaries declared in config/boundaries.toml."
        boundaries.set(boundariesFile)
        projectPaths.set(provider { subprojects.map { it.path } })
    }

tasks.named("check") { dependsOn(checkBoundaries) }

subprojects {
    val checkDependencies =
        tasks.register<CheckDependenciesTask>("checkDependencies") {
            description = "Checks the dependencies of the module against config/boundaries.toml."
            boundaries.set(boundariesFile)
        }
    checkBoundaries.configure { dependsOn(checkDependencies) }
}

// Once every project is evaluated, the plugins have created all their configurations (AGP creates
// those of the variants after the build file is evaluated).
gradle.projectsEvaluated {
    subprojects {
        val module = this
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
