# Scope of the public API and the compatibility policy

- Status: Approved (2026-10-13, approved by a human in the final review)
- Date: 2026-10-12
- Related: Requirement 6.5 (public API) and section 8 (the compatibility policy of the public API, and when to start guaranteeing stability), [0007](0007-compose-session.md)

## Background

`vtfx-compose` exposes the other four modules with `api` (because apps use `HostWriter`, `KeyStroke`, and so on). As a result, declarations used only between modules (the terminal model's `Terminal`, the IME editing model's `ImeEditor`, the gate's `TerminalGate`, and so on) were also visible to apps. Kotlin's `internal` cannot cross modules, so these have to be `public`.

Requirement 6.5 asks for keeping the public API to the minimum needed, mechanically detecting changes to the public API (including binary compatibility), and defining the compatibility policy and when to start guaranteeing stability.

## Decision

### Scope of the public API

- The public API is the `public` declarations of the six library modules that are not marked `@InternalVtfxApi`.
- `@InternalVtfxApi` (`vtfx-core`) is an opt-in marker at error level. The vtfx modules opt in through the compiler settings (the conventions in `build-logic`), and apps are assumed not to opt in. The sample app compiles without opting in, which also confirms that it can be embedded with the public API alone.
  - 2026-10-14: [0029](0029-import-without-gradle.md) moved the opt-in from the compiler settings to each file that uses the marked declarations (`@file:OptIn(InternalVtfxApi::class)`).
- Marked: `Terminal`, `TerminalEvent`, `TerminalQuery`, `ColorTarget`, `ModeState`, `TerminalEventListener` (core), `ImeEditor`, `HostInput` (input), `TerminalGate`, `ReportedColors`, `MouseButton`, `MouseAction`, `MouseReport`, and the methods of `HostWriter` starting with `send` (gate). Nested classes are marked too (because the compatibility check does not exclude nested classes based only on the marker of the outer class).
- To apps, `HostWriter` is "the thing that wraps `HostConnection` and is passed to `TerminalSession`", and only its constructor is public API. Apps hold the connection themselves, so they do not need to send through `HostWriter`.
  - 2026-10-14: [0027](0027-session-takes-connection.md) made `TerminalSession` create the `HostWriter`, and the whole `HostWriter` class was marked.
- `TerminalSnapshot` and its parts (`LineSnapshot`, `CellStyle`, `TerminalColor`, `TerminalModes`, and so on) are included in the public API as the result of `TerminalSession.snapshot()`.

### Compatibility policy

- Versions follow Semantic Versioning.
- Before 1.0 (0.x), compatibility is not guaranteed. However, changes to the public API are always detected by the check and appear in commits as differences in the dump. For changes that break compatibility, write the reason in the commit message.
- From 1.0 on, changes that break compatibility are made only in major versions. An API to be removed is first replaced with `@Deprecated` (WARNING) pointing to its replacement, becomes ERROR after at least one minor version, and is removed in the next major version.
  - 2026-10-16: [0036](0036-adding-parameters.md) keeps the previous signature as a hidden overload when a parameter is added from 1.0 on.
  - 2026-10-16: [0037](0037-api-shape-before-1.0.md) removes data classes and public `const val`s from what the public API returns and exposes, and lists the closed enums: `UnderlineStyle` and `CursorShape` take new entries only in a major version. The other enums and sealed types that vtfx returns are shaped so that apps cannot match them exhaustively; those that apps give to vtfx may grow in a minor version.
  - 2026-10-19: [0043](0043-url-open-results.md) adds the closed sealed type `OpenUrlResult` (with its subtype `UrlOpenerResult`), which `openUrl` returns and `UrlOpener` implementations return a part of: a new kind of result is added only in a major version.
- `@InternalVtfxApi` declarations may change without notice in any version.
- When to start guaranteeing stability (1.0) is decided by humans once the publishing (Maven) policy is decided ("Publishing" in [review-queue.md](../review-queue.md)).

### Compatibility check

- The ABI of the public API of each library module is dumped into `api/<module>.api` and committed, and `check` compares it with the current code. It fails if there is a difference. After an intended change, update the dump with `./gradlew updateKotlinAbi`, review the diff, and commit.
- JVM modules use the Kotlin Gradle Plugin's ABI validation (`abiValidation`, an experimental API).
- For Android modules (`vtfx-compose`, `vtfx-gate-android`), with AGP 9's built-in Kotlin, KGP's ABI validation cannot find the compilation and cannot produce a dump. So `DumpAbiTask` in `build-logic` dumps the release Kotlin classes with the ABI feature of the Kotlin Build Tools API, which KGP uses internally. The format is the same as for JVM modules. The task names (`updateKotlinAbi`, `checkKotlinAbi`) are also the same.
- The implementation of the Build Tools API (`org.jetbrains.kotlin:kotlin-build-tools-impl`) is pinned to the same version as Kotlin (`kotlin` in the version catalog). It is the same artifact KGP uses for compilation.
- 2026-10-16: The ABI dump holds JVM descriptors only, so it missed changes that break apps: removing a parameter's default value, the value of a `const val`, type arguments, nullability, parameter names, `sealed`, and `fun interface` (found in a review of binary compatibility). Each library module also dumps the Kotlin signatures of its public API into `api/<module>.signatures`, read from the Kotlin metadata of the compiled classes with `kotlin-metadata-jvm` and ASM (`KotlinSignatures` in `build-logic`, tested in `build-logic`'s own tests, which `check` runs). A parameter with a default value shows as `= ...` (the value is in the function's body, so it can change), and a hidden overload ([0036](0036-adding-parameters.md)) as `@Deprecated(HIDDEN)`. `updateKotlinAbi` writes it, and `checkKotlinAbi` and `check` compare it. The dump fails on a public `const val`, which [0037](0037-api-shape-before-1.0.md) rules out. Not seen still: changes to the body of public `inline` functions (there are none) and Compose's stability inference.
  - Not checked either (found in the same review, 2026-10-16): the Kotlin version that apps need. The modules are compiled without `languageVersion` or `apiVersion`, so an app needs a Kotlin compiler that can read the metadata of the Kotlin version vtfx is built with, and upgrading Kotlin raises that minimum with no difference in `api/`; [usage.md](../usage.md) states no minimum. Also, the methods with default bodies of `TerminalSessionListener` are JVM default methods (the default `-jvm-default` mode of Kotlin 2.2 and later), so a Kotlin compiler older than 2.2 that compiles the sources ([0029](0029-import-without-gradle.md)) gives them another ABI.

### API reference

- The HTML API reference of the six library modules is generated with Dokka (2026-10-13, decided by a human). Each module applies `vtfx.dokka` from `build-logic`, and `vtfx.api-reference` at the root aggregates them. `./gradlew :dokkaGenerate` outputs to `build/dokka/html`. Generation is not part of `check` (it is for publishing).
- The footer of the reference carries the same notice as the license headers of the sources, `Copyright 2026 Google LLC. Licensed under the Apache License, Version 2.0.`, and the version comes from `gradle.properties` (2026-10-15, the footer decided by a human). Each declaration links to its source in https://github.com/google/vtfx at the tag `v<version>`, so a reference of a version keeps pointing at the sources of that version.
- Broken links in the generated reference are checked with `scripts/check_api_reference.py`, because Dokka 2.2 outputs links resolved to pages it does not generate as broken links without a warning. This happens in the following two cases.
  - Links to declarations excluded with `@suppress`
  - Unqualified links to properties declared in the primary constructor (`[scrollbackLines]`). Dokka 2.2 resolves them to the constructor parameter. Write them qualified with the class name, like `[scrollbackLines][TerminalLimits.scrollbackLines]`.
- The description of a property declared in the primary constructor is written in the property's own KDoc or in `@property` in the class's KDoc. Dokka does not show mentions in the class description (`[name]`) or `@param` as the property's description. `scripts/check_kdoc.py` checks this.
- `@InternalVtfxApi` declarations are excluded from the reference with KDoc's `@suppress`, because Dokka 2.2 has no setting to exclude declarations by annotation. Dokka also excludes the contents of excluded declarations, so `@suppress` is needed only on marked declarations whose enclosing declaration is not marked (the outermost ones). `scripts/check_kdoc.py` checks for missing ones.
- Options not taken: keeping internal declarations in the reference and showing the marker in the signature with `@MustBeDocumented` (no maintenance needed, but internal declarations would be mixed into the reference). Writing our own Dokka plugin that excludes by annotation (more modules, and Dokka's plugin API is not stable).

## Alternatives considered

- **Merge into one module and use `internal`**: It would break the design that mechanically checks the security boundary (only the gate can send to the host) by modules ([architecture.md](../design/architecture.md)).
- **Allow `internal` between modules with `-Xfriend-paths`**: An unofficial compiler setting, and its handling across Gradle projects is not guaranteed.
- **Make `vtfx-compose` depend with `implementation` and rewrap only the needed types in compose**: Types such as `KeyStroke` and `TerminalSnapshot` would be duplicated in compose, increasing conversion effort and the risk of inconsistencies.
- **kotlinx binary-compatibility-validator**: It is being replaced by KGP's ABI validation. Its Android support with AGP 9's built-in Kotlin could not be confirmed either.
- **Stop using AGP's built-in Kotlin in Android modules and go back to the kotlin-android plugin**: It would change how all Android modules compile just for ABI validation. Future versions of AGP will not offer the choice.
- **metalava**: The standard for checking Android public APIs, but its dependencies and build configuration are large.

## Impact of reversing

- To change the scope, add or remove `@InternalVtfxApi` and run `updateKotlinAbi`.
- Once KGP supports AGP's built-in Kotlin, drop `DumpAbiTask` and `CheckAbiTask` in `vtfx.android-library` and align with the same `abiValidation` as JVM. When upgrading KGP or the Build Tools API, if the dump format changes, update it with `updateKotlinAbi`.
