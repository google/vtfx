# Fonts

This document describes where the fonts of the terminal come from, how vtfx sanitizes the fonts that users choose, the canonical form that is all that reaches the platform's native font code, and the modules that do it. Why it is so is in the decisions linked from each section.

Fonts are parsed by native code in the app's process (FreeType, HarfBuzz, minikin), and a malicious font can take over a process that holds SSH keys and connections. So vtfx draws only fonts that come with the system or the app's signed package, and fonts that it wrote itself in a form that it checked.

## 1. Where fonts come from

`TerminalStyle.font` takes a `TerminalFont`, a sealed interface of three kinds ([decisions/0086](../decisions/0086-fonts.md)):

| Kind | Fonts | Why they are trusted |
|---|---|---|
| `System(family)` | A font family of the system (`monospace` by default). An unknown name becomes the system's default font. | Part of the OS, protected by OS updates. |
| `Resources(regular, bold, italic, boldItalic)` | Fonts in the app's `res/font`. Styles left out are synthesized from `regular`. | Part of the app's signed package. |
| `Sanitized(families)` | Fonts that users chose, as `SanitizedFamily`s (section 3), tried in turn for each character; characters in none of them come from the system's `monospace` and its fallbacks. 1 to `Sanitized.MAX_FAMILIES` (64) families. The cell size is measured from the first family. | vtfx wrote them itself in the canonical form (section 5), and they passed the verifier (section 6). |

Files, URIs, `Typeface`, custom `AndroidFont`, and downloadable fonts are not accepted. The `restricted_classes` rule for fonts in `config/boundaries.toml` forbids, in the compiled main classes of every library module except `vtfx-font-gate-android`, the APIs that make fonts from data: the packages `android.graphics.fonts` and `androidx.compose.ui.text.googlefonts`, `Typeface.Builder`, `Typeface.CustomFallbackBuilder`, `Typeface.createFromAsset`, `Typeface.createFromFile`, `ParcelFileDescriptor`, Compose's `AndroidFont`, `LoadedFontFamily`, and `FontFamily(Typeface)` (section 5 of [architecture.md](architecture.md), [decisions/0088](../decisions/0088-build-boundaries-and-tests.md)).

## 2. Modules

The work is split so that only the code that decides what reaches native code is under security review, and a mistake in the rest cannot let unverified bytes reach native code ([decisions/0091](../decisions/0091-security-review-and-process.md)):

| Module | Kind | Package | Contents | Security review |
|---|---|---|---|---|
| `vtfx-font-gate` | JVM, no dependencies | `com.google.vtfx.font.gate` | `CanonicalVerifier`, and the public types `SanitizedFont`, `SanitizedFamily`, `InvalidFontException`, `FontLimits`, `WidthClass` | Yes |
| `vtfx-font` | JVM, depends on `vtfx-font-gate` (`api`) | `com.google.vtfx.font` | The readers of font files, the Type 2 charstring interpreter, the curve approximation, the writer of the canonical form, and the public `FontFile`, `FaceInfo`, `OutlineFormat` | No |
| `vtfx-font-gate-android` | Android, depends on `vtfx-font-gate` (and `vtfx-core` at compile time only, for `InternalVtfxApi`) | `com.google.vtfx.font.gate.android` | `SanitizedTypefaces`, the only code that hands font data to the platform (section 8) | Yes |

- `config/protected-paths.toml` lists `vtfx-font-gate/**` and `vtfx-font-gate-android/**` under security review.
- `config/boundaries.toml` reserves the package `com.google.vtfx.font.gate` and its subpackages for the two gate modules (`reserved_packages`), so that no other module can declare a class of the same name as a reviewed one and replace it on the class path.
- The verifier shares no code with the readers and the writer: each has its own functions that throw (`Failures.kt` in `vtfx-font`, private functions in `CanonicalVerifier.kt`). A bug in `vtfx-font` can make glyphs look wrong or fonts be rejected, but cannot let unverified bytes reach native code.
- `vtfx-compose` exposes `vtfx-font` and `vtfx-font-gate` with `api`, and uses `vtfx-font-gate-android` as `implementation`.

## 3. Public API

```kotlin
// vtfx-font (com.google.vtfx.font)
public object FontFile {
    public fun inspect(input: ByteArray, limits: FontLimits): List<FaceInfo>
    public fun sanitize(input: ByteArray, faceIndex: Int, limits: FontLimits): SanitizedFont
}
public class FaceInfo internal constructor(...) {
    public val index: Int; public val familyName: String?; public val styleName: String?
    public val outlineFormat: OutlineFormat; public val isSupported: Boolean
}
public value class OutlineFormat // TrueType, Cff, Cff2, None

// vtfx-font-gate (com.google.vtfx.font.gate)
public class SanitizedFont private constructor(...) {
    public val widthClass: WidthClass
    public val size: Int
    public fun toByteArray(): ByteArray
    public fun toByteBuffer(): ByteBuffer // read-only, direct
    public companion object {
        public fun fromCanonical(canonical: ByteArray, limits: FontLimits): SanitizedFont
    }
}
public class SanitizedFamily(regular: SanitizedFont, bold: SanitizedFont? = null, italic: SanitizedFont? = null, boldItalic: SanitizedFont? = null)
public class FontLimits(maxInputBytes: Int = 64 MiB, maxOutputBytes: Int = 64 MiB, maxGlyphPoints: Int = 16384)
public class InvalidFontException(public val kind: Kind, message: String) : Exception(message)
public value class InvalidFontException.Kind // TooLarge, Unsupported, Malformed, TooComplex
public value class WidthClass // Monospaced, DualWidth, Proportional

// vtfx-compose (com.google.vtfx.compose)
public sealed interface TerminalFont { ...; public class Sanitized(public val families: List<SanitizedFamily>) }
```

- vtfx takes bytes, never paths or URIs. An app sanitizes a font file once, when the user imports it, saves `toByteArray()`, and loads it later with `fromCanonical`, which runs only the verifier; so the place where the app keeps it does not need to be trusted, and loading does not convert again. Since vtfx takes bytes, reading them is the app's: it should stop reading a file once it holds more than `FontLimits.maxInputBytes` (`maxOutputBytes` for a saved font) rather than read it whole first, as the sample's `FontStore` does, so that a huge or endless file cannot exhaust memory before vtfx sees it.
- `FontFile.sanitize` ends in `SanitizedFont.fromCanonical`, and the constructor of `SanitizedFont` is private, so the verifier is the only way to a `SanitizedFont`: holding one proves that its bytes passed it. `fromCanonical` rejects bytes larger than `FontLimits.maxOutputBytes` first, and copies the others before checking them, so that the caller cannot change them after the check, and keeps them in a read-only direct buffer, which the platform reads in place. Two `SanitizedFont`s are equal when the SHA-256 of their bytes is.
- `FontFile.inspect` lists the faces of a file (one for a single font, one per face of a collection) for the app's picker, reading only the table directories and the names. The names come from the file: they are cut to 64 characters and stripped of control characters, format characters (general category Cf: bidirectional formatting, zero width characters, tags, and so on), and line and paragraph separators, so that a name shows on one line and as what it says; a name left blank becomes null. `isSupported` is true for the outline formats `TrueType` and `Cff`.
- `sanitize` can take seconds for a CJK font on a phone, so it is a blocking function for a background thread. It checks `Thread.interrupted()` and stops with `InterruptedException`. `vtfx-font` has no dependencies, so it does not use coroutines.
- `InvalidFontException.Kind`, `WidthClass`, and `OutlineFormat` are value classes with constants in their companions, not enums, since vtfx returns them and they may grow ([decisions/0087](../decisions/0087-public-api.md)). `InvalidFontException`'s constructor is public because `vtfx-font` throws it from outside the module that defines it.
- `SanitizedTypefaces` is `@InternalVtfxApi`: apps have no use for it.

## 4. Reading font files

| Input | Handling |
|---|---|
| sfnt with `glyf` (`0x00010000`, `true`), and one face of a TTC | Accepted |
| sfnt with CFF (`OTTO`), name-keyed or CID-keyed, and one face of an OTC | Accepted, converted to `glyf` |
| Variable fonts with `glyf` | Accepted as the default instance: `glyf` holds the default outlines, so the variation tables are dropped |
| CFF outlines that real fonts no longer use: charstring type other than 2, the deprecated arithmetic and storage operators of Type 2 charstrings, `endchar` drawing an accented character (seac), a `FontMatrix` other than a scale of 1/`unitsPerEm` or the identity (which CID-keyed fonts give in the Top DICT or the Font DICTs, the other holding the scale), matrices that together do not scale by 1/`unitsPerEm` (the Top DICT's and, in a CID-keyed font, each Font DICT's, concatenated, one left out counting as the identity, and CFF's default of 0.001 when neither is given), and a `CFF ` table of more than one font | Rejected as unsupported |
| CFF2 | Rejected as unsupported |
| WOFF, WOFF2, fonts without outlines (bitmap only), fonts with both `glyf` and CFF | Rejected as unsupported |

- **CFF to `glyf`**: a Type 2 charstring interpreter produces cubic curves, which are approximated with quadratic ones within 1/1000 of the em (the approach of fontTools' cu2qu: for each cubic curve the fewest quadratic curves that fit, with on-curve points implied between them), and the coordinates are rounded to integers. Contours are reversed, keeping their start points, since CFF draws outer contours counter-clockwise and TrueType clockwise. Outlines are not moved, since CFF has no left side bearing to apply. Charstrings are taken in the font units of `head`; the advance widths that they may give are ignored, since advances come from `hmtx`; hints are skipped. So native code never runs its CFF interpreter, and there is one canonical form and one verifier.
- **Composite glyphs** are flattened, applying their transforms, so that native code never follows component references. Components placed by matching points (offsets that are not x and y values) are rejected as unsupported.
- **TrueType outlines** are moved horizontally by their left side bearing in `hmtx` less their `xMin`, so that each glyph draws where it did while its left side bearing becomes its `xMin` (section 5).
- **Values out of range**: values that the canonical form keeps within ranges (metrics, weight, width, italic angle, and so on, section 5) are checked when they are read, and a font with one outside is rejected as unsupported, not clamped, so that no font is drawn differently from what its maker meant without a word.
- **Characters**: a character mapped to a glyph that does not exist, or mapped twice, makes the font malformed. Mappings to glyph 0 and of surrogate code points, which nothing could draw, are dropped. A font without a Unicode `cmap` or without characters is unsupported.
- The readers and the writer work glyph by glyph; the font is not held as an object model. The conversion allocates nothing for each point or curve: it reuses one pen and its arrays for every glyph, and loops over charstring arguments without ranges that allocate.

## 5. The canonical form

The writer in `vtfx-font` produces, and the verifier in `vtfx-font-gate` accepts, exactly this:

- **Header**: sfnt version `0x00010000`; exactly the ten tables `OS/2`, `cmap`, `glyf`, `head`, `hhea`, `hmtx`, `loca`, `maxp`, `name`, `post`, in that order (by tag bytes) in both the directory and the data; `searchRange`, `entrySelector`, and `rangeShift` computed (128, 3, 32); each table starting at a multiple of 4, padded with zeros, with no gaps, overlaps, or data after the last table; correct checksums and `head.checksumAdjustment`.
- **`head`** (54 bytes): version 1.0; `unitsPerEm` in 16..16384; `indexToLocFormat` 1 (long offsets, always); `glyphDataFormat` 0; magic number; the bounding box the union of the glyphs' boxes; `macStyle` with only bold and italic; fixed values for the other fields (`fontRevision` 1.0, `flags` 0x000B, dates 0, `lowestRecPPEM` 8, `fontDirectionHint` 2).
- **`maxp`**: version 1.0 (32 bytes); `numGlyphs` in 1..65535; `maxPoints` and `maxContours` computed from `glyf`; every composite and hinting field 0, except `maxZones` 1.
- **`glyf`**: each glyph is empty (zero length) or simple: 1 or more contours, `endPtsOfContours` strictly increasing, at most `FontLimits.maxGlyphPoints` points and at most 2^25 in all the glyphs together (the limit of section 9), instruction length 0, flags using only on-curve, x-short, y-short, repeat, x-same, and y-same (bits 0 to 5), coordinates in −16384..16383 (so that every difference between two fits in 16 bits), coordinate data that ends exactly at the glyph's end (padding of zeros to 4 bytes), and a bounding box equal to that of the points. The flags have one encoding: each coordinate in its shortest form (same, short, or 16 bits), and runs of three to 256 equal flags with a repeat count, others without. No composite glyphs.
- **`loca`**: long offsets, `numGlyphs + 1` of them, the first 0, non-decreasing, each a multiple of 4, the last equal to the length of `glyf`.
- **`hhea`** (36 bytes) and **`hmtx`**: `numberOfHMetrics` equal to `numGlyphs` (no trailing left side bearings); each left side bearing equal to the glyph's `xMin` (0 for empty glyphs); `advanceWidthMax`, `minLeftSideBearing`, `minRightSideBearing`, and `xMaxExtent` computed; caret slope 1/0 and the other fields 0; ascender in 1..2×`unitsPerEm`, descender in −2×`unitsPerEm`..0, line gap in 0..`unitsPerEm`, each advance in 0..4×`unitsPerEm`.
- **`cmap`**: version 0 with the encoding record (3, 10) to a format 12 subtable and, if the input has variation sequences, before it (records are sorted by platform) the record (0, 5) to a format 14 subtable; the subtables follow the records in their order.
  - Format 12: language 0; at least one group; groups sorted, not overlapping, merged when consecutive, code points in 0..0x10FFFF without surrogates, glyph ids in 1..`numGlyphs` − 1.
  - Format 14: variation selectors sorted and in the ranges of Unicode's variation selectors (U+180B to U+180D, U+180F, U+FE00 to U+FE0F, U+E0100 to U+E01EF), each with a default table, a non-default table, or both, none empty; default tables as ranges sorted, not overlapping, and merged when consecutive up to the 256 code points that one range can hold; non-default tables sorted by code point, with glyph ids in 1..`numGlyphs` − 1; code points as in format 12; at most 2^20 variation sequences of all the selectors together, counting each code point of a default range and each non-default mapping (the limit of section 9, so that a canonical font is within the limits of sanitizing); within the subtable, the tables right after the records, in their order, each default table before the non-default one.
  - No format 4: format 12 covers every code point.
- **`OS/2`**: version 4 (96 bytes); weight in 1..1000 and width in 1..9, copied; `fsType` 0; `fsSelection` with italic and bold from `macStyle`, regular when neither, and use-typo-metrics always; typographic ascender, descender, and line gap equal to those of `hhea`; `usWinAscent` and `usWinDescent` covering the bounding box; `usFirstCharIndex` and `usLastCharIndex` from `cmap` (at most 0xFFFF); `xAvgCharWidth` the rounded average of the nonzero advances; x-height and cap height copied within 0..2×`unitsPerEm`; the subscript, superscript, and strikeout fields derived from `unitsPerEm`; `achVendID` `vtfx`, `usBreakChar` 0x20, and the family class, Panose, Unicode range, code page, default character, and context fields 0.
- **`name`**: format 0 with four fixed ASCII records in UTF-16BE for (3, 1, 0x409): family `vtfx`, subfamily (`Regular`, `Bold`, `Italic`, or `Bold Italic`, from `macStyle`), full name `vtfx <subfamily>`, and PostScript name `vtfx-<subfamily without spaces>`. No text from the input reaches native code.
- **`post`** (32 bytes): format 3.0 (no glyph names); italic angle within ±60°, underline position within ±`unitsPerEm` and thickness within 0..`unitsPerEm`, copied; `isFixedPitch` 1 when the width class is `Monospaced` (section 7), else 0; the memory fields 0.
- **Dropped**: hinting (`fpgm`, `prep`, `cvt `, `gasp`), so the TrueType bytecode interpreter never runs; `GSUB`, `GPOS`, `GDEF`, `kern` (no ligatures, no `locl`); color and bitmaps (`COLR`, `CPAL`, `SVG `, `CBDT`, `CBLC`, `EBDT`, `EBLC`, `sbix`); vertical metrics; variations; and every unknown table.
- **Variation sequences** are kept: Japanese text uses them for the forms of names (葛󠄀 and 葛 are U+845B with and without U+E0100), and Noto Sans CJK and Source Han have them.
- **One encoding**: every byte is fixed, computed from the rest, or a number in a checked range, and the values have one encoding, so a canonical font is what sanitizing it gives.

## 6. The verifier

`CanonicalVerifier` reads the written bytes again and checks every rule of section 5, in one linear pass. Every read is checked against the bounds of the file first, so no input ends in an exception of the JVM. Everything that it rejects is `Malformed`, except bytes larger than `FontLimits.maxOutputBytes`, which are `TooLarge`.

It runs for each of the millions of points of a CJK font, every time an app loads a saved font, so it allocates nothing for each point or glyph: the arrays of the points of a glyph are reused for every glyph, growing to the largest; the message of a check is built only when the check fails; and its reads of one and two bytes are kept small enough for Android's runtime to inline them (32 code units of dex by default), with the exception of a read outside the font built in another function. Android's runtime, unlike HotSpot, does not remove short-lived objects.

## 7. CJK

- **Regional glyphs** come from choosing a face, not from `locl`, which is dropped with `GSUB`: each face of Noto Sans CJK's OTC maps the code points to its region's glyphs, and a regional OTF does so by itself.
- **Widths**: the verifier computes `SanitizedFont.widthClass` from the advances of the glyphs that `cmap` maps, ignoring zero advances: `Monospaced` for one advance, `DualWidth` for one advance and its double (as in CJK monospace fonts), and `Proportional` otherwise, or when no character has a width. It is reported, not enforced: the cell size is measured from the font, and wide characters take two cells by their width in `vtfx-core`.
- **Fallback chain**: `TerminalFont.Sanitized` takes a list of families, for example a Latin font, then a CJK font (section 1).

## 8. Typefaces

`SanitizedTypefaces.regular` in `vtfx-font-gate-android` makes the regular typeface of a list of `SanitizedFamily`s: one platform `FontFamily` per family, each with a `Font` per style that it has (built with `Font.Builder` from `toByteBuffer()`), joined with `Typeface.CustomFallbackBuilder` and the system fallback `monospace`. If the platform rejects verified bytes, that is a bug in the canonical form, and it fails with `IllegalStateException` rather than falling back.

`vtfx-compose` derives the bold, italic, and bold italic typefaces from the regular one with `Typeface.create`, which reads no font data: each family gives its closest font for the style, and the system synthesizes the rest.

## 9. Limits

| Limit | Value | Kind of rejection |
|---|---|---|
| `FontLimits.maxInputBytes`: the font file | 64 MiB by default, at most 256 MiB (`FontLimits.MAX_BYTES`) | `TooLarge` |
| `FontLimits.maxOutputBytes`: the canonical font | 64 MiB by default, at most 256 MiB | `TooLarge` |
| `FontLimits.maxGlyphPoints`: the points of one glyph after conversion and flattening | 16384 by default, at most 65535 (`FontLimits.MAX_GLYPH_POINTS`) | `TooComplex` |
| Faces of a collection | 256 | `Unsupported` |
| Depth of composite glyphs | 8 | `TooComplex` |
| Components that flattening one glyph visits | 4096 | `TooComplex` |
| Components that flattening all the glyphs of a face visits together | 2^22 | `TooComplex` |
| Points of all the glyphs of a face together, after conversion and flattening | 2^25 | `TooComplex` |
| Code points that `cmap` maps, counting overlaps | 0x110000 | `TooComplex` |
| Variation sequences, all selectors together | 2^20 | `TooComplex` |
| Operators that one charstring executes, counting those of subroutines | 65536 | `TooComplex` |
| Operators and operands that all the charstrings of a face execute together, counting those of subroutines | 2^27 | `TooComplex` |
| Argument stack of a charstring | 48 (the CFF specification) | `Malformed` |
| Depth of subroutine calls | 10 (the CFF specification) | `TooComplex` |
| Quadratic curves that approximate one cubic curve | 32 | `TooComplex` |
| Font DICTs of a CID-keyed font | 256 (FDSelect cannot choose more) | `Unsupported` |
| Size of a Private DICT | 64 KiB | `Unsupported` |

The fixed limits are far beyond real fonts; they bound the time and memory that a crafted font can take. The limits of one glyph alone do not bound the time of a face, since every glyph can reach them through subroutines or composite glyphs that all the glyphs share, in a few bytes each; so the work of the whole face is bounded too, independently of the number of glyphs. The face limits leave room over the most complex real fonts: Noto Serif CJK executes about 30 million operators and operands (about 2^25) and has about 10.6 million points after conversion (about 2^23.3), and Droid Sans Fallback visits about 55,000 components. The size of the canonical font does not bound its points: runs of equal points take repeated flags of two bytes for each 256 points, so a font of a few megabytes could have a billion. Bounding the Font DICTs and the Private DICTs keeps the time of reading the DICTs bounded however they overlap. The limits of `FontLimits` follow the pattern of `TerminalLimits` ([decisions/0081](../decisions/0081-resource-limits.md)).

## 10. Tests

- The verifier's tests are in `vtfx-font-gate`, under review with it. `CanonicalVerifierTest` reaches the verifier through `SanitizedFont.fromCanonical`, with canonical fonts kept as files in `vtfx-font-gate/src/fuzz/resources/com/google/vtfx/font/gate/canonical/` and changes to them whose checksums the test computes again. `vtfx-font-gate` depends on nothing, not even in its tests ([decisions/0088](../decisions/0088-build-boundaries-and-tests.md)), so it cannot build the fonts itself.
- `CanonicalFixturesTest` in `vtfx-font` checks that `FontFile.sanitize` still writes those files byte for byte, which makes them a golden of the canonical form. `VerifierCrossCheckTest` checks that the fonts that the verifier accepts are those that sanitizing leaves as they are.
- The fonts of most tests of `vtfx-font` are generated by test code (TrueType and CFF, name-keyed and CID-keyed, collections, composites). Outlines are compared before and after: exactly for `glyf`, and within the approximation's tolerance for CFF.
- `ThirdPartyFontsTest` in `vtfx-font` sanitizes real fonts under the SIL Open Font License, one of each kind of font file: BIZ UDGothic (TrueType), Noto Sans JP (CID-keyed CFF), and the Noto Sans CJK collection (CID-keyed CFF faces). Gradle downloads them at pinned commits with the checksums in `gradle/verification-metadata.xml`, into a configuration on no classpath, so that no artifact contains them, and passes their paths to the test ([decisions/0086](../decisions/0086-fonts.md)). Each sanitized face must be one that the verifier accepts and that sanitizing leaves as it is.
- Fuzzing (section 7 of [testing.md](testing.md)): `FontFuzzer` in `vtfx-font` passes arbitrary bytes to `inspect`, `sanitize`, and `fromCanonical`, and checks that the only exception is `InvalidFontException` and that sanitizing is idempotent; `VerifierFuzzer` in `vtfx-font-gate` fuzzes the verifier with changes to the canonical fonts, after which it computes the checksums again, so that the changed fonts reach the checks of every table.
- Besides `ThirdPartyFontsTest`, real fonts are checked outside `check.sh`, by `SystemFontsTest` (an instrumented test of `vtfx-compose`) on the emulator with the system's own fonts (`/system/fonts`, which has the Noto Sans CJK OTC): the first face of each font file must sanitize, load, and draw, and the CJK faces must draw their own glyphs.

## 11. Known limitations

- No ligatures, no hinting (its effect on display is to be checked on devices, [review-queue](../review-queue.md)), no CFF2, and CFF outlines are approximated. The residual risk is in FreeType's `glyf` loader and HarfBuzz's handling of `cmap` (formats 12 and 14) and `hmtx` on well-formed input.
- `FontFile.sanitize` goes through `fromCanonical`, which copies the bytes before checking them, so sanitizing holds one more copy of the output (up to `FontLimits.maxOutputBytes`) for a moment, and a loaded font is held twice while `fromCanonical` runs (the checked copy and the direct buffer). Checking the bytes in the final direct buffer instead would remove the copy.
- The ABI dump of the Android modules (`DumpAbiTask` in `build-logic`) does not leave out `@InternalVtfxApi` declarations: the filters that the task passes to the Build Tools API have no effect. So `vtfx-font-gate-android.api` lists `SanitizedTypefaces`, while `vtfx-font-gate-android.signatures`, the Kotlin signatures, leaves it out.
