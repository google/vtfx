# Fonts: where they come from, sanitizing fonts that users choose, and test fonts

- Status: Approved
- Date: 2026-10-25
- Related: Requirements 6.1 and 6.5 (customizability, safe defaults), [fonts.md](../design/fonts.md), section 2 of [architecture.md](../design/architecture.md), section 4.5 of [boundaries.md](../design/boundaries.md), section 7 of [testing.md](../design/testing.md), [0081](0081-resource-limits.md), [0087](0087-public-api.md), [0090](0090-distribution.md), [0088](0088-build-boundaries-and-tests.md), [0080](0080-kitty-graphics.md) (PNG decoded in Kotlin), [0091](0091-security-review-and-process.md), [0088](0088-build-boundaries-and-tests.md)

## Background

Fonts (TTF, OTF) are parsed inside the app's process by the platform's native code (HarfBuzz for shaping, FreeType for rasterization, minikin). Vulnerabilities in this parsing that let a malicious font execute arbitrary code have been found repeatedly and used in real attacks (the embedded PNG of CVE-2020-15999, the structures related to variable fonts of CVE-2025-27363, and so on). The process of an SSH client holds keys and connections, so the impact of a takeover is large.

Even for a file the user chose themselves, the user cannot judge its contents. A path to a file in shared storage, remembered and read again later, can be replaced by another app that can write to the storage (TOCTOU), which becomes a path for a less privileged app to bring code into the SSH app's process. Fonts can also exhaust memory and time (huge files, deep subroutine recursion in CFF, glyphs with very many points, absurd metrics), and the `name` table carries arbitrary text that an app may show. The threat of physical access to the device is not handled at the font layer: if the device is unlocked, there are more direct means than fonts, and if it is locked, fonts cannot be placed.

Apps still want users to choose and import their own fonts, with CJK fonts supported from the start. Most monospace CJK fonts that users want are either TrueType (`glyf`) fonts (HackGen, UDEV Gothic, PlemolJP, Sarasa Gothic, BIZ UDGothic) or CID-keyed CFF fonts (Noto Sans Mono CJK, Source Han Mono), often in collections (TTC, OTC) of one face per region.

## 1. Where fonts come from

`TerminalStyle.font` takes a sealed `TerminalFont` of exactly three kinds: `System` (a font family of the system), `Resources` (fonts in the app's `res/font`), and `Sanitized` (fonts that users chose, which vtfx wrote again itself, topics 2 and 3) (section 1 of [fonts.md](../design/fonts.md)). Files, URIs, `Typeface`, custom `AndroidFont`, and downloadable fonts are not accepted. The APIs that make fonts from data are forbidden in every library module except exactly one place, which takes only `SanitizedFont`; the build checks this in the compiled classes (section 1 of [fonts.md](../design/fonts.md), section 4.5 of [boundaries.md](../design/boundaries.md), [0088](0088-build-boundaries-and-tests.md)).

**Why.** Refusing everything else removes the paths by which vtfx would parse untrusted font files. System fonts are part of the OS and protected by OS updates; fonts in `res/font` are part of the app's signed package and as trustworthy as the app's code; sanitized fonts are bytes that vtfx wrote and checked (topics 2 and 3). Adding the kind `Sanitized` does not break apps, since apps give a `TerminalFont` rather than receive one.

**Alternatives considered.**
- **Keep taking `FontFamily` and restrict it by inspecting the type inside**: it would rely on Compose's internal types and be fragile. Any typeface can be wrapped with `FontFamily(Typeface)`, so leaks could not be prevented either.
- **An escape hatch for apps that accept the risk** (an opt-in `Unchecked(fontFamily)` and so on): it breaks "vtfx itself can be used safely". If needed, it can be added without breaking the existing API.
- **Treat fonts as trusted input and make their origin the app's responsibility**: the problem appears in every app, so the library prevents what it can.
- **Read fonts in an isolated process** (`isolatedProcess`) and receive only glyphs: the strongest, but it would mean rebuilding the rendering mechanism.
- **Bundle OpenType Sanitizer with the NDK**: maintaining native code, and taking on its own attack surface.
- **Reject fonts by looking at the list of their tables**: cannot prevent malformed data inside allowed tables.

**Impact of reversing.** Add kinds to `TerminalFont`, or return `TerminalStyle` to `FontFamily`. Remove the font rule of `restricted_classes` in `config/boundaries.toml`. Revise `TerminalStyleTest` and the font section of usage.md.

## 2. Fonts that users choose are written again in one canonical form

1. **Only fonts that vtfx wrote reach native code.** `vtfx-font` reads a font in Kotlin and writes a new font in one canonical form, from validated values only (sections 4 and 5 of [fonts.md](../design/fonts.md)).
2. **CFF outlines, name-keyed and CID-keyed, are converted to `glyf` in Kotlin**, approximating cubic curves with quadratic ones within 1/1000 of the em, so that the canonical form always has TrueType outlines. Collections (TTC, OTC) are read one face at a time, and variable fonts with `glyf` become their default instance. CFF2, WOFF, and bitmap-only fonts are rejected (section 4 of [fonts.md](../design/fonts.md)).
3. **What a terminal does not need is dropped**: hinting, OpenType layout (`GSUB`, `GPOS`, so no ligatures and no `locl`), color and bitmap glyphs, variations, the names of the input, and every unknown table. Values that the canonical form keeps within ranges are rejected when out of them, not clamped (sections 4 and 5 of [fonts.md](../design/fonts.md)).
4. **CJK from the start**: regional glyphs come from choosing the face of the region; variation sequences (`cmap` format 14) are kept; `SanitizedFont.widthClass` reports whether a font is monospaced or dual-width, without enforcing it; `TerminalFont.Sanitized` takes a fallback chain of families (sections 1 and 7 of [fonts.md](../design/fonts.md)).
5. **Bounded work**: `FontLimits` bounds the input, the output, and the points of one glyph, and fixed limits bound the rest (section 9 of [fonts.md](../design/fonts.md)). `sanitize` is a blocking function for a background thread that stops when interrupted. The verifier and the conversion allocate nothing for each point (sections 3, 4, and 6 of [fonts.md](../design/fonts.md)); measurements are in [font-sanitizing-performance.md](../research/font-sanitizing-performance.md).

**Why.** Errors in reading become Kotlin exceptions, not memory corruption. Converting CFF means that native code never runs its CFF interpreter, and that there is one canonical form and one verifier, while both groups of CJK fonts (TrueType and CID-keyed CFF) are accepted. Variation sequences are kept because apps save only canonical fonts, and a later version could not add them to fonts imported before it. `sanitize` blocks rather than suspends so that the module needs no dependencies. Nothing is allocated for each point since Android's runtime does not remove short-lived objects.

Accepted costs: no ligatures; CFF2 and hinting are dropped (the effect of no hinting is checked on devices); CFF outlines are approximated. The residual risk is in FreeType's `glyf` loader and HarfBuzz's handling of `cmap` (formats 12 and 14) and `hmtx` on well-formed input (section 11 of [fonts.md](../design/fonts.md)), the most used and fuzzed paths of those libraries.

**Alternatives considered.**
- **Draw glyphs in Kotlin** (outlines to `Path`, cached as bitmaps): no font parsing in native code at all, but two drawing paths in the renderer, and fallback and combining characters handled by vtfx. Kept as the next step if the residual risk above is not accepted.
- **Write CFF in a canonical form** instead of converting it: no approximation, but two canonical forms and verifiers, and FreeType's CFF parsing (DICT, INDEX, FDSelect) stays exposed.
- **An isolated process, OpenType Sanitizer, an unchecked escape hatch**: as in topic 1.
- **Reject CFF**: leaves out Noto Sans Mono CJK and Source Han Mono, against supporting CJK fonts from the start.
- **Keep format 4 in `cmap`**: more for the verifier to check, and format 12 covers every code point. If a device turns out to need format 4, it can be added then, with the verifier.
- **Drop variation sequences** (format 14), drawing them with the glyph of the base character: a smaller verifier, but fonts saved without them could not get them later.
- **Accept CFF2 as its default instance**: the default instance of a variable CJK font is often not the regular weight, so it would need instancing at chosen axis values, which is much more work; static OTF and OTC files are widely available.

**Impact of reversing.** Remove `TerminalFont.Sanitized` and the modules `vtfx-font`, `vtfx-font-gate`, and `vtfx-font-gate-android`; allow the font APIs in no module in the `restricted_classes` rule of `config/boundaries.toml`, and remove the two gate modules from `config/protected-paths.toml`; revise the font section of usage.md and the sample's font import.

## 3. A separate verifier is the only way to a `SanitizedFont`

1. **A separate verifier** reads the written bytes again and checks every rule of the canonical form; only bytes that pass it become a `SanitizedFont`. It shares no code with the readers and the writer (sections 2 and 6 of [fonts.md](../design/fonts.md)).
2. **The type proves the check**: a `SanitizedFont` can only come from functions that end in the verifier (section 3 of [fonts.md](../design/fonts.md)).
3. **vtfx takes bytes, never paths or URIs.** A saved font is loaded with `fromCanonical`, which runs only the verifier (section 3 of [fonts.md](../design/fonts.md)).
4. **Security review** covers the verifier, with the public types that its guarantee relies on, and the code that turns verified bytes into a typeface, the one place of topic 1, and not the readers, the charstring interpreter, or the curve approximation. The modules that hold them are in section 2 of [fonts.md](../design/fonts.md) ([0091](0091-security-review-and-process.md)).
5. **No fallback**: if the platform rejects verified bytes, that is a bug in the canonical form, and it fails loudly (section 8 of [fonts.md](../design/fonts.md)).
6. The kinds of `InvalidFontException`, `WidthClass`, and the outline format of a face are value classes, not enums, since vtfx returns them and they may grow ([0087](0087-public-api.md), section 3 of [fonts.md](../design/fonts.md)).

**Why.** Keeping the verifier apart from the readers and the writer keeps the readers, the charstring interpreter, and the curve approximation out of security review. Taking bytes and checking them again on loading means that the place where the app keeps a saved font does not need to be trusted, and loading does not convert again.

**Impact of reversing.** Removing the sanitized fonts removes the verifier and its modules with them (topic 2).

## 4. Fonts of the tests

1. Most tests of `vtfx-font` use fonts generated by test code; the readers, the writer, and the verifier are fuzzed; real fonts are also checked on the emulator with the system's own fonts, outside `check.sh` (section 10 of [fonts.md](../design/fonts.md), section 7 of [testing.md](../design/testing.md)).
2. **Fonts under the SIL Open Font License may be used as test data**; fonts under other licenses may not.
3. **They are never part of the distributed libraries**: no published artifact contains them, and no main source set refers to them.
4. **The build downloads them rather than the repository holding them.** They are Gradle dependencies of a configuration of `vtfx-font` that is on no classpath, fetched from an Ivy repository on `raw.githubusercontent.com` that serves only their groups, at pinned commits, and `gradle/verification-metadata.xml` pins their checksums as it does those of every other dependency ([0088](0088-build-boundaries-and-tests.md)). The test task gets their paths as system properties.
5. **Three fonts, one of each kind of font file**: BIZ UDGothic (TrueType), Noto Sans JP (CID-keyed CFF), and the Noto Sans CJK collection of the Regular weight (a collection of CID-keyed CFF faces).
6. **`ThirdPartyFontsTest` runs in `check.sh`**, as part of the unit tests of `vtfx-font`: each font must be read, and each sanitized face must be one that the verifier accepts and that sanitizing leaves as it is.

**Why.** The generated fonts cover the structures that the readers know, but not the sizes and the combinations of real CJK fonts. Committed fonts would make every clone tens of megabytes larger, and git compresses font files hardly at all. Gradle caches the downloaded fonts, so the network is needed only where the other dependencies are not cached either.

**Alternatives considered.**
- **Commit the fonts under `third_party/`**, as [0090](0090-distribution.md) keeps third-party material: tens of megabytes in every clone for three fonts, and more for each one added.
- **Download them in the test code**: the test would need its own checksums, cache, and proxy settings, a way into the build that dependency verification does not cover.
- **Run the test outside `check.sh`**, like long fuzzing: the gate would not see a change that breaks real fonts, and the download costs nothing more once Gradle has cached the fonts.
- **Keep only generated fonts in the gating tests**: real fonts would be checked only on the emulator.

**Impact of reversing.** Remove the `thirdPartyFonts` configuration and its dependencies from `vtfx-font/build.gradle.kts`, the repository from `settings.gradle.kts`, their entries from `gradle/verification-metadata.xml`, and `ThirdPartyFontsTest`.
