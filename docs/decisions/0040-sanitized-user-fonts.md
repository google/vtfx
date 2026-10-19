# Fonts chosen by users, sanitized in Kotlin

- Status: Provisional
- Date: 2026-10-19
- Related: Requirements 6.1 and 6.5, [0020](0020-fonts.md) (extends its "Future extension"), [0002](0002-resource-limits.md), [0016](0016-public-api.md), [0037](0037-api-shape-before-1.0.md), [0038](0038-kitty-graphics.md) (PNG decoded in Kotlin), `vtfx-font`, `TerminalFont` in `vtfx-compose`

## Background

[0020](0020-fonts.md) limits terminal fonts to the system's and the app's own resources, because fonts are parsed by native code (FreeType, HarfBuzz, minikin) in the app's process, and a malicious font can take over a process that holds SSH keys and connections. Apps still want users to import their own fonts, and on 2026-10-19 a human asked for that, with CJK fonts supported from the start, and approved this design.

Most monospace CJK fonts that users want are either TrueType (`glyf`) fonts (HackGen, UDEV Gothic, PlemolJP, Sarasa Gothic, BIZ UDGothic) or CID-keyed CFF fonts (Noto Sans Mono CJK, Source Han Mono), often in collections (TTC, OTC) of one face per region. 0020's sketch rejected CFF, which would leave out the second group.

Threats, beyond those of 0020: a path to a file in shared storage, read again later, can be replaced by another app (TOCTOU); fonts can exhaust memory and time (huge files, deep subroutine recursion in CFF, glyphs with very many points, absurd metrics); and the `name` table carries arbitrary text that an app may show.

## Decision

### Only fonts that vtfx wrote reach native code

- A new module `vtfx-font` (JVM, no dependencies, package `com.google.vtfx.font`) reads a font in Kotlin and writes a **new font in one canonical form** (below), from validated values only. Errors in reading become Kotlin exceptions, not memory corruption.
- The canonical form is always TrueType outlines. **CFF outlines (name-keyed and CID-keyed) are converted to `glyf` in Kotlin**: a Type 2 charstring interpreter produces cubic curves, which are approximated with quadratic ones within 1/1000 of the em (the approach of fontTools' cu2qu: for each cubic curve the fewest quadratic curves that fit, with on-curve points implied between them), and the coordinates are rounded to integers. Contours are reversed, keeping their start points, since CFF draws outer contours counter-clockwise and TrueType clockwise; outlines are not moved, since CFF has no left side bearing to apply. Charstrings are taken in the font units of `head`; the advance widths that they may give are ignored, since advances come from `hmtx`; hints are skipped. So native code never runs its CFF interpreter, and there is one canonical form and one verifier.
- A separate **`CanonicalVerifier`** reads the written bytes again and checks every rule of the canonical form. Only bytes that pass it become a `SanitizedFont`. It does not share code with the readers and the writer, so that the security review covers only the verifier and the one file in `vtfx-compose` that turns verified bytes into a `Typeface`, not the readers, the charstring interpreter, or the curve approximation. A bug in those can make glyphs look wrong or fonts be rejected, but cannot let unverified bytes reach native code.
- vtfx takes **bytes, never paths or URIs**. A saved font is loaded with `SanitizedFont.fromCanonical`, which runs only the verifier (one linear pass); so the place where the app keeps it does not need to be trusted, and loading does not convert again.

### Inputs

| Input | Handling |
|---|---|
| sfnt with `glyf` (`0x00010000`, `true`), and one face of a TTC | Accepted |
| sfnt with CFF (`OTTO`), name-keyed or CID-keyed, and one face of an OTC | Accepted, converted to `glyf` |
| Variable fonts with `glyf` | Accepted as the default instance: `glyf` holds the default outlines, so the variation tables are dropped |
| CFF outlines that real fonts no longer use: charstring type other than 2, the deprecated arithmetic and storage operators of Type 2 charstrings, `endchar` drawing an accented character (seac), a `FontMatrix` other than a scale of 1/`unitsPerEm` or the identity (which CID-keyed fonts give in the Top DICT or the Font DICTs, the other holding the scale), and a `CFF ` table of more than one font | Rejected as unsupported |
| CFF2 | Rejected for now; supporting it later means ignoring the deltas of `blend` in the same interpreter |
| WOFF, WOFF2, fonts without outlines (bitmap only), fonts with both `glyf` and CFF | Rejected |

Values that the canonical form keeps within ranges (metrics, weight, and so on, below) are checked when they are read, and a font with one outside is rejected as unsupported, not clamped, so that no font is drawn differently from what its maker meant without a word. A character mapped to a glyph that does not exist makes the font malformed. Mappings to glyph 0 and of surrogate code points, which nothing could draw, are dropped.

### The canonical form

The writer produces, and the verifier accepts, exactly this:

- **Header**: sfnt version `0x00010000`; exactly the ten tables `OS/2`, `cmap`, `glyf`, `head`, `hhea`, `hmtx`, `loca`, `maxp`, `name`, `post`, in that order (by tag bytes) in both the directory and the data; `searchRange`, `entrySelector`, and `rangeShift` computed; each table starting at a multiple of 4, padded with zeros, with no gaps or overlaps; correct checksums and `head.checksumAdjustment`.
- **`head`**: version 1.0; `unitsPerEm` in 16..16384; `indexToLocFormat` 1 (long offsets, always); `glyphDataFormat` 0; magic number; the bounding box the union of the glyphs' boxes; `macStyle` with only bold and italic; fixed values for the other fields (`fontRevision` 1.0, `flags` 0x000B, dates 0, `lowestRecPPEM` 8, `fontDirectionHint` 2).
- **`maxp`**: version 1.0 (32 bytes); `numGlyphs` in 1..65535; `maxPoints` and `maxContours` computed from `glyf`; every composite and hinting field 0, except `maxZones` 1.
- **`glyf`**: each glyph is empty (zero length) or simple: 1 or more contours, `endPtsOfContours` strictly increasing, instruction length 0, flags using only on-curve, x-short, y-short, repeat, x-same, and y-same (bits 0 to 5), coordinates in −16384..16383 (so that every difference between two fits in 16 bits), coordinate data that ends exactly at the glyph's end (padding of zeros to 4 bytes), and a bounding box equal to that of the points. The flags have one encoding: each coordinate in its shortest form (same, short, or 16 bits), and runs of three to 256 equal flags with a repeat count, others without. **No composite glyphs**: the readers flatten them, applying the transforms, so that native code never follows component references. Point matching of components (offsets that are not x and y values) is rejected.
- **`loca`**: long offsets, `numGlyphs + 1` of them, non-decreasing, each a multiple of 4, the last equal to the length of `glyf`.
- **`hhea`** and **`hmtx`**: `numberOfHMetrics` equal to `numGlyphs` (no trailing left side bearings); each left side bearing equal to the glyph's `xMin` (0 for empty glyphs), the outline moved by the original left side bearing less the original `xMin` so that it draws where it did; `advanceWidthMax`, `minLeftSideBearing`, `minRightSideBearing`, and `xMaxExtent` computed; caret slope 1/0 and the other fields 0; ascender in 1..2×`unitsPerEm`, descender in −2×`unitsPerEm`..0, line gap in 0..`unitsPerEm`, each advance in 0..4×`unitsPerEm`.
- **`cmap`**: version 0 with the encoding record (3, 10) to a format 12 subtable and, if the input has variation sequences, before it (records are sorted by platform) the record (0, 5) to a format 14 subtable; the subtables follow the records in their order. Format 12: groups sorted, not overlapping, merged when consecutive, code points in 0..0x10FFFF without surrogates, glyph ids in 1..`numGlyphs` − 1. Format 14: variation selectors sorted and in the ranges of Unicode's variation selectors (U+180B to U+180D, U+180F, U+FE00 to U+FE0F, U+E0100 to U+E01EF), each with a default table, a non-default table, or both, none empty; default tables as ranges sorted, not overlapping, and merged when consecutive up to the 256 code points that one range can hold; non-default tables sorted by code point, with glyph ids in 1..`numGlyphs` − 1; code points as in format 12; within the subtable, the tables right after the records, in their order, each default table before the non-default one. No format 4: format 12 covers every code point. If a device turns out to need format 4, it is added then, with the verifier.
- **Variation sequences** are kept because Japanese text uses them for the forms of names (葛󠄀 and 葛 are U+845B with and without U+E0100), and Noto Sans CJK and Source Han have them. They are kept from the start because apps save only canonical fonts: a later version could not add them to fonts imported before it, short of importing the original file again.
- **`OS/2`**: version 4 (96 bytes); weight in 1..1000 and width in 1..9, copied; `fsType` 0; `fsSelection` with only italic, bold, regular, and use-typo-metrics; typographic ascender, descender, and line gap equal to those of `hhea`; `usWinAscent` and `usWinDescent` covering the bounding box; `usFirstCharIndex` and `usLastCharIndex` from `cmap`; `xAvgCharWidth` the rounded average of the nonzero advances; x-height and cap height copied within 0..2×`unitsPerEm`; the subscript, superscript, strikeout, Panose, Unicode range, and code page fields fixed or derived from `unitsPerEm`, not copied.
- **`name`**: format 0 with fixed ASCII records (family `vtfx`, subfamily from bold and italic, full name, PostScript name), in UTF-16BE for (3, 1, 0x409). No text from the input reaches native code.
- **`post`**: format 3.0 (no glyph names); italic angle within ±60°, underline position and thickness within the em, copied; `isFixedPitch` set when the font is monospaced (below).
- Dropped: hinting (`fpgm`, `prep`, `cvt `, `gasp`), so the TrueType bytecode interpreter never runs; `GSUB`, `GPOS`, `GDEF`, `kern` (no ligatures, no `locl`); color and bitmaps (`COLR`, `CPAL`, `SVG `, `CBDT`, `CBLC`, `EBDT`, `EBLC`, `sbix`); vertical metrics; variations; and every unknown table.
- **One encoding**: every byte is fixed, computed from the rest, or a number in a checked range, and the values have one encoding, so a canonical font is what sanitizing it gives. Tests and fuzzing check both directions: sanitizing a sanitized font gives the same bytes, and bytes that the verifier accepts are what sanitizing them gives.

### CJK

- **Regional glyphs** come from choosing a face, not from `locl`, which is dropped with `GSUB`: each face of Noto Sans CJK's OTC maps the code points to its region's glyphs, and a regional OTF does so by itself. `FontFile.inspect` lists the faces of a file (family and style names, outline format) for the app's picker. The names are cut to 64 characters and stripped of control and bidirectional formatting characters.
- **Widths**: `SanitizedFont.widthClass` is `Monospaced` (one nonzero advance among the mapped glyphs), `DualWidth` (one advance and its double, as in CJK monospace fonts), or `Proportional`. It is reported, not enforced: the cell size is measured from the font as before, and wide characters take two cells by their width in vtfx-core.
- **Fallback chain**: `TerminalFont.Sanitized` takes a list of families (for example a Latin font, then a CJK font), and characters in none of them fall back to the system's `monospace`.

### Limits and cost

- `FontLimits` (like `TerminalLimits`, [0002](0002-resource-limits.md)) bounds the input (64 MiB by default; one weight of the Noto Sans CJK OTC is about 20 MB), the output (64 MiB, which also bounds the points of the whole font), and the points of one glyph after conversion and flattening (16384). Fixed limits, which no real font comes near, bound the faces of a collection (256), the depth of composite glyphs (8) and the components that flattening one glyph visits, the code points that `cmap` maps and its variation sequences, and the work of one charstring (65536 operators executed, counting those of subroutines, so that subroutines calling each other many times cannot take unbounded time; the argument stack of 48 and the subroutine depth of 10 come from the CFF specification). A cubic curve that 32 quadratic curves cannot approximate makes the font too complex. A CID-keyed font has at most 256 Font DICTs (FDSelect cannot choose more), and a Private DICT at most 64 KiB, so that reading the DICTs takes bounded time however they overlap.
- The readers and the writer work glyph by glyph; the font is not held as an object model. The canonical bytes are held once, in a read-only buffer that `Font.Builder` takes without copying.
- `sanitize` can take seconds for a CJK font on a phone, so it is a blocking function for a background thread, run once when the user imports a font. It checks `Thread.interrupted()` and stops with `InterruptedException`. `vtfx-font` stays without dependencies, so it does not use coroutines.

### API

```kotlin
// vtfx-font
public object FontFile { public fun inspect(input: ByteArray, limits: FontLimits): List<FaceInfo> }
public class SanitizedFont private constructor(...) {
    public val widthClass: WidthClass
    public fun toByteArray(): ByteArray
    public fun toByteBuffer(): ByteBuffer // read-only
    public companion object {
        public fun sanitize(input: ByteArray, faceIndex: Int, limits: FontLimits): SanitizedFont
        public fun fromCanonical(canonical: ByteArray, limits: FontLimits): SanitizedFont
    }
}
public class InvalidFontException(public val kind: Kind, message: String) : Exception(message)

// vtfx-compose
public class SanitizedFamily(regular, bold?, italic?, boldItalic?)
public sealed interface TerminalFont { ...; public class Sanitized(public val families: List<SanitizedFamily>) }
```

- A `SanitizedFont` can only come from `sanitize` or `fromCanonical`, both of which end in the verifier, so the type proves that its bytes passed it. Equality is by the SHA-256 of the bytes.
- `InvalidFontException.Kind`, `WidthClass`, and `FaceInfo`'s outline format are value classes with constants in their companions, not enums, since vtfx returns them and they may grow ([0037](0037-api-shape-before-1.0.md)). `TerminalFont` is given by apps, so adding `Sanitized` does not break them.
- If `Font.Builder` rejects verified bytes, that is a bug in the canonical form, and it fails loudly rather than falling back.

### Boundaries and review

- `config/boundaries.toml` allows the font APIs (`android.graphics.fonts`, `Typeface.CustomFallbackBuilder`) in exactly one file of `vtfx-compose`, which takes only `SanitizedFont`. This is the one relaxation of 0020's rule, approved by a human with this design.
- `config/protected-paths.toml` puts that file and `CanonicalVerifier` under security review.

### Tests

- Jazzer fuzzes `sanitize`, `fromCanonical`, and `inspect`: the only exception is `InvalidFontException`, every output of `sanitize` passes `fromCanonical`, and sanitizing is idempotent.
- Fonts for the gating tests are generated by test code (TrueType and CFF, name-keyed and CID-keyed, collections, composites), since committing third-party font files needs a human to check their licenses ([review-queue](../review-queue.md)). Outlines are compared before and after: exactly for `glyf`, and within the approximation's tolerance for CFF.
- Real fonts are checked outside `check.sh`, by a task given a directory of fonts, and on the emulator with the system's own fonts (`/system/fonts`, which has the Noto Sans CJK OTC).

## Alternatives considered

- **Draw glyphs in Kotlin** (outlines to `Path`, cached as bitmaps): no font parsing in native code at all, but two drawing paths in the renderer, and fallback and combining characters handled by vtfx. Kept as the next step if the residual risk below is not accepted.
- **Write CFF in a canonical form** instead of converting it: no approximation, but two canonical forms and verifiers, and FreeType's CFF parsing (DICT, INDEX, FDSelect) stays exposed.
- **Reject CFF**: leaves out Noto Sans Mono CJK and Source Han Mono, against the request.
- **An isolated process, OpenType Sanitizer, an unchecked escape hatch**: as in [0020](0020-fonts.md).
- **Keep format 4 in `cmap`**: more for the verifier to check, and format 12 covers every code point.

- **Drop variation sequences** (format 14), drawing them with the glyph of the base character: a smaller verifier, but fonts saved without them could not get them later.
- **Accept CFF2 as its default instance**: the default instance of a variable CJK font is often not the regular weight, so it would need instancing at chosen axis values, which is much more work; static OTF and OTC files are widely available.

Accepted costs: no ligatures; CFF2 and hinting are dropped (the effect of no hinting is checked on devices); CFF outlines are approximated. The residual risk is in FreeType's `glyf` loader and HarfBuzz's handling of `cmap` (formats 12 and 14) and `hmtx` on well-formed input, the most used and fuzzed paths of those libraries.

## Impact of reversing

Remove `TerminalFont.Sanitized`, `SanitizedFamily`, and the module `vtfx-font`; restore the rule of `boundaries.toml` and `protected-paths.toml`; revise the font section of usage.md and the sample's font import.
