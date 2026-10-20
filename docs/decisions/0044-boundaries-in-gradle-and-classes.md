# Checking the module boundaries in Gradle's model and in compiled classes

- Status: Provisional
- Date: 2026-10-20
- Related: Requirement 6.1, section 5 of [architecture.md](../design/architecture.md), [0027](0027-session-takes-connection.md), [0035](0035-dependency-verification.md), [0041](0041-security-review-by-module.md), [0042](0042-user-clipboard-through-gate.md), [boundaries.md](../design/boundaries.md)

## Background

`scripts/check_boundaries.py` checked the module boundaries of `config/boundaries.toml` in the text of the sources: the `project(...)` calls of each module's build file against `allowed_dependencies`, and regular expressions for restricted APIs against each line of Kotlin and Java. Requirement 6.1 allows such a guardrail in the initial phase, and a human decided to make the check hold against changes written to get around it after publication (2026-10-15).

Reading build files misses every dependency that the text does not spell out as a literal `project(...)` call in the module's own build file: one added by a convention plugin, by `withDependencies` or `defaultDependencies` when a configuration is resolved, or by a dependency substitution. The check worked around some of these by rejecting what it could not read (paths that are not literals, type-safe project accessors), which a build file can still avoid.

## Decision

The checks are described in [boundaries.md](../design/boundaries.md); this records what was decided.

1. **The dependencies between modules are checked in Gradle's model of the build**, by the convention plugin `vtfx.boundaries`, not by reading build files: the declared project dependencies of every configuration, and every edge to a project in every graph that Gradle resolves, from the project and from every component it reaches, so that dependencies added by plugins, on resolution, or by substitution are seen too. The projects of the build and the modules of `config/boundaries.toml` must be the same. The root project applies the plugin, which registers the check on every other project itself, so that a module cannot leave it out; the root project, which ships nothing, is not checked (section 3 of [boundaries.md](../design/boundaries.md)).
2. **Tests have no list of their own.** The configurations of the tests and test fixtures are checked against the same `allowed_dependencies` as the main code. No module needs more for its tests (the verifier's tests in `vtfx-font-gate` read canonical fonts from files instead of depending on `vtfx-font`, [0041](0041-security-review-by-module.md)), and a list for tests would add edges, which loosens the rules: it waits until a module needs one and a human agrees to it.
3. **What a module's code uses is checked in its compiled classes**, the main classes that the module ships, read with ASM; the classes of tests are not read, since they are not shipped (section 4 of [boundaries.md](../design/boundaries.md)).
   - `restricted_members` lets other modules name a class as a type but not use its members. This replaces the exception of `TerminalSession.kt` for `HostConnection`, `ClipboardAccess`, and `UrlOpener` ([0027](0027-session-takes-connection.md), [0042](0042-user-clipboard-through-gate.md)): vtfx-session may pass them on to vtfx-gate in any of its files, and may call them in none.
   - `restricted_classes` holds the rules that `restricted_apis` held as regular expressions, by the names that the compiled classes use, and adds what they did not cover: reflection and loading classes, processes, a system service named by a string, the platform's image decoders, and the implementations of the clipboard and the URL opener that only apps make.
   - `reserved_packages` keeps the packages of the gate modules, `com.google.vtfx.font.gate` and, at a human's request, `com.google.vtfx.gate`, to those modules, checked on the names of the compiled classes instead of `package` lines ([0041](0041-security-review-by-module.md)).
   - `bootstrap_methods` lists the bootstrap methods that the compilers use, since their descriptors name classes that `restricted_classes` does not allow otherwise.
   - **Every module whose classes are checked has an allowlist** of what its classes may refer to outside the project, on top of a baseline for all, so that an API that no rule names cannot get in unseen. vtfx-gate-android and vtfx-font-gate-android list classes one by one, so that each list is what the module may do; packages are allowed whole only where they hold no capabilities (section 5 of [boundaries.md](../design/boundaries.md)).
   - Supertypes are read from class files, and a class that cannot be found fails the check instead of being taken to have no supertypes.
   - Every library module is checked; `vtfx-sample` is not: it plays a third-party app, which may use any capability.
4. **What a library's artifact brings into apps besides classes is checked in the artifact** that is published, rather than in the source directories, so that a change to the source sets cannot route around it (section 6 of [boundaries.md](../design/boundaries.md)).
5. **The rules over source text are gone.** `scripts/check_boundaries.py`, its tests, and `restricted_apis` are removed once the compiled classes are checked against the same rules and the allowlists: two copies of a rule would drift apart, and the checks of the classes see everything that the regular expressions saw. Reproduction tests of reviews need no exception any more, since tests are not checked.
6. **The checks are meant to hold against changes written to get around them**, which resolves the item "Boundary check against adversarial changes" that review-queue.md held (decided by a human to be done after publication), within the limits that review covers: the build itself, how allowed APIs are used, the classes of tests and the sample app, and the references of new dependencies and versions (section 7 of [boundaries.md](../design/boundaries.md)).
7. **`config/boundaries.toml` stays the single place of the rules**, read by the build logic with Jackson's TOML module (`jackson-dataformat-toml`), at the version of Jackson that AGP already puts on the classpath of the build logic, so that only one artifact is added to the build. The reader fails on what it does not understand instead of ignoring it (section 2 of [boundaries.md](../design/boundaries.md)).

## Alternatives considered

- **Keep reading build files, and reject more of what cannot be read**: each new way of adding a dependency needs a new rule, and the text of one file never shows what plugins and resolution add.
- **Resolve only the classpaths of the main code**: cheaper, but the configurations of tests, lint, and those that a plugin creates would not be checked, and the list of names would have to be kept up to date with AGP.
- **Check only the direct dependencies of each project**: an external module substituted by a project further down the graph would not be seen.
- **Register the check from each module's convention plugin**: a module that does not apply the plugin would not be checked.
- **Read the constant pool directly**: about as simple, but parsing it is code of our own to review, while ASM, already on the class path of the build logic, gives the descriptors, the kinds of members, and the method handles ready to use.
- **Check only the members declared by a restricted class, or skip the methods of `Object`**: more precise, but it needs the members of every class in the hierarchy, and no module that may not use these classes has a reason to call anything on them.
- **Allowlists by package only**: shorter lists, but a package such as `java.util` also holds `ServiceLoader`, and `java.lang` holds `System` and `Runtime`; classes are listed one by one where a package mixes data with capabilities.
- **Count a member inherited by a class of the project as a member of that class**: simpler, but a module's own subclass of a platform class could then use any member of it unseen.
- **Check the manifests and resources in the source directories**: simpler to read, but a change to the source sets, or a plugin, can add files that the directories do not hold.
- **Another TOML library (tomlj), or a reader written for the subset used**: tomlj adds two artifacts that the build does not have yet, and a reader of our own is more code to review in the check itself.

## Impact of reversing

- Remove `vtfx.boundaries`, `Boundaries.kt`, `BoundaryTasks.kt`, their tests, and the plugin from the root `build.gradle.kts`, and bring back `scripts/check_boundaries.py` with its tests, its step in `scripts/check.sh`, and its entries in `config/protected-paths.toml`, from the history.
- Remove `ClassReferences.kt`, `ClassHierarchy.kt`, `checkClassReferences`, their tests and fixtures, and `restricted_members` and `check_classes` from `config/boundaries.toml`, `restricted_classes`, `reserved_packages`, `bootstrap_methods`, `baseline_references`, and `allowed_references`, and bring back `restricted_apis`, with the exception of `TerminalSession.kt`.
- Remove `LibraryArtifacts.kt`, `checkLibraryArtifact`, and their tests.
- Remove `jackson-dataformat-toml` from `gradle/libs.versions.toml`, `build-logic/build.gradle.kts`, and `gradle/verification-metadata.xml`.
