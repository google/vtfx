# The IME's colors of composing text

- Status: Provisional
- Date: 2026-11-14
- Related: Requirements 5.3 and 6.1, section 7 of [ime-contract.md](../design/ime-contract.md), [0082](0082-ime-editing-model.md), [research/ime-composing-spans.md](../research/ime-composing-spans.md)

## Background

vtfx draws the IME's composing text at the terminal's cursor, underlined, in the terminal's default colors (section 7 of [ime-contract.md](../design/ime-contract.md)). While a Japanese IME converts several clauses, the user could not tell which clause was being converted, nor, after moving the IME's cursor within the composing text, where the cursor was. IMEs tell an editor both only through the spans of the text that they pass to `setComposingText`: `newCursorPosition` stays 1, and no IME selects within the composing text. vtfx dropped the spans when it turned the text into a `String`.

The IMEs observed mark the text in two ways ([research/ime-composing-spans.md](../research/ime-composing-spans.md)). Gboard puts translucent `BackgroundColorSpan`s over every part of the text: a stronger one over the clause being converted, or the text before the cursor, and a weaker one over the rest. Simeji puts opaque, light `BackgroundColorSpan`s in the same places, with a black `ForegroundColorSpan` over the whole text. Gboard's Korean and SwiftKey send no spans. Each IME chose its colors for `EditText`, which draws both kinds of span; no IME is told the editor's colors.

The IME is not trusted (requirement 6.1), and the composing text has no length limit, so the work of a frame is bounded by the size of the grid (section 7 of [ime-contract.md](../design/ime-contract.md)).

## 1. Draw the colors as EditText does

The composing text is drawn with the IME's `BackgroundColorSpan`s composited, in their order, over the terminal's default background, and with the color of the last `ForegroundColorSpan` over a character in place of the text's color, for the character and its underline. Characters without spans keep the terminal's default colors, as before.

**Why.** Only the colors tell the clause being converted and the cursor apart: Gboard covers the whole text with background colors that differ only in alpha, so a range alone says nothing. The IMEs chose their colors for an editor that draws both kinds of span, and drawing one kind alone breaks them: Simeji's near-white background under the terminal's white text could not be read. Drawn both, Gboard looked as it does in an `EditText` on light colors and on Solarized Dark, and Simeji was readable on black and on white. The colors come from an untrusted IME, but they change nothing but how the composing text looks, which is never sent: an IME that wanted the host to receive other text would commit it, and the colors fall only on the cells that the composing text covers, not on the terminal's own.

**Alternatives considered.**
- **Ignore the colors and draw each colored range reversed**, as xterm and mlterm draw a focused clause: Gboard's spans cover the whole text, so all of it would be reversed, and the clause being converted could not be told apart.
- **Map the IME's colors to colors of the terminal by order or by alpha** (the strongest is the focus): it guesses at a convention of each IME; the two observed use different colors for the same meaning, and an IME with a third convention would be drawn wrong where `EditText` draws it right.
- **Draw the background colors and keep the terminal's text color**: Simeji's clauses could not be read.

**Impact of reversing.** Drop the colors from the composing text of `ImeEditor` and from `TerminalRenderer`'s drawing of it, and their tests and screenshot goldens; section 7 of [ime-contract.md](../design/ime-contract.md) goes back to an underline alone.

## 2. Keep the colors with the composing text in the editing model

`ImeEditor` holds the colored ranges with the composing text as one value. `setComposingText` sets both; every other change of the composing text (a commit, `setComposingRegion`, a deletion that takes a character of it, a reset) drops the ranges. A change of the ranges alone counts as a change of the edit buffer for redrawing.

**Why.** The composing text changes on many paths, some of them outside the IME's calls, and on other threads (section 8 of [ime-contract.md](../design/ime-contract.md)); ranges held anywhere else could outlive the text they belong to. Moving the focus to another clause keeps the text and changes only the ranges, so a display that compared only the text would not redraw.

**Alternatives considered.**
- **Hold the ranges in the `InputConnection` or the display**: each path that changes the composing text would have to clear them.

**Impact of reversing.** Move the ranges, and every path that clears them, to the other place.

## 3. Bound and clip the ranges

At most 16 colored ranges are kept, in the order of the spans; ranges are clipped to the composing text, empty ones are dropped, and if dropping control characters changed the length of the text (invariant 3), its ranges are all dropped, since they no longer match it. Only the cells that the composing text takes on the grid are drawn (section 7 of [ime-contract.md](../design/ime-contract.md)).

**Why.** The IME may send any number of spans, at any offsets. The IMEs observed sent at most three colored spans, so 16 leaves room for an IME that colors each clause, while a frame stays bounded by the grid.

**Alternatives considered.**
- **No limit**: an IME could make every frame do work in proportion to its spans, against the bound of section 7.

## 4. Other spans are not drawn

`UnderlineSpan`, `LocaleSpan`, `SuggestionSpan`, `StyleSpan`, and every other kind of span are ignored. vtfx keeps drawing its own underline under the whole composing text, which marks it as not yet sent.

**Why.** The underline spans observed covered the whole text, as vtfx's underline does; the other kinds were not observed in composing text, and vtfx asks the IME for no suggestions (section 3 of [ime-contract.md](../design/ime-contract.md)), which is what `SuggestionSpan` would carry.

**Alternatives considered.**
- **Draw underline spans, so that an IME that underlines each clause separately shows where clauses split**: no IME observed does; add it when one does.

**Impact of reversing.** Add the kinds to the ranges of topic 2 and to the drawing.

## 5. The cursor bar stays after the composing text

The terminal's cursor is drawn after the composing text, as before, even when the IME's colors show its cursor within it.

**Why.** Where the IME's cursor is, within the composing text, is not part of the API: the colors only suggest it, by a convention of Mozc's that Simeji shares, and vtfx would have to guess which span ends at the cursor. The colors already show it to the user, as they do in an `EditText`, which draws its own cursor at the end too.

**Alternatives considered.**
- **Draw the cursor at the end of the first colored span**: right for Gboard and Simeji while composing, wrong while converting, where the first span is the clause being converted.

**Impact of reversing.** Draw the cursor where the guess puts it, in `TerminalRenderer` and in the insertion marker of `CursorAnchorInfo`.

## 6. No protection of contrast

The colors are drawn as the IME gives them, even when the text color and the background color are close.

**Why.** It is how `EditText` draws them, and choosing colors that read on any background is the IME's part: Gboard's translucent colors and Simeji's pairs of opaque colors both read on black and on white. A protection would need a threshold, and would change how an IME that chose its colors well looks.

**Alternatives considered.**
- **Fall back to the terminal's colors when the contrast of the two is too low**: it would help with an IME that sets a dark text color without a background, which would be unreadable on a dark terminal; none was observed. Revisit when one is.

**Impact of reversing.** Compute the contrast of each cell's colors in `TerminalRenderer`'s drawing of the composing text, and fall back below the threshold.
