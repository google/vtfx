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

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.objectweb.asm.ClassReader

/** The name under which [BoundaryChecks] is registered. */
const val BOUNDARY_CHECKS_SERVICE = "vtfxBoundaryChecks"

/**
 * The paths of the check tasks that passed in this build. [CheckModulesTask] requires all of them,
 * so that a check that was disabled, skipped, or left out of the task graph fails the build instead
 * of passing unseen (docs/decisions/0061).
 */
abstract class BoundaryChecks : BuildService<BuildServiceParameters.None> {
    private val passed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun pass(taskPath: String) {
        passed += taskPath
    }

    fun passed(): Set<String> = passed.toSet()
}

/**
 * A check of the module boundaries, which reports to [BoundaryChecks] when it passes. It has no
 * outputs, so Gradle runs it in every build that asks for it rather than taking it as up to date.
 */
@DisableCachingByDefault(because = "A check with no outputs")
abstract class BoundaryCheckTask : DefaultTask() {
    @get:ServiceReference(BOUNDARY_CHECKS_SERVICE) abstract val checks: Property<BoundaryChecks>

    @TaskAction
    fun run() {
        check()
        checks.get().pass(path)
    }

    /** Fails if the check finds a violation. */
    protected abstract fun check()
}

/**
 * Fails if the projects of the build differ from the modules of `config/boundaries.toml`, or if one
 * of [requiredChecks] did not pass in this build.
 */
@DisableCachingByDefault(because = "A check with no outputs")
abstract class CheckModulesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val boundaries: RegularFileProperty

    /** The paths of all projects of the build but the root project. */
    @get:Input abstract val projectPaths: SetProperty<String>

    /** The paths of the check tasks of every project, which this task depends on. */
    @get:Input abstract val requiredChecks: SetProperty<String>

    @get:ServiceReference(BOUNDARY_CHECKS_SERVICE) abstract val checks: Property<BoundaryChecks>

    @TaskAction
    fun run() {
        val passed = checks.get().passed()
        val violations =
            Boundaries.parse(boundaries.get().asFile.readText())
                .moduleViolations(projectPaths.get()) +
                (requiredChecks.get() - passed).sorted().map {
                    "$it did not run, so what it checks was not checked"
                }
        failOn(violations)
    }
}

/**
 * Fails if a project depends on a module that `config/boundaries.toml` does not allow, in any of
 * its configurations.
 */
@DisableCachingByDefault(because = "A check with no outputs")
abstract class CheckDependenciesTask : BoundaryCheckTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val boundaries: RegularFileProperty

    /**
     * The declared and the resolved edges of the project ([declaredDependencyEdges],
     * [resolvedDependencyEdges]).
     */
    @get:Input abstract val edges: ListProperty<DependencyEdge>

    override fun check() {
        val violations =
            Boundaries.parse(boundaries.get().asFile.readText()).dependencyViolations(edges.get())
        failOn(violations)
    }
}

/**
 * Fails if the main classes of a module refer to what `config/boundaries.toml` does not allow the
 * module to ([Boundaries.classViolations]).
 */
@DisableCachingByDefault(because = "A check with no outputs")
abstract class CheckClassReferencesTask : BoundaryCheckTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val boundaries: RegularFileProperty

    /** The name of the module in `config/boundaries.toml`. */
    @get:Input abstract val module: Property<String>

    /** Directories of classes, for the JVM modules. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection

    /**
     * Directories of classes of a variant, for the Android modules (AGP's
     * `ScopedArtifact.CLASSES`).
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val variantDirectories: ListProperty<Directory>

    /** Jars of classes of a variant, for the Android modules (AGP's `ScopedArtifact.CLASSES`). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val variantJars: ListProperty<RegularFile>

    /** What the classes are compiled against, to find the supertypes of the classes they use. */
    @get:Classpath abstract val classpath: ConfigurableFileCollection

    /** The part of [classpath] that comes from the modules of the project. */
    @get:Classpath abstract val projectClasspath: ConfigurableFileCollection

    override fun check() {
        val directories = classDirectories.files + variantDirectories.get().map { it.asFile }
        val jars = variantJars.get().map { it.asFile }
        val classes =
            checkedClassFiles(directories.toList(), jars)
                .values
                .map { ClassReferences.read(it) }
                .sortedBy { it.name }
        val boundaries = Boundaries.parse(this.boundaries.get().asFile.readText())
        val projectClasses = projectClasspath.files.flatMap(::classNames).toSet()
        val violations =
            ClassPath(directories.toList() + jars + classpath.files).use { classPath ->
                val hierarchy = ClassHierarchy(classPath::find)
                boundaries.classViolations(module.get(), classes, hierarchy) +
                    boundaries.allowlistViolations(module.get(), classes, projectClasses, hierarchy)
            }
        failOn(violations)
    }
}

/** Fails if a library's artifact brings into apps what [LibraryArtifacts] does not allow. */
@DisableCachingByDefault(because = "A check with no outputs")
abstract class CheckLibraryArtifactTask : BoundaryCheckTask() {
    /** The jar of a JVM library, or the AAR of an Android library. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val artifact: RegularFileProperty

    /**
     * Directories of the classes that [CheckClassReferencesTask] reads, for the JVM modules: the
     * classes that the artifact may ship.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection

    /** Directories of the classes of the variant of the AAR, for the Android modules. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val variantDirectories: ListProperty<Directory>

    /** Jars of the classes of the variant of the AAR, for the Android modules. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val variantJars: ListProperty<RegularFile>

    override fun check() {
        val directories = classDirectories.files + variantDirectories.get().map { it.asFile }
        val checkedClasses =
            checkedClassFiles(directories.toList(), variantJars.get().map { it.asFile })
        val file = artifact.get().asFile
        failOn(LibraryArtifacts.violations(file.name, file.readBytes(), checkedClasses))
    }
}

/**
 * The class files that [CheckClassReferencesTask] reads and that [CheckLibraryArtifactTask] lets a
 * library's artifact ship (docs/design/boundaries.md sections 4.1 and 6), by their paths: those in
 * [directories] and in [jars], but those under `META-INF/`. Both tasks take them from here, so that
 * they agree on which classes were checked. Fails if two of them have the same path, since the
 * check of classes would see one of them and the artifact could ship the other, or if one is not at
 * the path of the class it holds, since the check finds classes by their paths.
 */
fun checkedClassFiles(directories: List<File>, jars: List<File>): Map<String, ByteArray> {
    class ClassFile(val input: File, val path: String, val bytes: ByteArray)
    val classFiles =
        directories.flatMap { directory ->
            directory
                .walk()
                .filter { it.isFile && it.extension == "class" }
                .map {
                    ClassFile(
                        directory,
                        it.relativeTo(directory).invariantSeparatorsPath,
                        it.readBytes(),
                    )
                }
                .toList()
        } +
            jars.flatMap { jar ->
                ZipFile(jar).use { zip ->
                    zip.entries()
                        .asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .map { entry ->
                            ClassFile(
                                jar,
                                entry.name,
                                zip.getInputStream(entry).use { it.readBytes() },
                            )
                        }
                        .toList()
                }
            }
    val checked = classFiles.filter { !it.path.startsWith("META-INF/") }
    val violations =
        checked
            .groupBy { it.path }
            .filterValues { it.size > 1 }
            .map { (path, copies) ->
                "$path is in more than one of ${copies.map { it.input }}, so the check of " +
                    "classes and the artifact could each take another"
            } +
            checked.mapNotNull { classFile ->
                val name = ClassReader(classFile.bytes).className
                if (classFile.path == "$name.class") null
                else "${classFile.path} in ${classFile.input} holds the class $name"
            }
    failOn(violations)
    return checked.associate { it.path to it.bytes }
}

/** The internal names of the classes in [file], a directory or a jar. */
private fun classNames(file: File): List<String> =
    when {
        !file.exists() -> emptyList()
        file.isDirectory ->
            file
                .walk()
                .filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(file).invariantSeparatorsPath.removeSuffix(".class") }
                .toList()
        else ->
            ZipFile(file).use { zip ->
                zip.entries()
                    .asSequence()
                    .map { it.name }
                    .filter { it.endsWith(".class") && !it.startsWith("META-INF/") }
                    .map { it.removeSuffix(".class") }
                    .toList()
            }
    }

private fun failOn(violations: List<String>) {
    val distinct = violations.distinct()
    if (distinct.isEmpty()) return
    throw GradleException(
        (distinct + "${distinct.size} boundary violation(s). See docs/decisions/0044.")
            .joinToString("\n")
    )
}

/** The project dependencies declared in every configuration of the project. */
fun Project.declaredDependencyEdges(): List<DependencyEdge> =
    configurations.flatMap { configuration ->
        configuration.dependencies.withType(ProjectDependency::class.java).map {
            DependencyEdge(
                configuration.name,
                DependencyNode.Project(path),
                DependencyNode.Project(it.path),
            )
        }
    }

/**
 * The edges to projects in the dependency graph resolved for [configuration], whose root is [root]:
 * from the root and from every component it reaches. A dependency that did not resolve still counts
 * as an edge to the project it requested.
 */
fun resolvedDependencyEdges(
    configuration: String,
    root: ResolvedComponentResult,
): List<DependencyEdge> {
    val edges = mutableListOf<DependencyEdge>()
    val visited = mutableSetOf(root.id)
    val pending = ArrayDeque(listOf(root))
    while (pending.isNotEmpty()) {
        val component = pending.removeFirst()
        val from = nodeOf(component.id)
        for (dependency in component.dependencies) {
            val to =
                when (dependency) {
                    is ResolvedDependencyResult -> {
                        val selected = dependency.selected
                        if (visited.add(selected.id)) pending.addLast(selected)
                        nodeOf(selected.id)
                    }
                    is UnresolvedDependencyResult ->
                        when (val requested = dependency.requested) {
                            is ProjectComponentSelector ->
                                projectNode(requested.buildPath, requested.projectPath)
                            else -> continue
                        }
                    else -> error("Unknown kind of dependency result: $dependency")
                }
            if (to !is DependencyNode.External) edges += DependencyEdge(configuration, from, to)
        }
    }
    return edges
}

private fun nodeOf(id: ComponentIdentifier): DependencyNode =
    when (id) {
        is ProjectComponentIdentifier -> projectNode(id.build.buildPath, id.projectPath)
        is ModuleComponentIdentifier -> DependencyNode.External("${id.group}:${id.module}")
        else -> DependencyNode.External(id.displayName)
    }

private fun projectNode(buildPath: String, projectPath: String): DependencyNode =
    if (buildPath == ":") DependencyNode.Project(projectPath)
    else DependencyNode.OtherBuild(buildPath, projectPath)
