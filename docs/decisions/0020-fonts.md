# Restricting where terminal fonts come from

- Status: Approved (2026-10-13, decided in a discussion with a human); items 1 and 3 changed and item 4 and the "Future extension" superseded by [0040](0040-sanitized-user-fonts.md); item 3 superseded by [0044](0044-boundaries-in-gradle-and-classes.md)
- Date: 2026-10-13
- Related: Requirements 6.1 and 6.5 (customizability, safe defaults), [0016](0016-public-api.md), [0040](0040-sanitized-user-fonts.md) (fonts chosen by users, sanitized in Kotlin: the future extension below, carried out), `TerminalFont` in `vtfx-compose`, `config/boundaries.toml`

## Background

Terminal apps often want to let users choose a font. `TerminalStyle` took Compose's `FontFamily`, so apps could also pass fonts from files or downloaded fonts.

Fonts (TTF, OTF) are parsed inside the app's process by the OS's native code (HarfBuzz for shaping, FreeType for rasterization, and so on). Vulnerabilities in this parsing that let a malicious font execute arbitrary code have been found repeatedly, and have been used in real attacks (the embedded PNG of CVE-2020-15999, the structures related to variable fonts of CVE-2025-27363, and so on). The process of an SSH client holds keys and connections, so the impact of a takeover is large.

Even for a file the user chose themselves, the user cannot judge its contents. Also, in designs that remember a path in shared storage and read it again, another app that can write to the storage can later replace the file, which becomes a path for a less privileged app to bring code into the SSH app's process. The threat of physical access to the device is not handled at the font layer: if the device is unlocked, there are more direct means than fonts, and if it is locked, fonts cannot be placed.

## Decision

1. Replace `TerminalStyle.fontFamily: FontFamily` with `TerminalStyle.font: TerminalFont`. `TerminalFont` is sealed and represents only the following two.
   - `System(family)`: the name of a system font (`monospace` and so on). System fonts are part of the OS and are protected by OS updates. Unknown names become the system's default font.
   - `Resources(regular, bold, italic, boldItalic)`: fonts in the app's `res/font`. They are part of the app's signed package and are as trustworthy as the app's code.
2. Files, URIs, `Typeface`, custom `AndroidFont`, and downloadable fonts are not accepted. This removes paths by which vtfx parses untrusted font files.
3. Added to `restricted_apis` in `config/boundaries.toml` a rule that the repository's sources do not use APIs that create fonts from files and the like (`Typeface.createFrom*`, `Typeface.Builder`, `android.graphics.fonts`, `AndroidFont`, `GoogleFont`, `Font(File)`, and so on). Only tests that simulate asynchronous fonts are allowed.
4. Features that let users import their own fonts cannot be used for vtfx's terminal display. Apps that want to let users choose bundle the candidates.

[0040](0040-sanitized-user-fonts.md) added a third kind, `Sanitized`, for fonts that users choose, which vtfx reads and writes again in Kotlin, and allowed the font APIs in the one place that makes typefaces of them; font files are still not accepted as they are. [0044](0044-boundaries-in-gradle-and-classes.md) replaced `restricted_apis` with `restricted_classes`, a check of the compiled main classes, which allows the font APIs only in `vtfx-font-gate-android`.

## Future extension: validating and rebuilding fonts

A module that reads fonts in Kotlin and writes them again from validated values, so that fonts imported by users could be drawn, was discussed with a human and not made at the time. [0040](0040-sanitized-user-fonts.md) made it, and accepts CFF outlines as well, which this sketch rejected.

## Alternatives considered

- **Keep taking `FontFamily` and restrict it by inspecting the type inside**: It would rely on Compose's internal types and be fragile. Any typeface can be wrapped with `FontFamily(Typeface)`, so leaks could not be prevented either.
- **An escape hatch for apps that accept the risk (an opt-in `Unchecked(fontFamily)` and so on)**: Not made, because it breaks "vtfx itself can be used safely". If needed, it can be added without breaking the existing API.
- **Treat fonts as trusted input and make their origin the app's responsibility**: The problem appears in every app, so the library prevents what it can.
- **Read fonts in an isolated process** (`isolatedProcess`) and receive only glyphs: the strongest, but would mean rebuilding the rendering mechanism.
- **Bundle OpenType Sanitizer with the NDK**: maintaining native code, and taking on its own attack surface.
- **Reject fonts by looking at the list of their tables**: cannot prevent malformed data inside allowed tables.

## Impact of reversing

Add kinds to `TerminalFont`, or return `TerminalStyle` to `FontFamily`. Remove the font rule of `restricted_classes` in `boundaries.toml`. Revise `TerminalStyleTest` and the font section of usage.md.
