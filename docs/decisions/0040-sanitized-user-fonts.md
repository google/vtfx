# Fonts chosen by users, sanitized in Kotlin

- Status: Approved (2026-10-20, approved by a human in a review of the review queue); the module layout, the package, and the API names of items 3, 8, and 9 superseded by [0041](0041-security-review-by-module.md); the licenses of test fonts in item 10 superseded by [0071](0071-ofl-fonts-as-test-data.md)
- Date: 2026-10-19
- Related: Requirements 6.1 and 6.5, [fonts.md](../design/fonts.md), [0020](0020-fonts.md) (extends its "Future extension"), [0002](0002-resource-limits.md), [0016](0016-public-api.md), [0037](0037-api-shape-before-1.0.md), [0080](0080-kitty-graphics.md) (PNG decoded in Kotlin), `vtfx-font`, `TerminalFont` in `vtfx-compose`

## Background

[0020](0020-fonts.md) limits terminal fonts to the system's and the app's own resources, because fonts are parsed by native code (FreeType, HarfBuzz, minikin) in the app's process, and a malicious font can take over a process that holds SSH keys and connections. Apps still want users to import their own fonts, and on 2026-10-19 a human asked for that, with CJK fonts supported from the start, and approved this design.

Most monospace CJK fonts that users want are either TrueType (`glyf`) fonts (HackGen, UDEV Gothic, PlemolJP, Sarasa Gothic, BIZ UDGothic) or CID-keyed CFF fonts (Noto Sans Mono CJK, Source Han Mono), often in collections (TTC, OTC) of one face per region. 0020's sketch rejected CFF, which would leave out the second group.

Threats, beyond those of 0020: a path to a file in shared storage, read again later, can be replaced by another app (TOCTOU); fonts can exhaust memory and time (huge files, deep subroutine recursion in CFF, glyphs with very many points, absurd metrics); and the `name` table carries arbitrary text that an app may show.

## Decision

1. **Only fonts that vtfx wrote reach native code.** A new module, `vtfx-font`, reads a font in Kotlin and writes a new font in one canonical form, from validated values only; errors in reading become Kotlin exceptions, not memory corruption. The canonical form is described in section 5 of [fonts.md](../design/fonts.md).
2. **CFF outlines, name-keyed and CID-keyed, are converted to `glyf` in Kotlin**, approximating cubic curves with quadratic ones within 1/1000 of the em, so that the canonical form is always TrueType outlines: native code never runs its CFF interpreter, and there is one canonical form and one verifier (section 4 of [fonts.md](../design/fonts.md)). Collections (TTC, OTC) are read one face at a time, and variable fonts with `glyf` become their default instance. CFF2, WOFF, and bitmap-only fonts are rejected.
3. **A separate verifier** reads the written bytes again and checks every rule of the canonical form; only bytes that pass it become a `SanitizedFont`. It shares no code with the readers and the writer, so that security review covers only the verifier and the code that turns verified bytes into a typeface, not the readers, the charstring interpreter, or the curve approximation (section 6 of [fonts.md](../design/fonts.md)).
4. **vtfx takes bytes, never paths or URIs.** A saved font is loaded with `fromCanonical`, which runs only the verifier, so the place where the app keeps it does not need to be trusted, and loading does not convert again.
5. **What a terminal does not need is dropped**: hinting, OpenType layout (`GSUB`, `GPOS`, so no ligatures and no `locl`), color and bitmap glyphs, variations, the names of the input, and every unknown table. Values that the canonical form keeps within ranges are rejected when out of them, not clamped.
6. **CJK from the start**: regional glyphs come from choosing the face of the region; variation sequences (`cmap` format 14) are kept, because apps save only canonical fonts, and a later version could not add them to fonts imported before it; `SanitizedFont.widthClass` reports whether a font is monospaced or dual-width, without enforcing it; `TerminalFont.Sanitized` takes a fallback chain of families (sections 1 and 7 of [fonts.md](../design/fonts.md)).
7. **Bounded work**: `FontLimits` bounds the input, the output, and the points of one glyph, and fixed limits bound the rest (section 9 of [fonts.md](../design/fonts.md)). `sanitize` is a blocking function for a background thread that stops when interrupted, so that the module needs no dependencies. The verifier and the conversion allocate nothing for each point, since Android's runtime does not remove short-lived objects; measurements are in [research/font-sanitizing-performance.md](../research/font-sanitizing-performance.md).
8. **API**: `TerminalFont` gets a third kind, `Sanitized(families)`, which does not break apps, since apps give `TerminalFont` rather than receive it. A `SanitizedFont` can only come from functions that end in the verifier, so the type proves that its bytes passed it. The kinds of `InvalidFontException`, `WidthClass`, and the outline format of a face are value classes, not enums, since vtfx returns them and they may grow ([0037](0037-api-shape-before-1.0.md)). If the platform rejects verified bytes, that is a bug in the canonical form, and it fails loudly rather than falling back. The current API is in section 3 of [fonts.md](../design/fonts.md).
9. **Boundaries and review**: the font APIs that 0020 forbade are allowed in exactly one place, which takes only `SanitizedFont`; this is the one relaxation of 0020's rule, approved by a human with this design. That place and the verifier are under security review.
10. **Tests**: the fonts of the gating tests are generated by test code, since committing third-party font files needs a human to check their licenses ([review-queue](../review-queue.md)); the readers, the writer, and the verifier are fuzzed; real fonts are checked on the emulator, outside `check.sh` (section 10 of [fonts.md](../design/fonts.md)).

Items 3, 8, and 9 placed the verifier, `SanitizedFont`, and the code that makes typefaces in `vtfx-font` and `vtfx-compose`, with `sanitize` on `SanitizedFont` and `SanitizedFamily` in `vtfx-compose`. [0041](0041-security-review-by-module.md) replaced that layout: the verifier and the public types it relies on are in `vtfx-font-gate` (package `com.google.vtfx.font.gate`), the code that makes typefaces is the module `vtfx-font-gate-android`, and `sanitize` is `FontFile.sanitize`.

## Alternatives considered

- **Draw glyphs in Kotlin** (outlines to `Path`, cached as bitmaps): no font parsing in native code at all, but two drawing paths in the renderer, and fallback and combining characters handled by vtfx. Kept as the next step if the residual risk below is not accepted.
- **Write CFF in a canonical form** instead of converting it: no approximation, but two canonical forms and verifiers, and FreeType's CFF parsing (DICT, INDEX, FDSelect) stays exposed.
- **Reject CFF**: leaves out Noto Sans Mono CJK and Source Han Mono, against the request.
- **An isolated process, OpenType Sanitizer, an unchecked escape hatch**: as in [0020](0020-fonts.md).
- **Keep format 4 in `cmap`**: more for the verifier to check, and format 12 covers every code point. If a device turns out to need format 4, it can be added then, with the verifier.
- **Drop variation sequences** (format 14), drawing them with the glyph of the base character: a smaller verifier, but fonts saved without them could not get them later.
- **Accept CFF2 as its default instance**: the default instance of a variable CJK font is often not the regular weight, so it would need instancing at chosen axis values, which is much more work; static OTF and OTC files are widely available.

Accepted costs: no ligatures; CFF2 and hinting are dropped (the effect of no hinting is checked on devices); CFF outlines are approximated. The residual risk is in FreeType's `glyf` loader and HarfBuzz's handling of `cmap` (formats 12 and 14) and `hmtx` on well-formed input, the most used and fuzzed paths of those libraries.

## Impact of reversing

Remove `TerminalFont.Sanitized` and the modules `vtfx-font`, `vtfx-font-gate`, and `vtfx-font-gate-android`; allow the font APIs in no module in the `restricted_classes` rule of `boundaries.toml`, and remove the two modules from `protected-paths.toml`; revise the font section of usage.md and the sample's font import.
