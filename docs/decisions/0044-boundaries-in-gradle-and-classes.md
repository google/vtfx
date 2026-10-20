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
   - `scripts/check_boundaries.py` still reads the build files as well, until the Gradle check has replaced it.
2. **Tests have no list of their own.** The configurations of the tests and test fixtures are checked against the same `allowed_dependencies` as the main code. No module needs more for its tests today (the verifier's tests in `vtfx-font-gate` read canonical fonts from files instead of depending on `vtfx-font`, [0041](0041-security-review-by-module.md)), and a list for tests would add edges, which loosens the rules: it waits until a module needs one and a human agrees to it.
3. **`config/boundaries.toml` stays the single place of the rules**, read by the build logic with Jackson's TOML module (`jackson-dataformat-toml`), at the version of Jackson that AGP already puts on the classpath of the build logic, so that only one artifact is added to the build. The reader fails on a key it does not know and on an allowed dependency that is not a declared module, instead of ignoring them.

## Alternatives considered

- **Keep reading build files, and reject more of what cannot be read**: each new way of adding a dependency needs a new rule, and the text of one file never shows what plugins and resolution add.
- **Resolve only the classpaths of the main code**: cheaper, but the configurations of tests, lint, and those that a plugin creates would not be checked, and the list of names would have to be kept up to date with AGP.
- **Check only the direct dependencies of each project**: an external module substituted by a project further down the graph would not be seen.
- **Register the check from each module's convention plugin**: a module that does not apply the plugin would not be checked.
- **Another TOML library (tomlj), or a reader written for the subset used**: tomlj adds two artifacts that the build does not have yet, and a reader of our own is more code to review in the check itself.

## Impact of reversing

- Remove `vtfx.boundaries`, `Boundaries.kt`, `BoundaryTasks.kt`, their tests, and the plugin from the root `build.gradle.kts`. `scripts/check_boundaries.py` still checks the build files.
- Remove `jackson-dataformat-toml` from `gradle/libs.versions.toml`, `build-logic/build.gradle.kts`, and `gradle/verification-metadata.xml`.
