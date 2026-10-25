# The shape of the public API before 1.0

- Status: Approved (2026-10-16, decided by a human)
- Date: 2026-10-16
- Related: Requirement 6.5, [0016](0016-public-api.md), [0085](0085-compose-interaction.md), [0036](0036-adding-parameters.md)

## Background

From 1.0 on, changes that break apps compiled against an earlier version need a major version ([0016](0016-public-api.md)). A review of binary compatibility found three parts of the public API whose likely changes would break such apps, and which are cheaper to change before 1.0, while compatibility is not guaranteed:

- **Data classes among what vtfx returns**: `TerminalModes`, `CursorState`, `Hyperlink`, `TerminalLink.Explicit` and `Matched`, and `TerminalLinkClick` are data classes with public constructors, which apps only read. A new property (a new mode; the position of a link click, which [0085](0085-compose-interaction.md) foresees) changes the signatures of the constructor and `copy`. Hidden overloads ([0036](0036-adding-parameters.md)) can keep both (checked with Kotlin 2.4.21), but `componentN` still fixes the order of the properties, `equals` and `toString` cover every property, and apps can build any state. An `internal` constructor alone leaves `copy` public, which Kotlin 2.4 warns about (an error with `allWarningsAsErrors`); `@ConsistentCopyVisibility` hides `copy` but leaves `componentN` public.
- **Public `const val`s**: their values are copied into apps at compile time, and the ABI check does not see a change of value. `TerminalLimits.MAX_DIMENSION`, `MAX_SCROLLBACK_LINES`, and `MAX_STRING_LENGTH` bound arguments that vtfx checks with `require`, so lowering one makes apps compiled earlier throw `IllegalArgumentException` (reproduced with `resize(TerminalLimits.MAX_DIMENSION, 24)`). `CharWidth.UNICODE_VERSION` reports the version that an app was compiled with, not that of the tables it runs with. `TerminalSession.DEFAULT_COLUMNS` and `DEFAULT_ROWS` and `GatePolicy.DEFAULT_MAX_CLIPBOARD_WRITE_LENGTH` would only show stale defaults. vtfx-compose also copies `MAX_DIMENSION`.
- **Enums and sealed types that vtfx returns**: an exhaustive `when` compiled earlier throws `NoWhenBranchMatchedException` on an entry or subtype added later. Kotlin has no marker for a non-exhaustive type, like Rust's `#[non_exhaustive]` or Swift's `@unknown default`. Libraries either treat a type as closed and add to it only in a major version (Compose's `LayoutDirection`), make it a value class with constants in its companion, on which `when` needs `else` (Compose's `PointerType` and `TextAlign`), or give it an entry for unknown values. The types that apps give to vtfx (`TerminalKey`, `KeyStroke`, `TerminalFont`, `AmbiguousWidth`) can grow without breaking apps. Of those that vtfx returns, `TerminalLink` (new kinds of links) and `MouseEncoding` (SGR-Pixels, mode 1016) are likely to grow; `UnderlineStyle` (SGR 4:0 to 4:5), `CursorShape` (DECSCUSR), and `MouseTracking` are nearly closed. Only vtfx-compose reads `TerminalModes.mouseTracking` and `mouseEncoding`; neither the sample app nor [usage.md](../usage.md) does.

## Decision

These are made before 1.0.

- **No data classes among what vtfx returns.** `TerminalModes`, `CursorState`, `Hyperlink`, `TerminalLink.Explicit` and `Matched`, and `TerminalLinkClick` become ordinary classes with internal constructors and hand-written `equals`, `hashCode`, and `toString`, like `TerminalSnapshot`. Apps read their properties, which can grow compatibly; they neither build nor destructure them. The tests of other modules make `TerminalModes` and `Hyperlink` through functions of vtfx-core's test fixtures, which are compiled as a friend of vtfx-core.
- **No public `const val`s.** Every public constant becomes a plain `val`, read through its getter at run time, so that a later version can change the value: `TerminalLimits.MAX_DIMENSION`, `MAX_SCROLLBACK_LINES`, `MAX_STRING_LENGTH`, `CharWidth.UNICODE_VERSION`, `TerminalSession.DEFAULT_COLUMNS` and `DEFAULT_ROWS`, and `GatePolicy.DEFAULT_MAX_CLIPBOARD_WRITE_LENGTH`. A single rule is simpler than deciding for each constant whether its value may change, and a check can enforce it. Constants that stay inside one module may still be `const val`s; vtfx-compose reads `MAX_DIMENSION` of vtfx-core through the getter like apps, since the two artifacts can be of different versions.
- **Enums and sealed types are decided per type.**
  - `UnderlineStyle` and `CursorShape` are closed: an entry is added only in a major version. 0016 lists the closed types.
  - `TerminalLink` takes a shape on which an exhaustive `when` is not possible, so that apps write `else` and new kinds of links can be added in a minor version. The kinds are still made only by vtfx: it is an abstract class with an internal constructor.
  - `MouseTracking` and `MouseEncoding`, and the properties of `TerminalModes` that hold them, leave the public API (marked `@InternalVtfxApi`). If apps need it, the public API tells only whether the host asks for the mouse, as a `Boolean`; no app needs it yet.
  - The types that apps give to vtfx can take new entries and subtypes in a minor version.

## Alternatives considered

- **Keep the data classes, and keep the previous constructor and `copy` with hidden overloads (0036)**: Fewer changes now, but the order of `componentN`, the properties in `equals` and `toString`, and the public construction stay fixed.
- **Data classes with internal constructors and `@ConsistentCopyVisibility`**: Hides construction and `copy`, but leaves `componentN` and `equals` public.
- **Plain `val`s only for the values that may change (the bounds and `UNICODE_VERSION`)**: Keeps the defaults as constants, but needs a judgment for each constant, which a check cannot make.
- **Keep the `const val`s, and promise that the bounds only rise and that `UNICODE_VERSION` is the version at compile time**: No change, but it relies on every later change keeping the promise, and the ABI check does not see a change of value.
- **Treat every enum and sealed type as closed**: Simple, but `TerminalLink` and `MouseEncoding` would likely need major versions.
- **Allow additions in minor versions and ask apps in the documentation to write `else`**: Nothing enforces it, and apps that miss it crash.

## Impact of reversing

- Data classes: turn the classes back into data classes; their constructors and `copy` then follow 0036.
- Constants: turn the `val`s back into `const val`s, and decide again how a change of value is reviewed, since the ABI check does not see it.
- Enums and sealed types: make `TerminalLink` sealed again, or make the closed types open; return `MouseTracking` and `MouseEncoding` to the public API.
