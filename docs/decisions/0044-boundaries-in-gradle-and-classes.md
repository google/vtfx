# Checking the module boundaries in Gradle's model and in compiled classes

- Status: Provisional
- Date: 2026-10-20
- Related: Requirement 6.1, section 5 of [architecture.md](../design/architecture.md), [0027](0027-session-takes-connection.md), [0035](0035-dependency-verification.md), [0041](0041-security-review-by-module.md), [0042](0042-user-clipboard-through-gate.md), the item "Boundary check against adversarial changes" in [review-queue.md](../review-queue.md)

## Background

`scripts/check_boundaries.py` checked the module boundaries of `config/boundaries.toml` in the text of the sources: the `project(...)` calls of each module's build file against `allowed_dependencies`, and regular expressions for restricted APIs against each line of Kotlin and Java. Requirement 6.1 allows such a guardrail in the initial phase, and a human decided to make the check hold against changes written to get around it after publication (2026-10-15).

Reading build files misses every dependency that the text does not spell out as a literal `project(...)` call in the module's own build file: one added by a convention plugin, by `withDependencies` or `defaultDependencies` when a configuration is resolved, or by a dependency substitution. The check worked around some of these by rejecting what it could not read (paths that are not literals, type-safe project accessors), which a build file can still avoid.

## Decision

1. **The dependencies between modules are checked in Gradle's model of the build**, by the convention plugin `vtfx.boundaries` (`build-logic/src/main/kotlin/`), not by reading build files. The root project applies it, and it registers the check on every other project itself, so that a module cannot leave the check out by not applying a convention plugin. `checkBoundaries` runs with the `check` of the root project, and so with `./gradlew build` in `scripts/check.sh`.
   - The projects of the build and the modules of `config/boundaries.toml` must be the same: a project that is not declared, and a declared module that is not a project, are violations. A nested project (such as `:vtfx-gate:x`) has no entry and is a violation.
   - For every configuration of every project, the project dependencies it declares are checked against `allowed_dependencies`.
   - For every configuration that can be resolved, every edge to a project in the resolved graph is checked: from the project itself, and from every component it reaches. An edge from another project of the build is checked against that project's entry; an edge from a module of a repository to a project, or to a project of another build, is a violation however it came about. This sees what the declarations do not: dependencies added on resolution, and substitutions. A dependency that does not resolve still counts as an edge to the project it requested.
   - An edge from a project to itself is allowed (the tests of `vtfx-core` depend on its own test fixtures).
   - The root project is not checked: it only aggregates the API reference of the library modules, and ships nothing. Its build file is under security review like every other.
   - `scripts/check_boundaries.py` no longer reads the build files.
2. **Tests have no list of their own.** The configurations of the tests and test fixtures are checked against the same `allowed_dependencies` as the main code. No module needs more for its tests today (the verifier's tests in `vtfx-font-gate` read canonical fonts from files instead of depending on `vtfx-font`, [0041](0041-security-review-by-module.md)), and a list for tests would add edges, which loosens the rules: it waits until a module needs one and a human agrees to it.
3. **What a module's code uses is checked in its compiled classes**, by `checkClassReferences` (one task per variant in the Android modules), which `checkBoundaries` depends on. The check reads the main classes that the module ships: those of the source set `main` in the JVM modules, and AGP's `ScopedArtifact.CLASSES` of each variant (Kotlin and Java) in the Android modules. The classes of tests are not read: they are not shipped, and fakes in them may implement anything.
   - From each class it reads, with ASM, every class named anywhere (supertypes, descriptors and generic signatures, annotations, instructions, constants, bootstrap methods), and every field and method used (in instructions, method handles, and bootstrap methods). Debug information is not read.
   - `restricted_members` lists classes whose fields and methods only some modules may use, directly or through a subtype; other modules may still name them as types. This replaces the exception of `TerminalSession.kt` for `HostConnection`, `ClipboardAccess`, and `UrlOpener` ([0027](0027-session-takes-connection.md), [0042](0042-user-clipboard-through-gate.md)): vtfx-session may pass them on to vtfx-gate in any of its files, and may call them in none. A use through any subtype counts, including the methods of `Object` called on them, which no module but the allowed ones needs.
   - The supertypes are read from the module's classes, what it is compiled against, and, for the Android modules, the boot class path (`android.jar`), and then from the platform that runs the build. A class that none of them has fails the check, instead of being taken to have no supertypes.
   - Each module says whether its classes are checked (`check_classes`). Every library module is; `vtfx-sample` is not: it plays a third-party app, which may use any capability.
4. **`config/boundaries.toml` stays the single place of the rules**, read by the build logic with Jackson's TOML module (`jackson-dataformat-toml`), at the version of Jackson that AGP already puts on the classpath of the build logic, so that only one artifact is added to the build. The reader fails on a key it does not know and on an allowed dependency that is not a declared module, instead of ignoring them.

## Alternatives considered

- **Keep reading build files, and reject more of what cannot be read**: each new way of adding a dependency needs a new rule, and the text of one file never shows what plugins and resolution add.
- **Resolve only the classpaths of the main code**: cheaper, but the configurations of tests, lint, and those that a plugin creates would not be checked, and the list of names would have to be kept up to date with AGP.
- **Check only the direct dependencies of each project**: an external module substituted by a project further down the graph would not be seen.
- **Register the check from each module's convention plugin**: a module that does not apply the plugin would not be checked.
- **Read the constant pool directly**: about as simple, but parsing it is code of our own to review, while ASM, already on the class path of the build logic, gives the descriptors, the kinds of members, and the method handles ready to use.
- **Check only the members declared by a restricted class, or skip the methods of `Object`**: more precise, but it needs the members of every class in the hierarchy, and no module that may not use these classes has a reason to call anything on them.
- **Another TOML library (tomlj), or a reader written for the subset used**: tomlj adds two artifacts that the build does not have yet, and a reader of our own is more code to review in the check itself.

## Impact of reversing

- Remove `vtfx.boundaries`, `Boundaries.kt`, `BoundaryTasks.kt`, their tests, and the plugin from the root `build.gradle.kts`, and bring back the parsing of `project(...)` and `include(...)` in `scripts/check_boundaries.py` with its tests.
- Remove `ClassReferences.kt`, `ClassHierarchy.kt`, `checkClassReferences`, their tests and fixtures, and `restricted_members` and `check_classes` from `config/boundaries.toml`, and bring back the exception of `TerminalSession.kt` in `restricted_apis`.
- Remove `jackson-dataformat-toml` from `gradle/libs.versions.toml`, `build-logic/build.gradle.kts`, and `gradle/verification-metadata.xml`.
