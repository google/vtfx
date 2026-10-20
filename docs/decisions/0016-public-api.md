# Scope of the public API and the compatibility policy

- Status: Approved (2026-10-13, approved by a human in the final review); the opt-in through compiler settings superseded by [0029](0029-import-without-gradle.md), and the public constructor of `HostWriter` by [0027](0027-session-takes-connection.md)
- Date: 2026-10-12
- Related: Requirement 6.5 (public API) and section 8 (the compatibility policy of the public API, and when to start guaranteeing stability), [0007](0007-compose-session.md), [0036](0036-adding-parameters.md), [0037](0037-api-shape-before-1.0.md), [0043](0043-url-open-results.md)

## Background

`vtfx-compose` exposes other library modules with `api` (because apps use `HostConnection`, `KeyStroke`, `TerminalSnapshot`, and so on; section 2 of [architecture.md](../design/architecture.md)). As a result, declarations used only between modules (the terminal model's `Terminal`, the IME editing model's `ImeEditor`, the gate's `TerminalGate`, and so on) were also visible to apps. Kotlin's `internal` cannot cross modules, so these have to be `public`.

Requirement 6.5 asks for keeping the public API to the minimum needed, mechanically detecting changes to the public API (including binary compatibility), and defining the compatibility policy and when to start guaranteeing stability.

## Decision

### Scope of the public API

- The public API is the `public` declarations of the nine library modules that are not marked `@InternalVtfxApi`.
- `@InternalVtfxApi` (`vtfx-core`) is an opt-in marker at error level. The vtfx modules opt in, and apps are assumed not to. How the modules opt in is decided in [0029](0029-import-without-gradle.md) (each file that uses a marked declaration). The sample app compiles without opting in, which confirms that vtfx can be embedded with the public API alone.
- Marked are the declarations shared only between modules: for example `Terminal`, `TerminalEvent`, and `TerminalEventListener` (core), `ImeEditor` and `HostInput` (input), `TerminalGate` and `HostWriter` (gate). The current set is what the sources mark. Nested declarations are marked too, because the compatibility check does not exclude nested classes based only on the marker of the outer class.
- `HostWriter` was public to apps only through its constructor; since [0027](0027-session-takes-connection.md), `TerminalSession` creates it, and the whole class is marked.
- `TerminalSnapshot` and its parts (`LineSnapshot`, `CellStyle`, `TerminalColor`, `TerminalModes`, and so on) are included in the public API as the result of `TerminalSession.snapshot()`.

### Compatibility policy

- Versions follow Semantic Versioning.
- Before 1.0 (0.x), compatibility is not guaranteed. However, changes to the public API are always detected by the check and appear in commits as differences in the dump. For changes that break compatibility, write the reason in the commit message.
- From 1.0 on, changes that break compatibility are made only in major versions. An API to be removed is first replaced with `@Deprecated` (WARNING) pointing to its replacement, becomes ERROR after at least one minor version, and is removed in the next major version. A parameter added from 1.0 on keeps the previous signature as a hidden overload ([0036](0036-adding-parameters.md)).
- The closed types, which take new entries or subtypes only in a major version, are `UnderlineStyle` and `CursorShape` ([0037](0037-api-shape-before-1.0.md)), and `OpenUrlResult` with its subtype `UrlOpenerResult` ([0043](0043-url-open-results.md)). The other enums and sealed types that vtfx returns are shaped so that apps cannot match them exhaustively; those that apps give to vtfx may grow in a minor version ([0037](0037-api-shape-before-1.0.md)).
- `@InternalVtfxApi` declarations may change without notice in any version.
- When to start guaranteeing stability (1.0) is decided by humans once the publishing (Maven) policy is decided ("Publishing" in [review-queue.md](../review-queue.md)).

### Compatibility check

- The ABI of the public API of each library module is dumped into `<module>/api/<module>.api` and committed, and `check` compares it with the current code. It fails if there is a difference. After an intended change, update the dump with `./gradlew updateKotlinAbi`, review the diff, and commit.
- JVM modules use the Kotlin Gradle Plugin's ABI validation (`abiValidation`, an experimental API).
- For the Android modules (`vtfx-compose`, `vtfx-gate-android`, `vtfx-font-gate-android`), with AGP 9's built-in Kotlin, KGP's ABI validation cannot find the compilation and cannot produce a dump. So `DumpAbiTask` in `build-logic` dumps the release Kotlin classes with the ABI feature of the Kotlin Build Tools API, which KGP uses internally. The format and the task names (`updateKotlinAbi`, `checkKotlinAbi`) are the same as for JVM modules. The implementation of the Build Tools API (`org.jetbrains.kotlin:kotlin-build-tools-impl`) is pinned to the same version as Kotlin; it is the same artifact KGP uses for compilation.
- The ABI dump holds JVM descriptors only, which miss changes that break apps: removing a parameter's default value, the value of a `const val`, type arguments, nullability, parameter names, `sealed`, and `fun interface`. So each library module also dumps the Kotlin signatures of its public API into `<module>/api/<module>.signatures`, read from the Kotlin metadata of the compiled classes (`KotlinSignatures` in `build-logic`). A parameter with a default value shows as `= ...` (the value is in the function's body, so it can change), and a hidden overload as `@Deprecated(HIDDEN)`. `updateKotlinAbi` writes it, and `checkKotlinAbi` and `check` compare it. The dump fails on a public `const val`, which [0037](0037-api-shape-before-1.0.md) rules out.
- Not seen by either dump: changes to the body of public `inline` functions (there are none), Compose's stability inference, and the Kotlin version that apps need. The modules are compiled without `languageVersion` or `apiVersion`, so upgrading Kotlin raises the Kotlin compiler that an app needs to read vtfx's metadata, with no difference in `api/`. The methods with default bodies of `TerminalSessionListener` are JVM default methods (the default `-jvm-default` mode of Kotlin 2.2 and later), so a Kotlin compiler older than 2.2 that compiles the sources ([0029](0029-import-without-gradle.md)) gives them another ABI.

### API reference

- The HTML API reference of the library modules is generated with Dokka (decided by a human). Each module applies `vtfx.dokka` from `build-logic`, and `vtfx.api-reference` at the root aggregates them (`./gradlew :dokkaGenerate`, output in `build/dokka/html`). Generation is not part of `check`; it is for publishing.
- The footer of the reference carries the same notice as the license headers of the sources, and the version comes from `gradle.properties` (the footer decided by a human). Each declaration links to its source in https://github.com/google/vtfx at the tag `v<version>`, so a reference of a version keeps pointing at the sources of that version.
- `@InternalVtfxApi` declarations are excluded from the reference with KDoc's `@suppress`, because Dokka has no setting to exclude declarations by annotation. `scripts/check_kdoc.py` checks for missing ones.
- Dokka renders links that it resolves to pages it does not generate as broken links without a warning (links to `@suppress`ed declarations, and unqualified links to properties declared in a primary constructor), so `scripts/check_api_reference.py` checks the generated reference for broken links. Dokka also shows neither mentions in the class description nor `@param` as the description of a property declared in the primary constructor; `scripts/check_kdoc.py` checks that each has its own KDoc or `@property`. The rules are in the docstrings of the two scripts.
- What KDoc in the reference may contain is decided in [0022](0022-api-reference-content.md).

## Alternatives considered

- **Merge into one module and use `internal`**: It would break the design that mechanically checks the security boundary (only the gate can send to the host) by modules ([architecture.md](../design/architecture.md)).
- **Allow `internal` between modules with `-Xfriend-paths`**: An unofficial compiler setting, and its handling across Gradle projects is not guaranteed.
- **Make `vtfx-compose` depend with `implementation` and rewrap only the needed types in compose**: Types such as `KeyStroke` and `TerminalSnapshot` would be duplicated in compose, increasing conversion effort and the risk of inconsistencies.
- **kotlinx binary-compatibility-validator**: It is being replaced by KGP's ABI validation. Its Android support with AGP 9's built-in Kotlin could not be confirmed either.
- **Stop using AGP's built-in Kotlin in Android modules and go back to the kotlin-android plugin**: It would change how all Android modules compile just for ABI validation. Future versions of AGP will not offer the choice.
- **metalava**: The standard for checking Android public APIs, but its dependencies and build configuration are large.
- **Keep internal declarations in the reference and show the marker in the signature with `@MustBeDocumented`**: No maintenance needed, but internal declarations would be mixed into the reference.
- **A Dokka plugin of our own that excludes by annotation**: More modules, and Dokka's plugin API is not stable.

## Impact of reversing

- To change the scope, add or remove `@InternalVtfxApi` and run `updateKotlinAbi`.
- Once KGP supports AGP's built-in Kotlin, drop `DumpAbiTask` and `CheckAbiTask` in `vtfx.android-library` and align with the same `abiValidation` as JVM. When upgrading KGP or the Build Tools API, if the dump format changes, update it with `updateKotlinAbi`.
