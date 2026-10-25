# Results of calls are used

- Status: Approved (2026-10-25, approved by a human)
- Date: 2026-10-24
- Related: [0029](0029-import-without-gradle.md) (sources that build without Gradle), [0023](0023-code-style.md), section 7 of [architecture.md](../design/architecture.md), section 9 of [testing.md](../design/testing.md), the conventions `vtfx.jvm-library`, `vtfx.android-library`, and `vtfx.android-application` in `build-logic`

## Background

A build system that imports the sources may check that the result of every call is used: the CheckReturnValue checks of Error Prone and of Android Lint, which Google's internal build runs on Kotlin too, report a call to a function that returns a value when the value is dropped, unless the function is marked ignorable. vtfx dropped results in about 530 places, so such a build had to turn the check off for vtfx, which also turned it off for the bugs it finds.

Kotlin has a check of its own, the unused return value checker (KEEP-412): `-Xreturn-value-checker=full` reports a dropped result of a function of the module, and of the standard library where it is marked. Both checks accept `@kotlin.IgnorableReturnValue` on a function. But the annotation, and `val _ = ...` for a dropped value, are experimental: the compiler refuses them unless it is given `-Xreturn-value-checker` and `-XXLanguage:+UnnamedLocalVariables`, and [0029](0029-import-without-gradle.md) keeps the sources free of features that need flags.

## Decision

1. The Gradle conventions compile every module, its tests and fuzz targets included, with `-Xreturn-value-checker=full`. With `allWarningsAsErrors`, a dropped result fails the build. The flag only checks: the sources compile the same without it, so [0029](0029-import-without-gradle.md) still holds.
2. The sources use neither `@IgnorableReturnValue` nor `val _`, so that a build without the flags compiles them.
3. A function whose result its callers drop returns nothing when the result carries nothing: a value that is always the same (the `ImeEditor` edits that always returned `true`), a receiver for chaining (the byte writers of `CanonicalWriter` and of the test fonts, which now take several values instead), or a result that no caller reads (`Line.combine`, `TerminalDisplay.scrollBy`).
4. A test uses a result it dropped when the result says what the test checks: the return values of `ImeEditor` and of the session's edits, a URL opener's result, an import's result.
5. Where a result is dropped on purpose, it is assigned to `val unused`, which both checks take as a use and which the CheckReturnValue checks suggest: a read that only registers a Compose observer, a key event that the IME is told was sent whatever it was, a copy with nothing to report, timing loops, and tests that only check that a call does not throw. A read for an observer that is followed by the value it guards uses `let` instead, as the code did before (`display.observeVersion().let { ... }`).
6. The Kotlin check does not see what the importing build's check also covers: calls of Java, Android, and AndroidX APIs that are neither in that build's lists of ignorable APIs nor marked. Only that build finds those.

## Alternatives considered

- **Mark ignorable functions with `@IgnorableReturnValue`**: The way both checks name an ignorable function, but the importing build then needs `-Xreturn-value-checker` on every vtfx target, the flag that [0029](0029-import-without-gradle.md) keeps the sources from needing.
- **`@Suppress` with the names of both checks**: Two names that are only strings to the compiler, on a declaration rather than the call.
- **A helper that drops its argument (`fun Any?.ignore()`)**: Hides the intent from both checks' conventions, and from readers who know `val unused`.
- **Check only the main sources**: The importing build checks the tests too.

## Impact of reversing

Remove the flag from the three conventions. The code may stay as it is: none of it depends on the flag.
