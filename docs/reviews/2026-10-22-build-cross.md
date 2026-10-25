# 2026-10-22 Bugs reproduced in a code review

- Review target: `b649be2a7d94a8b56c2921c235bdcef9c606f018`
- Reviewer: coding agent
- Points of view: 1 (security boundaries, module boundary check, artifact check, protected paths), 2 (frozen display), 3 (discrepancies with boundaries.md, session-and-threads.md, testing.md), 4 (combinations across module boundaries)

Reproduced 6 bugs with 9 test cases. This review did not change product code.

## Status

| ID | Severity | Bug | State | Fix commit / verification |
|---|---|---|---|---|
| R1 | High | `checkClassReferences` allows calling restricted or unallowed inherited external methods through a project interface or deeper superclass | Fixed | `3d476ea6268e08a99cc5aaad625b351aade6d1e0`: `declaringClass` resolves members as the JVM does (superclasses before interfaces for methods, interfaces first for fields), and both checks count methods a class inherits to implement an interface its superclass does not implement as uses (`ClassHierarchy.inheritedImplementations`). Failed before: `AllowlistViolationsTest.allowlistViolations_methodInheritedToImplementOwnInterface_reportsDeclaringClass`, `AllowlistViolationsTest.allowlistViolations_memberOfDeeperSuperclassAndOfOwnInterface_reportsSuperclass`, `ClassHierarchyTest.declaringClass_fieldOfSuperclassAndOfOwnInterface_isTheInterface`, `ClassHierarchyTest.declaringClass_methodOfDeeperSuperclassAndOfOwnInterface_isTheSuperclass`, `ClassViolationsTest.classViolations_methodInheritedToImplementOwnInterface_reportsIt`. From the verification, `a4c39fc14875bb7ee64dfe3e63d64c9c327d2adf` passes over static and private methods in `inheritedImplementations` |
| R2 | High | `checkLibraryArtifact` allows unchecked `META-INF/` `.class` entries from `variantJars` and shadows duplicate class paths across inputs | Fixed | `3332220aa6e803d44b25bda766ec78f55e28cd43`: both checks take the classes from `checkedClassFiles`, which leaves out `META-INF/` and fails on duplicate paths and on class files not at the path of their class. Failed before: `CheckBoundariesTest.checkBoundaries_classUnderMetaInfOfVariantJar_fails`, `CheckBoundariesTest.checkBoundaries_classAtTheSamePathInTwoDirectories_fails` |
| R3 | Medium | `config/protected-paths.toml` omits scripts and tests that `scripts/check.sh` executes | Open | — |
| R4 | Medium | `TerminalSession` delivers `onStickyModifiersChanged` and `onTitleChanged` on the wrong thread when another thread calls `feed` concurrently | Open | — |
| R5 | Medium | `TerminalDisplay.detach` leaves `invalidationPending` and `synchronizationTimeoutPending` set, freezing the display if re-attached | Open | — |
| R6 | Low | `KotlinSignatures` includes declarations in `@file:InternalVtfxApi` file facades and `@InternalVtfxApi` typealiases | Open | — |

The meaning of the states and how to update them are in 3.3 of [README.md](README.md).

## Running the reproduction tests

The reproduction tests are in [repro-2026-10-22-build-cross/](repro-2026-10-22-build-cross/). Run them from the root of the repository.

```sh
./gradlew --console=plain --no-configuration-cache \
  -I docs/reviews/repro-2026-10-22-build-cross/review.init.gradle \
  :vtfx-compose:reviewRepro
```

At the target commit, the result is **9 tests completed, 9 failed**.
Requires JDK 21, the Android SDK, and `python3` (for R3). No emulator or connected device is needed.

## R1: `checkClassReferences` allows calling restricted or unallowed inherited external methods through a project interface or deeper superclass

- Related: [Boundaries.kt](../../build-logic/src/main/kotlin/Boundaries.kt) (`externalMember`, `allowlistViolations`, `classViolations`), [ClassHierarchy.kt](../../build-logic/src/main/kotlin/ClassHierarchy.kt) (`declaringClass`)
- Contract: sections 4.2, 4.3, and 5.1 of [boundaries.md](../design/boundaries.md), and [decisions/0088](../decisions/0088-build-boundaries-and-tests.md):
  - Section 4.2: "The class that declares a member used through some owner is the owner itself or the nearest of its supertypes that declares the member, superclasses before interfaces."
  - Section 4.3: "Neither the class itself nor any of its subtypes may be used as the owner of a field or method instruction or handle, so calling a restricted method through a subclass does not bypass the rule."
  - Section 5.1: "A member used through a class of the project that the class inherits from a class outside the project counts as a use of the member through the class that declares it, which an allowlist must then allow. A module's own subclass of a platform class therefore does not open up the platform class's members."
- Reproduction tests: `r1a_interfaceTunnelBypassesRestrictedMemberAndAllowlist`, `r1b_declaringClassVisitsShallowInterfaceBeforeDeeperSuperclass`

Steps:

1. Case A (`r1a_interfaceTunnelBypassesRestrictedMemberAndAllowlist`): In `vtfx-compose`, define a project interface `ViewTunnel` declaring `performHapticFeedback(I)Z` (listed in `restricted_members` in `config/boundaries.toml`) and `setKeepScreenOn(Z)V` (not in `vtfx-compose`'s allowlists). Define `SubView` extending `android/view/View` and implementing `ViewTunnel` without overriding either method (in JVM bytecode, the inherited `public` methods on `android/view/View` satisfy `ViewTunnel` without generating synthetic bridges in `SubView.class`). Call both methods through `ViewTunnel`.
2. Case B (`r1b_declaringClassVisitsShallowInterfaceBeforeDeeperSuperclass`): Define `SubView1` extending `android/view/View`, project interface `Mask` declaring `performHapticFeedback(I)Z` and `setKeepScreenOn(Z)V`, and `SubView2` extending `SubView1` and implementing `Mask` without overriding either method. Call `subView2.performHapticFeedback(0)` and `subView2.setKeepScreenOn(true)` directly on `SubView2`.

Expected result: In both cases, `classViolations` reports the restricted member `android/view/View.performHapticFeedback:(I)Z`, `allowlistViolations` reports the unallowed member `android/view/View.setKeepScreenOn:(Z)V`, and in Case B `ClassHierarchy.declaringClass("com/google/vtfx/compose/SubView2", "performHapticFeedback", "(I)Z")` returns `"android/view/View"`.

Actual result: In both cases, `classViolations` and `allowlistViolations` return `emptyList()`. In Case B, `declaringClass` returns `"com/google/vtfx/compose/Mask"` instead of `"android/view/View"` (`expected:<[android/view/View]> but was:<[com/google/vtfx/compose/Mask]>`).

View of the cause:

- For Case A, `checkClassReferences` only resolves the declaring class of a call from the bytecode instruction's `owner` (`ViewTunnel`). Because `SubView` inherits the implementation from `android/view/View` and satisfies `ViewTunnel` without emitting any method or reference in `SubView.class`, neither `SubView`'s `ClassReferences` nor the `INVOKEINTERFACE ViewTunnel` instruction connects `ViewTunnel.performHapticFeedback` to `android/view/View.performHapticFeedback`.
- For Case B, `ClassHierarchy.declaringClass` traverses `info.supertypes` (`superName` followed by `interfaces`) in a single breadth-first queue. An interface implemented directly by `SubView2` (depth 1) is therefore visited before a superclass at depth 2 (`android/view/View` via `SubView1`), contrary to both section 4.2 ("superclasses before interfaces") and JVMS §5.4.3.3.

Direction of the fix:

- Walk the entire superclass chain before checking interfaces in `ClassHierarchy.declaringClass` (matching JVMS §5.4.3.3).
- In `Boundaries`, also check methods that a project class inherits from an external superclass to satisfy a project interface (or treat any non-overridden method inherited by a project class from an external superclass to implement a superinterface as a use of that external superclass's member by the subclass).

## R2: `checkLibraryArtifact` allows unchecked `META-INF/` `.class` entries from `variantJars` and shadows duplicate class paths across inputs

- Related: [BoundaryTasks.kt](../../build-logic/src/main/kotlin/BoundaryTasks.kt) (`CheckClassReferencesTask.check`, `CheckLibraryArtifactTask.check`, `ClassPath.find`)
- Contract: section 6 of [boundaries.md](../design/boundaries.md) and [decisions/0088](../decisions/0088-build-boundaries-and-tests.md):
  - Section 6: `checkLibraryArtifact` fails if it finds "A class file that is not, byte for byte, one of the classes that `checkClassReferences` reads (section 4.1; for an AAR, those of the release variant), wherever it is in the artifact, including under `META-INF/`."
- Reproduction tests: `r2a_checkLibraryArtifactAllowsUncheckedMetaInfClassFromVariantJar`, `r2b_duplicateClassPathAcrossInputsUsesDifferentClassInHierarchyAndArtifact`

Steps:

1. Case A (`r2a_checkLibraryArtifactAllowsUncheckedMetaInfClassFromVariantJar`): Place `META-INF/versions/21/com/google/vtfx/compose/Unchecked.class` (which calls `java/lang/System.exit:(I)V`, a restricted member) inside a jar in `variantJars` and inside `classes.jar` of `library.aar`. Run both `CheckClassReferencesTask` and `CheckLibraryArtifactTask`.
2. Case B (`r2b_duplicateClassPathAcrossInputsUsesDifferentClassInHierarchyAndArtifact`): Pass two directories (`dir1` and `dir2`) in `classDirectories` to both tasks. `dir1` has `com/google/vtfx/core/Sub.class` extending `java/lang/RuntimeException` and overriding `printStackTrace()V` itself. `dir2` (and `vtfx-core.jar`) has `com/google/vtfx/core/Sub.class` extending `java/lang/RuntimeException` without overriding `printStackTrace()V`, and `Caller.class` calling `Sub.printStackTrace()V` (which is not in `vtfx-core`'s `java-base` allowlist). Run both `CheckClassReferencesTask` and `CheckLibraryArtifactTask`.

Expected result: In both cases, at least one of `CheckClassReferencesTask` or `CheckLibraryArtifactTask` throws `GradleException`.

Actual result: In both cases, both `CheckClassReferencesTask` and `CheckLibraryArtifactTask` succeed without error.

View of the cause:

- In Case A, `CheckClassReferencesTask` filters `variantJars` entries with `it.name.endsWith(".class") && !it.name.startsWith("META-INF/")` (`BoundaryTasks.kt:192`), skipping `.class` files under `META-INF/`. However, `CheckLibraryArtifactTask` filters `variantJars` entries with `!it.isDirectory && it.name.endsWith(".class")` (`BoundaryTasks.kt:250`) without excluding `META-INF/`, so it treats those unchecked `META-INF/` classes as checked and allows them in the AAR.
- In Case B, `ClassPath.find` in `CheckClassReferencesTask` resolves the first matching entry (`dir1`), so `ClassHierarchy` sees `dir1`'s `Sub.class` (which declares `printStackTrace()V` internally), whereas `CheckLibraryArtifactTask` builds `checkedClasses` with `.toMap()` (`BoundaryTasks.kt:258`), which silently overwrites `dir1`'s entry with `dir2`'s entry.

Direction of the fix:

- Collect the checked classes with a single shared helper used by both `CheckClassReferencesTask` and `CheckLibraryArtifactTask` (excluding `META-INF/` `.class` files from `checkedClasses` or checking them consistently), and fail if duplicate relative `.class` paths exist across the input directories and jars (or if a `.class` file's relative path does not match its internal class name + `.class`).

## R3: `config/protected-paths.toml` omits scripts and tests that `scripts/check.sh` executes

- Related: [protected-paths.toml](../../config/protected-paths.toml), [check.sh](../../scripts/check.sh), [report_protected_changes.py](../../scripts/report_protected_changes.py)
- Contract: section 5 of [architecture.md](../design/architecture.md), section 9 of [testing.md](../design/testing.md), and [decisions/0091](../decisions/0091-security-review-and-process.md): files that define or run the gate and CI verification are listed under `security_review` in `config/protected-paths.toml` so that changes to them are flagged for human security review.
- Reproduction test: `r3_protectedPathsCoverAllScriptsExecutedByCheckSh`

Steps:

1. Read `config/protected-paths.toml` and match `scripts/check_kdoc.py`, `scripts/check_doc_links.py`, `scripts/generate_unicode_tables.py`, and `scripts/tests/test_check_kdoc.py` (all of which `scripts/check.sh` executes directly or via `python3 -I -m unittest discover -s scripts/tests`) against the `security_review` patterns.

Expected result: Each path matches a pattern in `security_review.paths`.

Actual result: None of the four paths match (`UNPROTECTED:scripts/check_kdoc.py,scripts/check_doc_links.py,scripts/generate_unicode_tables.py,scripts/tests/test_check_kdoc.py`).

View of the cause: `config/protected-paths.toml` lists only `scripts/check.sh`, `scripts/report_protected_changes.py`, and `scripts/tests/test_report_protected_changes.py`, omitting the other scripts in `scripts/` and the test files discovered and executed by `unittest discover -s scripts/tests` during `scripts/check.sh`. Modifying any of those files or adding a new `scripts/tests/test_*.py` executes arbitrary Python code in CI and local gate runs without being reported by `report_protected_changes.py`.

Direction of the fix: Protect `scripts/**` under `security_review` in `config/protected-paths.toml`.

## R4: `TerminalSession` delivers `onStickyModifiersChanged` and `onTitleChanged` on the wrong thread when another thread calls `feed` concurrently

- Related: [TerminalSession.kt](../../vtfx-session/src/main/kotlin/com/google/vtfx/session/TerminalSession.kt) (`input`, `feed`, `LatestValueNotifier`)
- Contract: section 2.5 of [session-and-threads.md](../design/session-and-threads.md) and the KDoc of `TerminalSessionListener`:
  - Section 2.5: "Bells and titles come on the thread that called `feed`, once per `feed` (with the last title the output set); resizes on the thread that called `resize`; sticky modifiers on the thread that changed them."
  - `TerminalSessionListener.onStickyModifiersChanged`: "Called on the thread that changed them."
- Reproduction tests: `r4a_onStickyModifiersChangedArrivesOnTheThreadThatChangedThem`, `r4b_onTitleChangedArrivesOnTheFeedThreadThatSetTheTitle`

Steps:

1. Case A (`r4a_onStickyModifiersChangedArrivesOnTheThreadThatChangedThem`): Commit `"a"`, arm sticky Ctrl (`session.stickyModifiers = KeyModifiers.of(ctrl = true)`), and press `'c'` on the UI thread (`session.edit { it.onKeyDown(KeyStroke.Character('c'.code)) }`), which consumes sticky Ctrl and invokes `observer.onRestartInput()` before `stickyModifiersNotifier.tell()`. While `onRestartInput()` is running on the UI thread, call `session.feed("\r\n".toByteArray())` on a background reader thread.
2. Case B (`r4b_onTitleChangedArrivesOnTheFeedThreadThatSetTheTitle`): On Thread A, call `session.feed("\u001b]2;Title\u0007\u0007".toByteArray())`, which sets the title and rings the bell before `titleNotifier.tell()`. While Thread A is inside `listener.onBell()`, call `session.feed("plain".toByteArray())` (which sets no title) on Thread B.

Expected result: `onStickyModifiersChanged` is called on the UI thread in Case A, and `onTitleChanged` is called on Thread A in Case B.

Actual result: `onStickyModifiersChanged` is called on the background `feed` thread in Case A (`expected:<Thread[#1,Test worker,5,main]> but was:<Thread[#116,background-feeder,5,]>`), and `onTitleChanged` is called on Thread B in Case B (`expected:<Thread[#1,Test worker,5,main]> but was:<Thread[#117,plain-feeder,5,]>`).

View of the cause: Unlike `resize()`, which calls `if (report) sizeNotifier.tell()` (`TerminalSession.kt:606`) only when it recorded a new size, `input()` calls `stickyModifiersNotifier.tell()` unconditionally (`TerminalSession.kt:428`) and `feed()` calls `titleNotifier.tell()` unconditionally (`TerminalSession.kt:509`). Because `feed()` calls `if (moved) input {}` (`TerminalSession.kt:502`) whenever output moves the cursor to another line, a background `feed()` call that did not change sticky modifiers invokes `stickyModifiersNotifier.tell()` and drains a notification recorded by the UI thread. Similarly, a `feed()` call with `titleSet == null` drains a pending title notification recorded by another thread.

Direction of the fix: Call `stickyModifiersNotifier.tell()` in `input()` only when that `input()` call changed `stickyModifiersState`, and call `titleNotifier.tell()` in `feed()` only when `titleSet != null` (matching `if (report) sizeNotifier.tell()` in `resize()`).

## R5: `TerminalDisplay.detach` leaves `invalidationPending` and `synchronizationTimeoutPending` set, freezing the display if re-attached

- Related: [TerminalDisplay.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalDisplay.kt) (`detach`, `invalidate`, `onOutput`)
- Contract: [TerminalDisplay.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalDisplay.kt) (`attach` and `detach` KDoc) and [Terminal.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/Terminal.kt) (`DisposableEffect(display)`): `detach` drops what waits for the main thread so that the display stops receiving notifications while detached and resumes when `attach` is called again.
- Reproduction test: `r5_reattachingDisplayAfterDetachWithPendingBackgroundOutputStillUpdatesVersion`

Steps:

1. Attach a `TerminalDisplay` (`display.attach()`).
2. From a background thread, call `session.feed("first".toByteArray())`, which sets `invalidationPending` to `true` and posts the invalidation callback to `mainHandler`.
3. Before the main looper runs that callback, call `display.detach()` and then `display.attach()` on the main thread.
4. Feed new output (`session.feed("second".toByteArray())`) and idle the main looper.

Expected result: `display.observeVersion()` increments by 1 after the new output is fed.

Actual result: `display.observeVersion()` remains unchanged forever (`expected:<1> but was:<0>`).

View of the cause: `detach()` calls `mainHandler.removeCallbacksAndMessages(null)` and resets `linesToFollow = -1L` and `restartPending.set(false)`, but does not reset `invalidationPending.set(false)` or `synchronizationTimeoutPending.set(false)` (or `pendingSize = null`). Removing the queued callbacks from `mainHandler` prevents the Runnable that resets `invalidationPending` to `false` from ever running, so `invalidationPending.compareAndSet(false, true)` returns `false` on every subsequent `invalidate()`.

Direction of the fix: Reset `invalidationPending.set(false)`, `synchronizationTimeoutPending.set(false)`, and `pendingSize = null` in `TerminalDisplay.detach()`, and guard against callbacks posted by a concurrent `SessionObserver` notification that was already in flight when `removeObserver` ran.

## R6: `KotlinSignatures` includes declarations in `@file:InternalVtfxApi` file facades and `@InternalVtfxApi` typealiases

- Related: [KotlinSignatures.kt](../../build-logic/src/main/kotlin/KotlinSignatures.kt) (`facadeSection`, `typeAlias`)
- Contract: section 9 of [testing.md](../design/testing.md) and the KDoc of `KotlinSignatures` (`KotlinSignatures.kt:75-76`): "Declarations that are not public or protected, or that carry one of the excluded annotations (or are nested in a class that does), are left out, like in the ABI dump."
- Reproduction test: `r6_kotlinSignaturesExcludesInternalVtfxApiFileFacade`

Steps:

1. Create a compiled Kotlin file facade (`InternalFacadeKt.class`) annotated with `@file:InternalVtfxApi` containing a public top-level function `internalTopLevel()`, and pass it to `KotlinSignatures.dump` with `excludedAnnotations = setOf("com.google.vtfx.core.InternalVtfxApi")`.

Expected result: `result.text` is `""` (empty), matching KGP's ABI dump (`AbiFilters.EXCLUDE_ANNOTATED_WITH`).

Actual result: `result.text` contains `file com.google.vtfx.core.InternalFacadeKt {\n    public final fun internalTopLevel(): kotlin.Unit\n}\n\n`.

View of the cause: `isIncluded(kmClass)` checks `parsed.annotations.any { it in excluded }`, but `facadeSection` (used for `KotlinClassMetadata.FileFacade` and `KotlinClassMetadata.MultiFileClassPart`) does not check `parsed.annotations`. In addition, `typeAlias(t: KmTypeAlias)` does not check `t.annotations` (from `kotlin.metadata.jvm.annotations`) against `excluded`.

Direction of the fix: Check `if (parsed.annotations.any { it in excluded }) return null` in `facadeSection`, and check `t.annotations` against `excludedAnnotations` (and `@Deprecated`) in `typeAlias`.

## Unverified concerns

- `DumpAbiTask` and `DumpKotlinSignaturesTask` read only the output directory of the Kotlin compile task (`compileReleaseKotlin` / `compileKotlin`), whereas `CheckClassReferencesTask` and `CheckLibraryArtifactTask` also include the Java compile task's destination directory (`AbiDump.kt:65-69, 145-149` vs `vtfx.boundaries.gradle.kts:156-162`). None of the modules currently contain `.java` sources, so this cannot produce an ABI diff in the current tree.
- `TerminalFont.Sanitized(val families: List<SanitizedFamily>)` (`TerminalFont.kt:101`) is annotated `@Immutable` and validates `require(families.size in 1..MAX_FAMILIES)` in `init`, but stores the caller-supplied `List` reference without copying (`families.toList()`). If a caller passes a `MutableList` and mutates it after construction, `families` can violate the `1..MAX_FAMILIES` invariant when `SanitizedTypefaces.regular(font.families)` is later called.

## Verification results at review time

- `scripts/check.sh`: passed
- Reproduction tests (`./gradlew --console=plain --no-configuration-cache -I docs/reviews/repro-2026-10-22-build-cross/review.init.gradle :vtfx-compose:reviewRepro`): 9 tests completed, 9 failed
- Verification not run: emulator end-to-end tests (`scripts/e2e_ime.py`) and long fuzzing (`scripts/fuzz.sh`)
