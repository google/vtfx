# Restricting where terminal fonts come from

- Status: Approved (2026-10-13, decided in a discussion with a human)
- Date: 2026-10-13
- Related: Requirements 6.1 and 6.5 (customizability, safe defaults), [0016](0016-public-api.md), [0040](0040-sanitized-user-fonts.md) (fonts chosen by users, sanitized in Kotlin: the future extension below, carried out), `TerminalFont` in `vtfx-compose`, `config/boundaries.toml`

## Background

Terminal apps often want to let users choose a font. `TerminalStyle` took Compose's `FontFamily`, so apps could also pass fonts from files or downloaded fonts.

Fonts (TTF, OTF) are parsed inside the app's process by the OS's native code (HarfBuzz for shaping, FreeType for rasterization, and so on). Vulnerabilities in this parsing that let a malicious font execute arbitrary code have been found repeatedly, and have been used in real attacks (the embedded PNG of CVE-2020-15999, the structures related to variable fonts of CVE-2025-27363, and so on). The process of an SSH client holds keys and connections, so the impact of a takeover is large.

Even for a file the user chose themselves, the user cannot judge its contents. Also, in designs that remember a path in shared storage and read it again, another app that can write to the storage can later replace the file, which becomes a path for a less privileged app to bring code into the SSH app's process. The threat of physical access to the device is not handled at the font layer: if the device is unlocked, there are more direct means than fonts, and if it is locked, fonts cannot be placed.

## Decision

- Replace `TerminalStyle.fontFamily: FontFamily` with `TerminalStyle.font: TerminalFont`. `TerminalFont` is sealed and represents only the following two.
  - `System(family)`: the name of a system font (`monospace` and so on). System fonts are part of the OS and are protected by OS updates. Unknown names become the system's default font.
  - `Resources(regular, bold, italic, boldItalic)`: fonts in the app's `res/font`. They are part of the app's signed package and are as trustworthy as the app's code.
- Files, URIs, `Typeface`, custom `AndroidFont`, and downloadable fonts are not accepted. This removes paths by which vtfx parses untrusted font files.
  - 2026-10-19: [0040](0040-sanitized-user-fonts.md) added a third kind, `Sanitized(families)`: fonts that users choose, read and written again in Kotlin, whose bytes pass a verifier before native code reads them. Font files are still not accepted as they are.
- Added to `restricted_apis` in `config/boundaries.toml` a rule that the repository's sources do not use APIs that create fonts from files and the like (`Typeface.createFrom*`, `Typeface.Builder`, `android.graphics.fonts`, `AndroidFont`, `GoogleFont`, `Font(File)`, and so on). Only tests that simulate asynchronous fonts are allowed.
  - 2026-10-20: [0044](0044-boundaries-in-gradle-and-classes.md) replaced `restricted_apis`, a check of the source text, with `restricted_classes`, a check of the compiled main classes of the library modules; tests are not checked. The rule allows the font APIs only in `vtfx-font-gate-android` ([0040](0040-sanitized-user-fonts.md), [0041](0041-security-review-by-module.md)).
- Features that let users import their own fonts cannot be used for vtfx's terminal display. Apps that want to let users choose bundle the candidates.

## Future extension: validating and rebuilding fonts

To safely use fonts imported by users, there is an option to create the following module (discussed with a human and decided not to implement now).

- `vtfx-font` (JVM, no dependencies): Parses fonts in Kotlin, keeps only the tables a terminal needs (`cmap`, `glyf`, `loca`, `hmtx`, `hhea`, `head`, `maxp`, `OS/2`, `name`, `post`, and so on) by an allowlist, and rebuilds a new font file from the validated values. Hinting, color and bitmaps, variable fonts, and `GSUB` and `GPOS` are discarded. Outlines are also rewritten after validating the number of points and the depth of composite glyphs. Only normalized simple fonts reach native code. Malformed structures become Kotlin exceptions, not memory corruption. Fuzzed with Jazzer.
- Add a third kind representing a validated font to `TerminalFont`. The current API is not broken.
- The initial target is static fonts with TrueType outlines (`glyf`); CFF OTFs and variable fonts are rejected. One font is extracted from a TTC. The effect on display of discarding hinting is checked on real devices.
- Making it a separate repository as a general-purpose font sanitizer is not done until a second user appears. It is made independent of vtfx's other modules so that it can be split out.
- Alternatives considered: reading fonts in an isolated process (`isolatedProcess`) and receiving only glyphs is the strongest, but would mean rebuilding the rendering mechanism. Bundling OpenType Sanitizer with the NDK means maintaining native code and taking on its own attack surface. Only rejecting by looking at the list of tables cannot prevent malformed data inside allowed tables.

## Alternatives considered

- **Keep taking `FontFamily` and restrict it by inspecting the type inside**: It would rely on Compose's internal types and be fragile. Any typeface can be wrapped with `FontFamily(Typeface)`, so leaks could not be prevented either.
- **An escape hatch for apps that accept the risk (an opt-in `Unchecked(fontFamily)` and so on)**: Not made, because it breaks "vtfx itself can be used safely". If needed, it can be added without breaking the existing API.
- **Treat fonts as trusted input and make their origin the app's responsibility**: The problem appears in every app, so the library prevents what it can.

## Impact of reversing

Add kinds to `TerminalFont`, or return `TerminalStyle` to `FontFamily`. Remove the rule in `boundaries.toml`. Revise `TerminalStyleTest` and the font section of usage.md.
