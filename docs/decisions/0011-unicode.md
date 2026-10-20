# Unicode version, character width, grapheme clusters, fonts

- Status: Approved (2026-10-13, approved by a human in the final review); generating the width table in the build superseded by [0029](0029-import-without-gradle.md)
- Date: 2026-10-12
- Related: Requirement 5.2, section 8 (the policy on the Unicode version, Ambiguous width, and grapheme clusters), `CharWidth`, `scripts/generate_unicode_tables.py`, [third_party/unicode](../../third_party/unicode/README.md), `BoxDrawing`

## Background

If the terminal and the host disagree on character widths, cursor positions shift and the screen breaks. Most apps on the host count widths with libc's `wcwidth()` (or their own table with the same rules). Requirement 5.2 asks for the policy on fullwidth, Ambiguous, grapheme clusters, and the Unicode version to be decided.

## Decision

### Unicode version and width rules

- The character width table is generated from the Unicode Character Database by `scripts/generate_unicode_tables.py` and committed as `UnicodeWidthTables.kt` in `vtfx-core` ([0029](0029-import-without-gradle.md)). The rules are the same as glibc's `utf8_gen.py`. The script reads the copies of the UCD files in `third_party/unicode` and does not download them, so as not to put load on unicode.org (decided by a human).
  - Width 0: general categories Mn, Me, Cf (except U+00AD), Hangul medial vowel and final consonant jamo (U+1160–U+11FF, U+D7B0–U+D7FF), U+200B.
  - Width 2: East_Asian_Width W or F. Includes unassigned code points in the CJK ranges (the `@missing` default).
  - Everything else has width 1.
- The version is Unicode 16.0.0, the same as glibc 2.41 in Debian 13 as of 2026-10. Move to a newer version after the libc of major hosts does (replace the UCD files in `third_party/unicode` and run the script).

### East Asian Ambiguous

- The default is width 1. `wcwidth()` returns 1 in almost all locales (including ja_JP.UTF-8).
- `AmbiguousWidth.Wide` makes it width 2 (an argument of `Terminal` and `TerminalSession`). This is for users who use vim's `ambiwidth=double` and the like on the host. Matching the host's setting is the app's responsibility, and is written in the KDoc.

### grapheme clusters

- Widths are counted per code point, to match the sum of `wcwidth()` on the host. Zero-width characters (combining characters, ZWJ, variation selectors) are attached to the preceding cell.
- Therefore a sequence of emoji joined with ZWJ (family emoji and so on) takes cells for each emoji. Drawing it in 2 cells as one emoji would disagree with the width the host counted.
- As an exception, a pair of regional indicators (a flag) is combined into one 2-cell-wide character. Two indicators are 1 + 1 cells to begin with, so combining them does not change the number of cells, and they can be drawn as a flag. They are not combined when wrapping at the end of a line.
- A mode that counts width with a grapheme cluster as one unit (the mode 2027 proposal) is not supported, since it needs agreement with the host.

### Fonts and rendering

- Box-drawing (U+2500–U+257F) and block elements (U+2580–U+259F) are drawn as shapes rather than font glyphs. Font glyphs do not reach the edges of the cell, leaving gaps between cells. Light, heavy, double, rounded-corner, diagonal, and dashed lines (drawn as solid) are drawn.
- CJK characters are drawn with Android's system font fallback. The glyphs of Han characters (which differ between Japanese, Chinese, and Korean) can be chosen with `TerminalStyle.locale`. The default is the system locale. The difference in glyphs by locale could not be confirmed with Robolectric's rendering, so checking on a real device was put in the review queue.

## Alternatives considered

- **Use the latest Unicode (18.0)**: The widths of newly assigned characters would disagree with the host. The libc of major hosts is still at 16 or earlier.
- **Draw a grapheme cluster as one unit**: Looks nicer, but disagrees with the width the host counted and breaks the cursor.

## Impact of reversing

To change the version, replace the files in `third_party/unicode` as its README describes, regenerate the table with `scripts/generate_unicode_tables.py`, and check the width examples in `EmulationCoverageTest`. To change the handling of grapheme clusters, change `Emulator.print` and `Line`.
