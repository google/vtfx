# Security review by whole modules

- Status: Provisional (2026-10-19, review by whole modules and the names `vtfx-font-gate` and `vtfx-font-gate-android` decided by a human)
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

- The package of `vtfx-font-gate` stays `com.google.vtfx.font`, so the types that apps use keep their names; the package is split across `vtfx-font` and `vtfx-font-gate`, which the JVM allows. Files with top-level functions have different names in the two modules, so that their facade classes do not clash. The verifier has its own private functions that throw, and the readers theirs (`Failures.kt`), so the verifier still shares no code with them.
- `config/boundaries.toml` allows the font APIs in `vtfx-font-gate-android/**` instead of the one file of `vtfx-compose`. The module holds that one file, and the whole of it is under review.
- `vtfx-compose` derives the bold and italic typefaces from the regular one with `Typeface.create`, which reads no font data, so it no longer uses the font APIs.

### Changes to the public API

No version of vtfx has been released yet (there are no tags), and [0037](0037-api-shape-before-1.0.md) allows changing the API before 1.0.

- `SanitizedFont.sanitize` becomes `FontFile.sanitize`, beside `FontFile.inspect`: `vtfx-font-gate` cannot call the readers, which depend on it. It ends in `SanitizedFont.fromCanonical`, so the verifier is still the only way to a `SanitizedFont`.
- `InvalidFontException` gets a public constructor, since the readers throw it from another module. Apps have no reason to construct one, and one that they construct proves nothing.
- `SanitizedFamily` moves from `com.google.vtfx.compose` to `com.google.vtfx.font` in `vtfx-font-gate`, since `vtfx-font-gate-android` takes it. It loses Compose's `@Immutable`, which no composable parameter relied on: the parameters have the type `TerminalFont`, which keeps it.

### Tests

The verifier's tests (`CanonicalVerifierTest`) and the fuzz test stay in `vtfx-font`, since the fonts that they check are written by its writer; they reach the verifier through `SanitizedFont.fromCanonical`, which runs only it. `vtfx-font-gate` has no tests of its own.

### Known cost

`FontFile.sanitize` now goes through `fromCanonical`, which copies the bytes before checking them, so sanitizing holds one more copy of the output (up to `FontLimits.maxOutputBytes`) for a moment. Checking the bytes in the final direct buffer instead, so that the bytes checked are exactly those that native code reads, would remove the copy; it is a separate change to `vtfx-font-gate`.

`SanitizedTypefaces` is the first `InternalVtfxApi` declaration of an Android module, and the ABI dump of the Android modules (`DumpAbiTask` in `build-logic`) does not leave it out: the filters that the task passes to the Build Tools API have no effect, even one that names the class. So `vtfx-font-gate-android.api` lists it, while the Kotlin signatures, which the other check dumps, leave it out. Fixing the task is a separate change to `build-logic`.

## Alternatives considered

- **Put the whole of `vtfx-font` under review**: one line in the configuration, but the readers, the charstring interpreter, and the curve approximation, where most changes happen, would all need security review, against the point of 0040's verifier, and the review would be spread thin.
- **Keep listing files, and add `SanitizedFont.kt`, `InvalidFontException.kt`, and `FontLimits.kt`**: the list would have to follow every refactoring by hand, and nothing would stop new code that the verifier relies on from landing in an unlisted file.
- **Other names**: `vtfx-font-canonical` or `vtfx-font-verifier` (accurate, but say nothing of review; the second is narrower than the contents), or keeping `vtfx-font` for the small module and renaming the readers `vtfx-font-sanitizer` (the fewest changes for apps, but the name does not say what is reviewed).
- **Make `SanitizedTypefaces.regular` public API**, so that `vtfx-font-gate-android` needs no dependency on `vtfx-core`: apps would get an API that they do not need, which the public API check would then hold.

## Impact of reversing

Merge `vtfx-font-gate` back into `vtfx-font` and `vtfx-font-gate-android` back into `vtfx-compose`; restore `SanitizedFont.sanitize`, the internal constructor of `InvalidFontException`, and `SanitizedFamily` in `vtfx-compose`; list the files again in `protected-paths.toml` and `boundaries.toml`.
