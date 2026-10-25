# Security review by whole modules

- Status: Approved (2026-10-20, approved by a human in a review of the review queue; review by whole modules and the names `vtfx-font-gate` and `vtfx-font-gate-android` had been decided by a human on 2026-10-19)
- Date: 2026-10-19
- Related: Requirement 6.1, section 2 of [architecture.md](../design/architecture.md), section 2 of [fonts.md](../design/fonts.md), [0087](0087-public-api.md), [0086](0086-fonts.md), `config/protected-paths.toml`, `config/boundaries.toml`

## Background

`config/protected-paths.toml` put two single files under security review: `CanonicalVerifier.kt` in `vtfx-font` and `SanitizedTypefaces.kt` in `vtfx-compose` ([0086](0086-fonts.md)). Every other entry was a whole module or build configuration.

A review of `vtfx-font` from this point of view found that what the verifier's guarantee relies on was not all in those files:

- `SanitizedFont.kt` holds the guarantee that a `SanitizedFont` passed the verifier: its private constructor, the one function that calls it, the copy that keeps the caller from changing bytes after the check, and the read-only buffer. A factory added there would skip the verifier without any review.
- `InvalidFontException.kt` (the functions that throw on rejection) and `FontLimits.kt` (the ranges of the limits that the verifier checks against) were shared with the readers and the writer, so a change made for the readers changed the verifier unreviewed.

A boundary drawn around files has to be redrawn by hand whenever code moves between them; a module boundary is enforced by the compiler (`internal`, `private`) and by `config/boundaries.toml`.

## Decision

- **Security review covers whole modules.** `config/protected-paths.toml` lists code only as `<module>/**`, besides build configuration and scripts; no single source files. Code that needs review, and what it relies on, lives in a module of its own.
- **The modules under review are named with `gate`**, as `vtfx-gate` and `vtfx-gate-android` are: a gate decides what may cross a boundary, whether to the host and the outside world or into the platform's native code.
- `vtfx-font` is split in three: `vtfx-font-gate` (JVM, no dependencies, under review) holds the verifier and the public types that its guarantee relies on (`SanitizedFont`, with `fromCanonical` only, `SanitizedFamily`, `InvalidFontException`, `FontLimits`, `WidthClass`); `vtfx-font` (JVM, not under review) keeps the readers, the CFF interpreter, the curve approximation, the writer, and `FontFile`; `vtfx-font-gate-android` (Android, under review) holds the one file that makes typefaces from `SanitizedFont`s. The modules are described in section 2 of [fonts.md](../design/fonts.md).
- **All of `vtfx-font-gate` is in a package of its own**, `com.google.vtfx.font.gate`, and `config/boundaries.toml` reserves it and its subpackages for the two font gate modules. A package split across modules would let any of them replace a reviewed class: a class that `vtfx-font` declares with the name of one in `vtfx-font-gate` compiles, and replaces the reviewed class wherever `vtfx-font` comes first on the class path, so that, for example, a copy of `SanitizedFont` whose `fromCanonical` skips the verifier would make malformed fonts pass. An Android app's build rejects the duplicate class when it merges dex files, but a JVM program that uses `vtfx-font` without a UI does not. The verifier has its own private functions that throw, and the readers theirs, so the verifier still shares no code with them.
- The same holds for `vtfx-gate` and `vtfx-gate-android`, whose classes are all in `com.google.vtfx.gate` and its subpackage `com.google.vtfx.gate.android`: `config/boundaries.toml` reserves that package for the two modules in the same way, so that no other module can replace, for example, `Replies`, which builds every reply to the host (at a human's request).
- `config/boundaries.toml` allows the font APIs in `vtfx-font-gate-android` instead of the one file of `vtfx-compose`. The module holds that one file, and the whole of it is under review. `vtfx-compose` derives the bold and italic typefaces from the regular one with `Typeface.create`, which reads no font data, so it no longer uses the font APIs.
- **The verifier's tests are in `vtfx-font-gate`**, under review with the code that they check, so that a change that weakens them is reviewed too. They take canonical fonts kept as files, since `vtfx-font-gate` cannot depend on `vtfx-font`, even in its tests (tests have no list of their own, [0088](0088-build-boundaries-and-tests.md)), and a test in `vtfx-font` checks that sanitizing still writes those files byte for byte. The fuzz test of the verifier changes those fonts and computes their checksums again, so that the changed fonts reach the checks of every table rather than fail the first checksum, as almost all raw bytes do. The tests are described in section 10 of [fonts.md](../design/fonts.md).

### Changes to the public API

Version 0.1.0 was published only as the source repository: no artifacts were uploaded and no release was tagged ([0090](0090-distribution.md)). [0087](0087-public-api.md) allows changing the API before 1.0.

- `SanitizedFont.sanitize` becomes `FontFile.sanitize`, beside `FontFile.inspect`: `vtfx-font-gate` cannot call the readers, which depend on it. It ends in `SanitizedFont.fromCanonical`, so the verifier is still the only way to a `SanitizedFont`.
- `InvalidFontException` gets a public constructor, since the readers throw it from another module. Apps have no reason to construct one, and one that they construct proves nothing.
- `SanitizedFamily` moves from `com.google.vtfx.compose` to `com.google.vtfx.font.gate` in `vtfx-font-gate`, since `vtfx-font-gate-android` takes it. It loses Compose's `@Immutable`, which no composable parameter relied on: the parameters have the type `TerminalFont`, which keeps it.
- The public types of `vtfx-font-gate` move from `com.google.vtfx.font` to `com.google.vtfx.font.gate`, so that no package is split between `vtfx-font` and `vtfx-font-gate`. Apps change their imports; the names of the types stay.

### Accepted costs

`FontFile.sanitize` goes through `fromCanonical`, which copies the bytes before checking them, so sanitizing holds one more copy of the output for a moment. `SanitizedTypefaces`, the first `InternalVtfxApi` declaration of an Android module, is listed in the ABI dump of `vtfx-font-gate-android`, whose task does not leave such declarations out. Both are described in section 11 of [fonts.md](../design/fonts.md), and fixing either is a separate change.

## Alternatives considered

- **Put the whole of `vtfx-font` under review**: one line in the configuration, but the readers, the charstring interpreter, and the curve approximation, where most changes happen, would all need security review, against the point of 0040's verifier, and the review would be spread thin.
- **Keep listing files, and add `SanitizedFont.kt`, `InvalidFontException.kt`, and `FontLimits.kt`**: the list would have to follow every refactoring by hand, and nothing would stop new code that the verifier relies on from landing in an unlisted file.
- **Other names**: `vtfx-font-canonical` or `vtfx-font-verifier` (accurate, but say nothing of review; the second is narrower than the contents), or keeping `vtfx-font` for the small module and renaming the readers `vtfx-font-sanitizer` (the fewest changes for apps, but the name does not say what is reviewed).
- **Keep the public types in `com.google.vtfx.font`, split across `vtfx-font` and `vtfx-font-gate`, so that apps keep their imports**: another module could then replace them, `SanitizedFont` included, whose `fromCanonical` is what makes the verifier the only way to a `SanitizedFont`. Guarding the split package would take either a rule in `config/boundaries.toml` against declaring each of their names elsewhere, which every new type and file in `vtfx-font-gate` would have to extend by hand, or a check of the compiled classes of every module, a new task in `build-logic`.
- **Let the tests of `vtfx-font-gate` depend on `vtfx-font`**, so that they build the fonts that they check: `config/boundaries.toml` would need a dependency of `vtfx-font-gate` on `vtfx-font`, which the boundary check could not tell apart from one of the reviewed code on the code that it reviews, since tests have no list of their own ([0088](0088-build-boundaries-and-tests.md)).
- **Keep the verifier's tests in `vtfx-font`**: they would build their fonts, but a change that weakens them would not be reviewed, and the fuzz test there passes raw bytes, almost all of which fail the first check of a checksum.
- **Make `SanitizedTypefaces.regular` public API**, so that `vtfx-font-gate-android` needs no dependency on `vtfx-core`: apps would get an API that they do not need, which the public API check would then hold.

## Impact of reversing

Merge `vtfx-font-gate` back into `vtfx-font` and `vtfx-font-gate-android` back into `vtfx-compose`; move `CanonicalVerifier` and the public types of `vtfx-font-gate` back to `com.google.vtfx.font`; restore `SanitizedFont.sanitize`, the internal constructor of `InvalidFontException`, and `SanitizedFamily` in `vtfx-compose`; list the files again in `protected-paths.toml` and `boundaries.toml`.
