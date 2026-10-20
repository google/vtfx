# Security review by whole modules

- Status: Provisional (2026-10-19, review by whole modules and the names `vtfx-font-gate` and `vtfx-font-gate-android` decided by a human; the verifier's own package, and its tests in `vtfx-font-gate`, added on 2026-10-19, and the move of the public types to that package, not yet reviewed)
- Date: 2026-10-19
- Related: Requirement 6.1, section 2 of [architecture.md](../design/architecture.md), [0016](0016-public-api.md), [0037](0037-api-shape-before-1.0.md), [0040](0040-sanitized-user-fonts.md), `config/protected-paths.toml`, `config/boundaries.toml`

## Background

`config/protected-paths.toml` put two single files under security review: `CanonicalVerifier.kt` in `vtfx-font` and `SanitizedTypefaces.kt` in `vtfx-compose` ([0040](0040-sanitized-user-fonts.md)). Every other entry was a whole module or build configuration.

A review of `vtfx-font` from this point of view found that what the verifier's guarantee relies on was not all in those files:

- `SanitizedFont.kt` holds the guarantee that a `SanitizedFont` passed the verifier: its private constructor, the one function that calls it, the copy that keeps the caller from changing bytes after the check, and the read-only buffer. A factory added there would skip the verifier without any review.
- `InvalidFontException.kt` (the functions that throw on rejection) and `FontLimits.kt` (the ranges of the limits that the verifier checks against) were shared with the readers and the writer, so a change made for the readers changed the verifier unreviewed.

A boundary drawn around files has to be redrawn by hand whenever code moves between them; a module boundary is enforced by the compiler (`internal`, `private`) and by `config/boundaries.toml`.

## Decision

- **Security review covers whole modules.** `config/protected-paths.toml` lists code only as `<module>/**`, besides build configuration and scripts; no single source files. Code that needs review, and what it relies on, lives in a module of its own.
- **The modules under review are named with `gate`**, as `vtfx-gate` and `vtfx-gate-android` are: a gate decides what may cross a boundary, whether to the host and the outside world or into the platform's native code.
- `vtfx-font` is split in three:

| Module | Kind | Contents | Review |
|---|---|---|---|
| `vtfx-font-gate` | JVM, no dependencies | `CanonicalVerifier`, `SanitizedFont` (`fromCanonical` only), `SanitizedFamily`, `InvalidFontException`, `FontLimits`, `WidthClass` | Needed |
| `vtfx-font` | JVM, depends on `vtfx-font-gate` | The readers, the CFF interpreter, the curve approximation, the writer, `FontFile` | Not needed |
| `vtfx-font-gate-android` | Android, depends on `vtfx-font-gate` (and `vtfx-core`, at compile time only, for `InternalVtfxApi`) | `SanitizedTypefaces.regular`, which makes the regular typeface of a list of `SanitizedFamily`s | Needed |

- All of `vtfx-font-gate` is in a package of its own, `com.google.vtfx.font.gate`: the verifier (`CanonicalVerifier`, with its private classes and top-level declarations) and the public types (`SanitizedFont`, `SanitizedFamily`, `InvalidFontException`, `FontLimits`, `WidthClass`). `config/boundaries.toml` allows declaring that package and its subpackages only in `vtfx-font-gate/**` and `vtfx-font-gate-android/**` (whose package is `com.google.vtfx.font.gate.android`), so no other module can put a class there. A package split across modules would let any of them replace a reviewed class: a class that `vtfx-font` declares with the name of one in `vtfx-font-gate` compiles, and replaces the reviewed class wherever `vtfx-font` comes first on the class path. Tried on 2026-10-19 while the package was split: a `vtfx-font` copy of `CanonicalVerifier` that accepted everything, and a `vtfx-font` copy of `SanitizedFont` whose `fromCanonical` did not call the verifier, each made `fromCanonical` in `vtfx-font`'s own tests accept malformed fonts, and `check_boundaries.py` passed. An Android app's build rejects the two classes when it merges dex files, but a JVM program that uses `vtfx-font` without a UI does not. The verifier has its own private functions that throw, and the readers theirs (`Failures.kt`), so the verifier still shares no code with them.
- The same holds for `vtfx-gate` and `vtfx-gate-android`, whose classes are all in `com.google.vtfx.gate` and its subpackage `com.google.vtfx.gate.android`: `config/boundaries.toml` reserves that package for the two modules in the same way, so that no other module can replace, for example, `Replies`, which builds every reply to the host (2026-10-20, at a human's request).
- `config/boundaries.toml` allows the font APIs in `vtfx-font-gate-android/**` instead of the one file of `vtfx-compose`. The module holds that one file, and the whole of it is under review.
- `vtfx-compose` derives the bold and italic typefaces from the regular one with `Typeface.create`, which reads no font data, so it no longer uses the font APIs.

### Changes to the public API

Version 0.1.0 was published only as the source repository: no artifacts were uploaded and no release was tagged ([0018](0018-distribution.md)). [0037](0037-api-shape-before-1.0.md) allows changing the API before 1.0.

- `SanitizedFont.sanitize` becomes `FontFile.sanitize`, beside `FontFile.inspect`: `vtfx-font-gate` cannot call the readers, which depend on it. It ends in `SanitizedFont.fromCanonical`, so the verifier is still the only way to a `SanitizedFont`.
- `InvalidFontException` gets a public constructor, since the readers throw it from another module. Apps have no reason to construct one, and one that they construct proves nothing.
- `SanitizedFamily` moves from `com.google.vtfx.compose` to `com.google.vtfx.font.gate` in `vtfx-font-gate`, since `vtfx-font-gate-android` takes it. It loses Compose's `@Immutable`, which no composable parameter relied on: the parameters have the type `TerminalFont`, which keeps it.
- The public types of `vtfx-font-gate` (`SanitizedFont`, `SanitizedFamily`, `InvalidFontException`, `FontLimits`, `WidthClass`) move from `com.google.vtfx.font` to `com.google.vtfx.font.gate`, so that no package is split between `vtfx-font` and `vtfx-font-gate`. Apps change their imports; the names of the types stay.

### Tests

The verifier's tests are in `vtfx-font-gate`, under review with the code that they check, so that a change that weakens them is reviewed too. `CanonicalVerifierTest` reaches the verifier through `SanitizedFont.fromCanonical`, which runs only it, with canonical fonts kept as files in `vtfx-font-gate/src/test/resources/com/google/vtfx/font/gate/canonical/`, and with changes to them whose checksums the test computes again. `VerifierFuzzTest` fuzzes the verifier in the same way: its input is a list of changes to one of the fonts, after which it computes the checksums again, so that the changed fonts reach the checks of every table rather than fail the first checksum, as almost all raw bytes do.

The fonts are written by `vtfx-font`, which `vtfx-font-gate`'s tests cannot depend on: `config/boundaries.toml` would need a dependency of `vtfx-font-gate` on `vtfx-font`, since the dependencies of tests are checked against the same list as those of the main code (tests have no list of their own, [0044](0044-boundaries-in-gradle-and-classes.md); this said that `check_boundaries.py`, which 0044 replaced, did not tell the dependencies of tests apart). So `CanonicalFixturesTest` in `vtfx-font` checks that `FontFile.sanitize` still writes the files byte for byte, which also makes them a golden of the canonical form. `vtfx-font` keeps only the checks that need its readers or its writer: `VerifierCrossCheckTest`, which checks that the fonts that the verifier accepts are those that sanitizing leaves as they are, and the fuzz test.

### Known cost

`FontFile.sanitize` now goes through `fromCanonical`, which copies the bytes before checking them, so sanitizing holds one more copy of the output (up to `FontLimits.maxOutputBytes`) for a moment. Checking the bytes in the final direct buffer instead, so that the bytes checked are exactly those that native code reads, would remove the copy; it is a separate change to `vtfx-font-gate`.

`SanitizedTypefaces` is the first `InternalVtfxApi` declaration of an Android module, and the ABI dump of the Android modules (`DumpAbiTask` in `build-logic`) does not leave it out: the filters that the task passes to the Build Tools API have no effect, even one that names the class. So `vtfx-font-gate-android.api` lists it, while the Kotlin signatures, which the other check dumps, leave it out. Fixing the task is a separate change to `build-logic`.

## Alternatives considered

- **Put the whole of `vtfx-font` under review**: one line in the configuration, but the readers, the charstring interpreter, and the curve approximation, where most changes happen, would all need security review, against the point of 0040's verifier, and the review would be spread thin.
- **Keep listing files, and add `SanitizedFont.kt`, `InvalidFontException.kt`, and `FontLimits.kt`**: the list would have to follow every refactoring by hand, and nothing would stop new code that the verifier relies on from landing in an unlisted file.
- **Other names**: `vtfx-font-canonical` or `vtfx-font-verifier` (accurate, but say nothing of review; the second is narrower than the contents), or keeping `vtfx-font` for the small module and renaming the readers `vtfx-font-sanitizer` (the fewest changes for apps, but the name does not say what is reviewed).
- **Keep the public types in `com.google.vtfx.font`, split across `vtfx-font` and `vtfx-font-gate`, so that apps keep their imports**: another module could then replace them, `SanitizedFont` included, whose `fromCanonical` is what makes the verifier the only way to a `SanitizedFont`. Guarding the split package would take either a rule in `config/boundaries.toml` against declaring each of their names elsewhere, which every new type and file in `vtfx-font-gate` would have to extend by hand, or a check of the compiled classes of every module, a new task in `build-logic`.
- **Let the tests of `vtfx-font-gate` depend on `vtfx-font`**, so that they build the fonts that they check: `config/boundaries.toml` would need a dependency of `vtfx-font-gate` on `vtfx-font`, which the boundary check could not tell apart from one of the reviewed code on the code that it reviews, since tests have no list of their own ([0044](0044-boundaries-in-gradle-and-classes.md)).
- **Keep the verifier's tests in `vtfx-font`**: they would build their fonts, but a change that weakens them would not be reviewed, and the fuzz test there passes raw bytes, almost all of which fail the first check of a checksum.
- **Make `SanitizedTypefaces.regular` public API**, so that `vtfx-font-gate-android` needs no dependency on `vtfx-core`: apps would get an API that they do not need, which the public API check would then hold.

## Impact of reversing

Merge `vtfx-font-gate` back into `vtfx-font` and `vtfx-font-gate-android` back into `vtfx-compose`; move `CanonicalVerifier` and the public types of `vtfx-font-gate` back to `com.google.vtfx.font`; restore `SanitizedFont.sanitize`, the internal constructor of `InvalidFontException`, and `SanitizedFamily` in `vtfx-compose`; list the files again in `protected-paths.toml` and `boundaries.toml`.
