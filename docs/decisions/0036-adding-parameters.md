# Adding parameters to the public API after 1.0

- Status: Approved (2026-10-16, approved by a human)
- Date: 2026-10-16
- Related: Requirement 6.5, [0016](0016-public-api.md), [0029](0029-import-without-gradle.md), [0037](0037-api-shape-before-1.0.md)

## Background

In Kotlin, a new parameter changes the JVM signature of a function or constructor even when it has a default value. The synthetic method that applies the defaults (`$default`, or the constructor taking a bit mask and `DefaultConstructorMarker`; composables take the mask in the same method) changes as well. An app compiled against the previous version then fails with `NoSuchMethodError` unless it is recompiled. The parts of the public API most likely to grow are the parameters of `Terminal` and of the `TerminalSession` constructor, and the properties of `TerminalStyle`, `TerminalColors`, `GatePolicy`, and `TerminalLimits` (their constructors, and `copy` where there is one). The deprecation procedure of 0016 covers removing an API, not adding a parameter.

There are two ways to keep the previous signature:

- **A hand-written overload with the previous parameters**, marked `@Deprecated(level = DeprecationLevel.HIDDEN)` and delegating to the new one. This is how AndroidX and Compose libraries add parameters.
- **Kotlin's `@IntroducedAt`** (`kotlin.IntroducedAt`) on the new parameter, from which the compiler generates the overloads of the previous versions. It appeared in Kotlin 2.3 and is experimental: it needs `@OptIn(ExperimentalVersionOverloading::class)`, an opt-in at error level.

Both keep apps compiled against the previous version running, as a client compiled against the unchanged library showed with Kotlin 2.4.21. `@IntroducedAt` needs no compiler flag and also covers composables and a data class's constructor and `copy`, but it rejects default values of earlier parameters that are too complex for the generated overloads (`VERSION_OVERLOADS_TOO_COMPLEX_EXPRESSION`), such as `object : TerminalSessionListener {}` in the constructor of `TerminalSession`.

## Decision

- **Before 1.0**, parameters may be added without keeping the previous signature ([0016](0016-public-api.md)). The difference in `api/` and the reason in the commit message are enough.
- **From 1.0**, when a parameter is added to a public function, constructor, composable, or `copy`, the previous signature is kept as an overload marked `@Deprecated("...", level = DeprecationLevel.HIDDEN)` that delegates to the new one.
  - The overload keeps the default values of the previous signature. Callers that left a parameter to its default call the synthetic default method of the previous signature, which exists only if the overload has the same defaults.
  - The new parameter has a default value, so that sources calling the function keep compiling.
  - `HIDDEN` makes the overload synthetic: sources cannot call it, and the dump shows it as `public synthetic fun`. Reviewers check that a difference in `api/` that adds a parameter keeps the previous lines, as synthetic.
  - The hidden overloads are removed in the next major version, with the other deprecated APIs.
- The ABI check catches a forgotten overload: the lines of the previous signature disappear from the dump, and `checkKotlinAbi` fails.
- `@IntroducedAt` is not used while it is experimental. When it becomes stable, it may replace the hand-written overloads. The generated overloads have the same descriptors as the hand-written ones (the dump differs only in the synthetic flag, which does not affect linking), so switching does not break apps.
- Whether a new setting becomes a parameter or a property of a settings class (like `TerminalStyle`) is decided case by case; either way, the overloads above keep the previous signatures.

## Alternatives considered

- **`@IntroducedAt` now**: Less code to write for each new parameter. But it is experimental, so its name or meaning may change, and other build systems that compile the sources ([0029](0029-import-without-gradle.md)) would need Kotlin 2.3 or later and the opt-in. It also rejects some default values that the current API has.
- **`@JvmOverloads`**: Generates one overload for each prefix of the parameters, which includes the previous full signature. But the synthetic default method of the previous signature, which Kotlin callers that use default arguments call, still changes. Its overloads are meant for Java, which is out of scope (requirement 4.2).
- **Major versions for every new parameter**: Simple, but parameters are the most common kind of addition, so major versions would come often.
- **A rule to put settings that are likely to grow into a settings class rather than into the parameters of `Terminal` or `TerminalSession`**: Fewer places would need overloads, but whether a setting belongs to a parameter or a settings class depends on the setting (not taken, decided by a human on 2026-10-16).
- **Settings with mutable properties or builders**: Adding a property is then compatible without overloads. But it departs from the immutable settings of Compose and of the existing classes (`TerminalStyle`, `GatePolicy`).

## Impact of reversing

- To adopt `@IntroducedAt`, replace each hidden overload with the annotation on the parameters added after it, and check that the dump keeps the previous descriptors.
- To stop keeping previous signatures, remove this procedure, and bump the major version for each new parameter (0016).
