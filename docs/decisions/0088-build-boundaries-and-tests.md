# Build, module boundaries, and tests

- Status: Approved
- Date: 2026-10-25
- Related: Requirements 6.1 and 7.2, sections 5 and 7 of [architecture.md](../design/architecture.md), [boundaries.md](../design/boundaries.md), sections 1, 5, 6, 7, 9, and 13 of [testing.md](../design/testing.md), sections 7.1 and 9.7 of [emulation.md](../design/emulation.md), "Verification" and "Things to handle with care" in [AGENTS.md](../../AGENTS.md), [0087](0087-public-api.md), [0090](0090-distribution.md), [0084](0084-session-and-threads.md), [0041](0041-security-review-by-module.md), [0083](0083-gate.md), [0046](0046-history-never-rewritten.md), [0086](0086-fonts.md), `build-logic`, `config/boundaries.toml`, `gradle/verification-metadata.xml`, `scripts/generate_unicode_tables.py`, `scripts/fuzz.sh`

## Background

An app may build the vtfx sources with another build system (for example Bazel), which compiles them with its own rules instead of the conventions in `build-logic`: whatever the sources need from the Gradle build has to be written again there, and a missing compiler option shows up only as errors in an unfamiliar codebase. All gates run with one command, `scripts/check.sh`, and are deterministic, with oracles that humans own (section 1 of [testing.md](../design/testing.md), Requirement 7.2). Dependencies and the build configuration are in the review scope, and the module boundaries are checked mechanically (Requirement 6.1).

## 1. Sources that build without Gradle

1. Each file that uses declarations marked `@InternalVtfxApi` ([0087](0087-public-api.md)) opts in with `@file:OptIn(InternalVtfxApi::class)`, in the main sources and in the tests; the conventions in `build-logic` pass no `optIn`. Apps still must not opt in: the sample app has no `@OptIn(InternalVtfxApi::class)`, so it still compiles with the public API alone.
2. No other compiler option of the conventions is needed to compile the sources: explicit API mode (`explicitApi()`) and warnings as errors (`allWarningsAsErrors`) only check the sources, and the JVM target (17, also for Java) chooses the bytecode version without changing the meaning of the sources. No Kotlin language feature that needs a flag (context parameters, `-Xjvm-default`, and so on) is used.
3. What another build system still has to provide, because the sources are written for it: the Compose compiler plugin for `vtfx-compose` (and the sample app); test source sets (and the test fixtures of `vtfx-core`) compiled as friends of their main source set, so that tests see `internal` declarations, as Gradle does for every test compilation (in Bazel, `associates` of `kt_jvm_test`); and, only for running the tests, the system properties through which some tests read the oracles in `spec/` and the recordings in `recordings/` (`vtfx.specDir`, `vtfx.recordingsDir`), and Robolectric for the tests of the Android modules.

**Why.** With the opt-in as a compiler option (`optIn` in `vtfx.jvm-library` and `vtfx.compose`), no module compiled without `-opt-in=com.google.vtfx.core.InternalVtfxApi`. A file-level opt-in, rather than one per declaration, keeps the change mechanical and close to the module-wide opt-in it replaces.

**Alternatives considered.**
- **Keep the compiler option and document it**: every build system that imports vtfx would have to set it on each module, and leaving it out gives errors that point at many files.
- **Opt in per declaration** (`@OptIn` on each function or class that uses a marked declaration): shows more precisely where the internal API is used, but needs many more annotations, and new code would have to add them one by one.
- **Propagate the opt-in by marking the users `@InternalVtfxApi` too**: would spread the marker to declarations that are not shared between modules, and change what the public API is.

**Impact of reversing.** Add `optIn.add("com.google.vtfx.core.InternalVtfxApi")` back to `compilerOptions` in `vtfx.jvm-library` and `vtfx.compose`, and remove the `@file:OptIn(InternalVtfxApi::class)` lines and their imports.

## 2. Committed generated sources

1. The sources generated from the Unicode Character Database are committed like the other sources: `UnicodeWidthTables.kt` and `PlaceholderDiacritics.kt` in `vtfx-core`, and `UnicodeDefaultIgnorables.kt` in `vtfx-gate` (sections 7.1 and 9.7 of [emulation.md](../design/emulation.md), section 5.2 of [gate-policy.md](../design/gate-policy.md)). `scripts/generate_unicode_tables.py` generates them from the copies of the UCD files in `third_party/unicode`; there is no generation task in the Gradle build. The script uses only the Python standard library, like the other scripts, and downloads nothing; its tests (`scripts/tests/test_generate_unicode_tables.py`) check the rules on small UCD files.
2. `scripts/check.sh` runs `scripts/generate_unicode_tables.py --check`, which regenerates the files in memory and fails if a committed file differs (section 9 of [testing.md](../design/testing.md)), so the files cannot drift from the UCD files or be edited by hand.
3. The generated files begin with the same Apache License header as the other sources, so the license header check covers them as it does every other source. The UCD files stay under the Unicode License v3 in `third_party/unicode`, as [0090](0090-distribution.md) arranges for the artifacts that contain the tables.
4. `vtfx.ktfmt` excludes `UnicodeWidthTables.kt` and `UnicodeDefaultIgnorables.kt` by path, because ktfmt would break their tables of value pairs into one value per line; the `--check` keeps their format fixed instead (topic 4).

**Why.** A source generated by a Gradle task into `build/` exists only in a Gradle build. With the files committed, another build system only lists the sources.

**Alternatives considered.**
- **Keep generating the tables in the build, and give other build systems the script**: every build system would have to run a generation step before compiling `vtfx-core`.
- **Generate the tables at run time from the UCD files as resources**: adds the UCD files to the artifact and parsing at startup, for data that changes once per Unicode version.
- **Format the generated files with ktfmt**: doubles their length and loses the pairs of first and last code point that make them readable.

**Impact of reversing.** Delete the committed files and the `--check` step in `scripts/check.sh`, add a task that runs the script into `build/` and register its output as a source directory of `vtfx-core` and `vtfx-gate`, and drop the exclusions in `vtfx.ktfmt`.

## 3. Results of calls are used

1. The conventions `vtfx.jvm-library`, `vtfx.android-library`, and `vtfx.android-application` compile every module, its tests and fuzz targets included, with `-Xreturn-value-checker=full`; with `allWarningsAsErrors`, a dropped result fails the build (section 7 of [architecture.md](../design/architecture.md)). The flag only checks: the sources compile the same without it, so topic 1 still holds.
2. The sources use neither `@IgnorableReturnValue` nor `val _`, so that a build without the flags compiles them.
3. A function whose result its callers drop returns nothing when the result carries nothing: a value that is always the same, a receiver for chaining, or a result that no caller reads.
4. A test uses a result it dropped when the result says what the test checks: the return values of `ImeEditor` and of the session's edits, a URL opener's result, an import's result.
5. Where a result is dropped on purpose, it is assigned to `val unused`, which both checks take as a use and which the CheckReturnValue checks suggest: a read that only registers a Compose observer, a key event that the IME is told was sent whatever it was, a copy with nothing to report, timing loops, and tests that only check that a call does not throw. A read for an observer that is followed by the value it guards uses `let` instead (`display.observeVersion().let { ... }`).
6. The Kotlin check does not see what the importing build's check also covers: calls of Java, Android, and AndroidX APIs that are neither in that build's lists of ignorable APIs nor marked. Only that build finds those.

**Why.** A build system that imports the sources may check that the result of every call is used: the CheckReturnValue checks of Error Prone and of Android Lint, which Google's internal build runs on Kotlin too, report a dropped value unless the function is marked ignorable. Sources that drop results force such a build to turn the check off for vtfx, which also turns it off for the bugs it finds. Kotlin's own unused return value checker (KEEP-412) reports a dropped result of a function of the module, and of the standard library where it is marked. Both checks accept `@kotlin.IgnorableReturnValue`, but the annotation, and `val _` for a dropped value, are experimental: the compiler refuses them unless it is given `-Xreturn-value-checker` and `-XXLanguage:+UnnamedLocalVariables`, and topic 1 keeps the sources free of features that need flags.

**Alternatives considered.**
- **Mark ignorable functions with `@IgnorableReturnValue`**: the way both checks name an ignorable function, but the importing build then needs `-Xreturn-value-checker` on every vtfx target, the flag that topic 1 keeps the sources from needing.
- **`@Suppress` with the names of both checks**: two names that are only strings to the compiler, on a declaration rather than the call.
- **A helper that drops its argument (`fun Any?.ignore()`)**: hides the intent from both checks' conventions, and from readers who know `val unused`.
- **Check only the main sources**: the importing build checks the tests too.

**Impact of reversing.** Remove the flag from the three conventions. The code may stay as it is: none of it depends on the flag.

## 4. Formatting Kotlin code

1. Kotlin code is formatted with ktfmt 0.64's `--kotlinlang-style`. `vtfx.ktfmt` in the root project defines `formatKotlin`, which formats the sources in place, and `checkKotlinFormat`, which fails, listing them, if any sources are not formatted; `check` runs it, so it is a gate in `scripts/check.sh` and CI (section 9 of [testing.md](../design/testing.md)).
2. The targets are the build scripts, `build-logic`, and the Kotlin sources in each module's `src`. `tools/ime-probe`, which is a separate build, the reproduction tests saved in bug reports (`docs/reviews/`), and the two generated tables that topic 2 names (`UnicodeWidthTables.kt`, `UnicodeDefaultIgnorables.kt`) are excluded.
3. ktfmt is called directly with `JavaExec`, without a formatting plugin such as Spotless, so that the dependencies under review are only ktfmt and its dependencies. The plain jar is used rather than a jar with its dependencies bundled, so that each dependency is resolved and visible like other dependencies.
4. Expressions that ktfmt 0.64 changes into a different form on a second formatting (calls chained on the result of a lambda, such as `map { ... }.let { ... }`) are rewritten into a form that is stable after one pass, since `checkKotlinFormat` would otherwise keep failing.
5. Formatting-only commits are registered in `.git-blame-ignore-revs`, in a later commit, since a commit cannot contain its own ID. The history is never rewritten ([0046](0046-history-never-rewritten.md)), so the registered IDs last.

**Why.** `kotlin.code.style=official` in `gradle.properties` only tells the IDE which style to use and is not checked in the build, so without a check the format relies on matching the surrounding code. The formatting follows Google's style for Kotlin: `--kotlinlang-style` (indent 4, 100 columns) is close to the Android Kotlin style guide that Google publishes, to the existing code, and to the IDE setting, and changes fewer lines than `--google-style`.

**Alternatives considered.**
- **ktfmt's `--google-style`**: Google's internal style (indent 2); changes almost every line, and is farthest from the existing code and the IDE setting.
- **ktlint**: rules can be configured individually, but the formatting result is not as uniquely determined as with ktfmt, and there are more configuration decisions.
- **Spotless**: can call ktfmt, but the plugin and its dependencies would be added to what is under review.
- **Formatting Python and shell as well** (pyink, pylint, shfmt, shellcheck): there are few targets, and a mechanism to install pinned pip dependencies would be needed, so it is postponed.

**Impact of reversing.** To change the style, change `--kotlinlang-style` in `Ktfmt.kt`, reformat everything with `./gradlew formatKotlin`, and add that commit to `.git-blame-ignore-revs`. To stop enforcing formatting, make `check` not depend on `checkKotlinFormat` in `vtfx.ktfmt.gradle.kts`, and revert the descriptions in AGENTS.md and section 9 of testing.md.

## 5. Dependency verification

`gradle/verification-metadata.xml` lists the SHA-256 checksum of every artifact and metadata file (POM, Gradle module) that the build resolves, and Gradle fails the build when an artifact is missing from the file or its checksum differs (section 5 of [architecture.md](../design/architecture.md)). Further:

1. `verify-metadata` is on and `verify-signatures` is off.
2. The file covers the build of this repository, including the included build `build-logic` and its plugins: Gradle applies the root build's file to the whole build. It is generated from the tasks of `scripts/check.sh` (`build`), the API reference (`:dokkaGenerate`), and the instrumented test APKs (`assembleAndroidTest`), so that the check, publishing, and the emulator tests resolve only listed artifacts.
3. It applies only to builds of this repository. Apps that depend on the published artifacts, and composite builds that include vtfx as an included build, use their own verification settings (or none).
4. The separate build in `tools/ime-probe` is not covered: Gradle looks for the file in that build's own `gradle/` directory, which does not exist. The probe is a research tool: it is not part of the published artifacts, and neither the check nor CI builds it.
5. Sources and javadoc jars (`*-sources.jar`, `*-javadoc.jar`) are trusted without a checksum: only IDEs download them, no build classpath contains them, and listing them would make every IDE sync depend on which IDE features were used when the file was generated. There are no other trusted artifacts.
6. Where an artifact differs by platform (for example `aapt2`), only the checksums for Linux are listed, since the check and CI run on Linux. A build on another platform fails verification until the entries for that platform are added with the command below.
7. The file has the license header of [0090](0090-distribution.md) as an XML comment, since `scripts/check.sh` checks every tracked file. Gradle keeps the comment when it updates the file.
8. When a dependency is added or updated, the metadata is updated in the same change with:

   ```sh
   GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --no-configuration-cache \
       --write-verification-metadata sha256 build :dokkaGenerate assembleAndroidTest
   ```

   The empty Gradle user home makes Gradle download every file again: with a warm cache, Gradle reuses the metadata it already parsed and leaves out parent POMs and BOMs, and a build with an empty cache, such as CI, then fails verification. The configuration cache is turned off so that every configuration, including the plugins, is resolved again. Gradle adds the entries for new artifacts and keeps the existing ones, the configuration (including the trusted artifacts above), and the license header; to drop the entries of artifacts no longer used, empty the `<components>` element first and run the command again. A task outside these (for example the emulator tests) that fails verification gets its entries the same way, with that task added to the command. Review the diff: each new checksum is trusted from the moment it is written. The file is under `gradle/`, which `config/protected-paths.toml` lists for security review.

**Why.** Dependencies are pinned by version, the Gradle distribution by `distributionSha256Sum`, and GitHub Actions by commit SHA, but pinning a version does not detect an artifact that changed on a repository (Maven Central, Google Maven, the Gradle Plugin Portal) or on the way from it, because a repository can serve different contents under the same version. The artifacts in question are the tools and libraries that the published vtfx artifacts are built from: AGP, the Kotlin compiler and Gradle plugin, the Compose compiler plugin, Dokka, ktfmt, the AndroidX and Compose libraries, and the test dependencies (JUnit, Robolectric, Roborazzi, Jazzer, ...). Requirement 6.1 puts dependencies in the review scope but does not ask for verification.

**Alternatives considered.**
- **PGP signatures as well** (`--write-verification-metadata pgp,sha256`): signatures tie an artifact to its publisher's key, so an update of a dependency could be checked without trusting the first download. But not every artifact is signed, so some would still need checksums or trust rules, the keys need a key ring checked into the repository or fetched from key servers, and keys themselves need judging whom to trust. Checksums already detect a changed artifact. Signatures can be added later without removing the checksums.
- **Add verification at publication**: the published artifacts are the ones the verification protects, but the artifacts built and tested before then are built from the same tools, and the cost per dependency change is the same before and after publication.
- **Not at all**: no file to update with each dependency change, but a changed artifact on a repository would go unnoticed into the build and the published artifacts.

**Impact of reversing.** Delete `gradle/verification-metadata.xml`, the sentence about updating it in AGENTS.md, and its row in section 5 of architecture.md. The review of external dependencies by the boundary check (sections 3 and 7 of [boundaries.md](../design/boundaries.md), topic 6) and the download of the test fonts (section 10 of [fonts.md](../design/fonts.md), [0086](0086-fonts.md)) also rely on it.

## 6. Module boundaries checked in Gradle's model and in compiled classes

The checks are described in [boundaries.md](../design/boundaries.md).

1. **The dependencies between modules are checked in Gradle's model of the build** by the convention plugin `vtfx.boundaries`, not by reading build files: the declared project dependencies of every configuration and every edge to a project in every resolved graph, so that dependencies added by plugins, on resolution, or by substitution are seen too (section 3 of boundaries.md). The root project applies the plugin, which registers the check on every other project itself, so that a module cannot leave it out; the root project, which ships nothing, is not checked (section 1).
2. **Tests have no list of their own**: the configurations of the tests and test fixtures are checked against the same `allowed_dependencies` as the main code. No module needs more for its tests (the verifier's tests in `vtfx-font-gate` read canonical fonts from files instead of depending on `vtfx-font`, [0041](0041-security-review-by-module.md)), and a list for tests would add edges, which loosens the rules: it waits until a module needs one and a human agrees to it.
3. **What a module's code uses is checked in its compiled classes**, the main classes that the module ships, read with ASM; the classes of tests are not read, since they are not shipped (section 4 of boundaries.md). Within this:
   - `restricted_members` lets other modules name a class as a type but not use its members, so `vtfx-session` may pass `HostConnection`, `ClipboardAccess`, and `UrlOpener` on to `vtfx-gate` in any of its files, and may call them in none (section 4.4, [0084](0084-session-and-threads.md), [0083](0083-gate.md)).
   - `restricted_classes` names what only some modules, or none, may refer to, by the names that the compiled classes use (section 4.5); `reserved_packages` keeps the packages of the gate modules to those modules, checked on the names of the compiled classes rather than `package` lines (section 4.6, [0041](0041-security-review-by-module.md)); `bootstrap_methods` lists the bootstrap methods that the compilers use, since their descriptors name classes that `restricted_classes` does not allow otherwise (section 4.7).
   - Every module whose classes are checked has an allowlist of what its classes may refer to outside the project, on top of a baseline for all, so that an API that no rule names cannot get in unseen. `vtfx-gate-android` and `vtfx-font-gate-android` list classes one by one, so that each list is what the module may do; packages are allowed whole only where they hold no capabilities (section 5).
   - Supertypes are read from class files, and a class that cannot be found fails the check instead of being taken to have no supertypes (section 4.3).
   - Every library module is checked; `vtfx-sample` is not: it plays a third-party app, which may use any capability (section 4.1).
4. **What a library's artifact brings into apps besides classes is checked in the published artifact**, rather than in the source directories, so that a change to the source sets cannot route around it (section 6).
5. **There are no rules over source text**: two copies of a rule would drift apart, and the checks of the classes see everything that regular expressions over the source lines saw. Reproduction tests of reviews need no exception, since tests are not checked.
6. **The checks are meant to hold against changes written to get around them** (Requirement 6.1), within the limits that review covers: the build itself, how allowed APIs are used, the classes of tests and the sample app, and the references of new dependencies and versions (section 7).
7. **`config/boundaries.toml` stays the single place of the rules**, read by the build logic with Jackson's TOML module (`jackson-dataformat-toml`) at the version of Jackson that AGP already puts on the classpath of the build logic, so that only one artifact is added to the build. The reader fails on what it does not understand instead of ignoring it (section 2).

**Why.** Reading build files misses every dependency that the text does not spell out as a literal `project(...)` call in the module's own build file: one added by a convention plugin, by `withDependencies` or `defaultDependencies` when a configuration is resolved, or by a dependency substitution. Rejecting what cannot be read (paths that are not literals, type-safe project accessors) still leaves ways that a build file can avoid.

**Alternatives considered.**
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

**Impact of reversing.**
- Remove `vtfx.boundaries`, `Boundaries.kt`, `BoundaryTasks.kt`, their tests, and the plugin from the root `build.gradle.kts`, and bring back `scripts/check_boundaries.py` with its tests, its step in `scripts/check.sh`, and its entries in `config/protected-paths.toml`, from the history.
- Remove `ClassReferences.kt`, `ClassHierarchy.kt`, `checkClassReferences`, their tests and fixtures, and from `config/boundaries.toml` `restricted_members`, `check_classes`, `restricted_classes`, `reserved_packages`, `bootstrap_methods`, `baseline_references`, and `allowed_references`, and bring back `restricted_apis`, with the exception of `TerminalSession.kt`.
- Remove `LibraryArtifacts.kt`, `checkLibraryArtifact`, and their tests.
- Remove `jackson-dataformat-toml` from `gradle/libs.versions.toml`, `build-logic/build.gradle.kts`, and `gradle/verification-metadata.xml`.

## 7. Library artifacts ship only the checked classes and no rules for R8

1. **Every class file that a library's artifact ships must be one that the check of classes read, byte for byte**, wherever in the jar or AAR it is (sections 4.1 and 6 of [boundaries.md](../design/boundaries.md)). The check stays on the published artifact rather than on the source directories (topic 6), so it holds for whatever the build puts in.
2. **Rules for R8 are not allowed in an artifact**: entries under `META-INF/proguard/` and `META-INF/com.android.tools/`, and an AAR's `proguard.txt`. A library that needs them later needs a human to agree to a way of reviewing them, since they change the classes of the app rather than of the library.
3. **An AAR's `classes.jar` is checked like an artifact of its own**, with all the rules of section 6, since R8 and the app's build read it like any other jar.

**Why.** The jar of a JVM module also takes in its resources, so a file ending in `.class` under `src/main/resources` would be shipped without being read. Such a class could use anything, and could take a name in a reserved package and replace a reviewed class of a gate module on the class path, which [0041](0041-security-review-by-module.md) and section 4.6 of boundaries.md mean to prevent. R8 takes rules for it from every jar into the app's build, and with them an app's release build could drop or change the calls of the gate modules. AGP leaves both out of an AAR, but without the check nothing would show that it does.

**Alternatives considered.**
- **Have `checkClassReferences` of the JVM modules read the jar instead of the classes directories**: it reads the shipped classes directly, but does not cover the `classes.jar` of an AAR, and keeps two different ways of tying the check to what is shipped.
- **Compare only the names of the classes**: a resource of the same name as a compiled class would go into the jar as a second entry, and the class loader takes one of them; comparing the bytes leaves no choice to it.
- **Reject `.class` files and rules only in `src/main/resources`, or add those directories to `config/protected-paths.toml`**: a change to the source sets or a plugin can add files from elsewhere, and a report leaves it to a reviewer to notice a binary file.
- **Allow rules for R8 and put them under review**: no library needs them, and their effect on the app is hard to review.

**Impact of reversing.** Remove the comparison with the checked classes, the rules for R8, and the check of `classes.jar` from `LibraryArtifacts.kt`, the inputs of the classes from `CheckLibraryArtifactTask` and `vtfx.boundaries.gradle.kts`, and their tests in `LibraryArtifactsTest.kt`; update section 6 of boundaries.md.

## 8. checkBoundaries requires every check to have run

1. **Every check reports when it passes, and `checkBoundaries` fails on a check that did not.** The checks report their task paths to a build service of the build (`BoundaryChecks`), which lives for one build only; `checkBoundaries` knows the paths of all the checks that the plugin registered and fails, naming them, on those that did not report (section 1 of [boundaries.md](../design/boundaries.md)). The checks have no outputs, so Gradle runs them in every build that asks for them and never takes them as up to date or from a cache.
2. **The report is not a file**: an output file left from an earlier build stays when a task is later disabled, so it could not show that the check ran in this build.

**Why.** If `checkBoundaries` only depends on the checks of the modules, a check that is disabled (`enabled = false`), skipped (`onlyIf`), stopped before its action, or left out of the task graph (`-x`) lets `checkBoundaries` and the build pass, so a one-line change to a build file, which review can miss, takes a module out of the check that section 1 of boundaries.md says a module cannot leave. The build files are under security review (section 7 of boundaries.md), and that review is easier when turning a check off fails the build instead of only showing in a diff.

**Alternatives considered.**
- **Inspect the task graph when it is ready** (`enabled`, the tasks in the graph): `onlyIf` conditions and actions removed or stopped are not visible from there, and the inspection depends on Gradle's internal API.
- **Output files that `checkBoundaries` reads**: stale files pass, and making them fresh would need the checks to always run anyway, which they already do without outputs.
- **Reject Groovy build scripts and `buildSrc` in the build**: a Groovy root script would not apply the plugin that checks, so the check cannot catch its own removal; `config/protected-paths.toml` lists those files for review instead.

**Impact of reversing.** Remove `BoundaryChecks`, `BoundaryCheckTask`, and `requiredChecks` from `BoundaryTasks.kt` and `vtfx.boundaries.gradle.kts`, and `CheckBoundariesTest.kt`; make the checks plain tasks again, and update section 1 of boundaries.md.

## 9. Tests on JUnit 4

1. **The tests of the library modules and the sample app run on JUnit 4.** The tests of `build-logic` stay on JUnit Jupiter: they test the Gradle build itself, which another build system does not use (section 13 of [testing.md](../design/testing.md)).
2. **Parameterized tests use TestParameterInjector.**
3. **The Jupiter-only features are replaced without changing what the tests check** (section 13 of testing.md), and temporary directories use `TemporaryFolder`.
4. **`ExpectedFailures` does not depend on a test framework**: it runs a case and throws an `AssertionError` when the case does not fail or pass as the list says, and `OfficialGoldensTest` uses it too.

**Why.** The test rules of other build systems commonly run only JUnit 4 tests (for example Bazel's `java_test` and `android_local_test`). With the JVM modules on Jupiter, an app that builds vtfx that way could run the Robolectric tests but not the tests of the JVM modules, which are most of the tests, including the spec runners, the attacks on the gate, and the replay of the fuzzing corpus. The Android modules need JUnit 4 anyway, for Robolectric, the Compose test rule, and Roborazzi's rule. Two frameworks in one repository also split the shared test code: an `ExpectedFailures` that returned Jupiter's `DynamicTest` made `OfficialGoldensTest` implement the same list again. TestParameterInjector takes parameters per test method, and its value providers can list data at run time (the spec cases, the recordings, the fuzzing corpus), so a spec runner keeps listing its cases, running them, and checking the expected failures in one class.

**Alternatives considered.**
- **Run everything on the JUnit Platform, with the vintage engine for the JUnit 4 tests**: one launcher in Gradle, but the tests of the JVM modules stay on Jupiter, so a build that runs only JUnit 4 still cannot run them, and the fuzz targets stay tied to JUnit.
- **Jupiter everywhere**: Robolectric has no Jupiter runner, so the Android modules would still need JUnit 4.
- **JUnit 4's `Parameterized` instead of TestParameterInjector**: no new dependency, but parameters are per class, so a class with differently parameterized methods would be split, and the check for stale entries of the expected failures would move out of the spec runner's class.

**Impact of reversing.** Put the Jupiter dependencies and `useJUnitPlatform()` back into `vtfx.jvm-library`, and rewrite the tests of the JVM modules with Jupiter's features.

## 10. Fuzzing in the gate

1. The gate (`./gradlew build` in `scripts/check.sh`) both replays the fixed corpus of each fuzz target in the usual `test` task, deterministically, and fuzzes each target for 30 seconds in the `fuzz` task, which `check` depends on; hangs and memory exhaustion are failures too, and long fuzzing runs separately (sections 7 and 9 of [testing.md](../design/testing.md)).
2. Whatever fuzzing finds, on whichever run, is a real bug: there are no false positives. A fuzzing failure is therefore treated not as non-determinism of the gate but as a bug that had not been found yet. The input found is written into the corpus directory, and from then on the deterministic replay checks it.
3. **The fuzz targets are libFuzzer entry points** in a `fuzz` source set of the module, which depends on the module and not on JUnit or Jazzer (section 7 of testing.md). Jazzer runs them with its own driver instead of JUnit, and any other fuzzing engine or continuous fuzzing service for the JVM that takes this entry point can run the same code.
4. **The corpus of each target is a plain directory** with one input per file, holding hand-written seeds and the inputs that found bugs; the replay runs each file with a time limit of 10 seconds, the limit at which fuzzing reports a hang. The corpus that fuzzing grows is kept in `build/` and not committed.

**Why.** Section 7 of testing.md has the gate replay a fixed corpus and fuzz for a short time, while section 1 says that gates are deterministic; fuzzing tries different inputs on each run, so strictly it is not deterministic, which item 2 reconciles. The time limit on the replay keeps hangs on the corpus detected without relying on the per-input timeout of a fuzzing engine, which not every engine offers. Jazzer's JUnit integration (`@FuzzTest`) exists only for Jupiter, so targets written for it could not be replayed by a build that runs only JUnit 4 (topic 9), and could only be run through JUnit, not by a fuzzing engine on its own.

**Alternatives considered.**
- **Fix the random seed and the number of runs**: fixing the seed means trying the same inputs every time, which makes putting short fuzzing in the gate less meaningful.
- **Remove fuzzing from the gate**: would relax the rules of testing.md.
- **Keep `@FuzzTest` and add libFuzzer entry points beside them**: two ways to run each target, and the replay would still need Jupiter.

**Impact of reversing.** To take short fuzzing out of the gate, remove `tasks.named("check") { dependsOn(fuzz) }` from `vtfx.fuzz.gradle.kts` in `build-logic`, and the short fuzzing from sections 7 and 9 of testing.md (a relaxation of the gate, which only a human may make). To go back to `@FuzzTest`, move the targets back into the tests, move the corpus to `<FuzzTest>Inputs/<method>/` in the test resources, and make the `fuzz` task a `Test` task again.

## 11. Fuzz tasks run in parallel

1. The fuzz tasks have no limit on how many run at once. They run in parallel with each other and with the other tasks, like any other task (`vtfx.fuzz` in `build-logic`, section 7 of [testing.md](../design/testing.md)).
2. The amount of fuzzing in the gate does not change: each target is still fuzzed for 30 seconds on every run (topic 10).
3. The tests with a time limit do not need the fuzzers to wait. `ResourceExhaustionTest` limits the CPU time of its thread, which does not grow when other processes compete for the CPUs; its limit on the elapsed time (120 seconds) only catches hangs. The fuzzers themselves report an input as a hang only after 10 seconds.

**Why.** The fuzz tasks are never up to date, so their time is the least that the check takes. Run one after another, they made the check several times longer than in parallel even when every other task was up to date, while all the other tasks ran in parallel beside them ([research/check-duration-2026-10.md](../research/check-duration-2026-10.md)).

**Alternatives considered.**
- **Keep one fuzz task at a time**: the check stays that long even when nothing changed.
- **Run the fuzz tasks in parallel, but after all the test tasks** (`mustRunAfter`): keeps the tests apart from the fuzzers, but the fuzzing starts only after the slowest tests (the Robolectric tests of `vtfx-compose`), which makes a full build longer. No test needs it, for the reason in item 3.
- **Fuzz for less time, or skip fuzzing when the code has not changed**: changes what the gate checks (topic 10).

**Impact of reversing.** Register a shared build service with `maxParallelUsages` of 1 in `vtfx.fuzz.gradle.kts` in `build-logic`, and declare that the fuzz task uses it (`usesService`). On a machine with fewer CPUs than the fuzzers and the other tasks need, such as a CI runner with a few CPUs, the fuzzers share the CPUs, and each one tries fewer inputs in its 30 seconds than it would alone; a machine with little memory has to hold the fuzzing JVMs, one per fuzzed module with a heap of at most 1 GiB each, beside the Gradle daemon and the other test JVMs. If either matters, a limit of the number of fuzz tasks at once by the number of CPUs or the memory can be added instead of reversing.

## 12. Checking the official screenshot goldens

The procedure is in sections 5 and 6 of [testing.md](../design/testing.md).

1. Promoting copies a provisional golden to `spec/screenshots/` with the same file name. The file names contain the test class and the test name, so goldens of other modules would not collide.
2. `OfficialGoldensTest` in `vtfx-compose` checks that each official golden has a provisional golden with the same pixels; with the screenshot tests, which compare the rendering with the provisional goldens, this checks the rendering against the official goldens.
3. An official golden that the provisional golden no longer matches is listed in `spec-expected-failures.txt` of `vtfx-compose`, as spec cases are, by the agent that changes the rendering, with an item in review-queue.md; the human who promotes the new provisional golden removes the entry.
4. Provisional goldens without an official golden (new tests) are not checked by this test; they wait for promotion.

**Why.** Roborazzi compares the rendering with one directory, the provisional goldens, so without this test nothing would check `spec/screenshots/`.

**Alternatives considered.**
- **Point Roborazzi at `spec/screenshots/`**: the rendering would be compared with the official goldens directly, but recording (`recordRoborazziDebug`) would write into `spec/`, which agents must not change, and there would be no place for goldens of new tests before promotion.
- **Compare the bytes of the files**: simpler, but a PNG with the same pixels can be encoded differently (for example by another version of the encoder), which would fail without a visible change. Roborazzi itself compares pixels.
- **Report differences without failing**: keeps agents free to change the rendering, but an official golden that is not a gate does not protect anything. The expected failure list already gives the same freedom, with the difference visible in a reviewed file.

**Impact of reversing.** Remove `OfficialGoldensTest`, the system properties and input in `vtfx.screenshot-test`, the screenshot entries of `spec-expected-failures.txt`, and the description in sections 5 and 6 of testing.md. The official goldens can stay as a record.
