# Research: the spans of composing text, and drawing them

Status: Record. It records observations made on 2026-11-14 and is not kept up to date. The current contract is in [ime-contract.md](../design/ime-contract.md), and the decision it led to is [0103](../decisions/0103-ime-colors-of-composing-text.md).

## 1. Question

While a Japanese IME converts several clauses, the terminal showed which text was composing, underlined, but not which clause was being converted, nor where the IME's cursor was when the user moved it within the composing text. `ImeEditor.setComposingText` turned the `CharSequence` from the IME into a `String`, so whatever the IME marked on the text was lost, and `newCursorPosition` is ignored (invariant 1).

## 2. Environment

| Device | IME | Input |
|---|---|---|
| The `vtfx-probe` AVD (x86_64, API 37) | Gboard 18.5.9, Japanese (QWERTY) and Korean | Taps on the software keyboard, and keys of `adb shell input keyboard` |
| A tablet (arm64-v8a, API 35) | Simeji 23.7, Japanese (QWERTY, romaji) | Taps on the software keyboard |
| The same tablet | Microsoft SwiftKey 9.13.19.3, Japanese (12 keys) | Taps on the software keyboard, and keys of `adb shell input keyboard` |

The sample app ran a prototype that logged every span of the text of each `setComposingText` (class, range, flags, and the color of `BackgroundColorSpan` and `ForegroundColorSpan`), and drew the colors of the composing text as `EditText` draws them. The prototype was not kept. Samsung Keyboard was not checked: Google Play has no build of it for devices other than Samsung's.

## 3. What each IME sent

`newCursorPosition` was 1 in every call, and no IME called `setSelection` within the composing text.

| IME | State | Spans |
|---|---|---|
| Gboard, Japanese | Composing, cursor at the end | `UnderlineSpan`, `BackgroundColorSpan #664db6ac`, and `LocaleSpan`, each over the whole text |
| | Composing, cursor moved left with ◀ | `BackgroundColorSpan #664db6ac` before the cursor and `#194db6ac` after it; `UnderlineSpan` and `LocaleSpan` over the whole text |
| | Converting | `BackgroundColorSpan #66ef3566` over the clause being converted and `#19ef3566` over the other clauses; `UnderlineSpan` and `LocaleSpan` over the whole text |
| Gboard, Korean | Composing | None: the text was a `String`, from the software keyboard and from physical keys |
| Simeji | Composing, cursor at the end | `UnderlineSpan` over the whole text |
| | Composing, cursor moved left with ← | `BackgroundColorSpan #ff66cdaa` before the cursor and `#fff0ffff` after it; `ForegroundColorSpan #ff000000` and `UnderlineSpan` over the whole text |
| | Converting | `BackgroundColorSpan #ff8888ff` over the clause being converted and `#fff0ffff` over the other clauses; `ForegroundColorSpan #ff000000` and `UnderlineSpan` over the whole text |
| SwiftKey, Japanese | Composing | None: the text was a `String`. Conversion is by choosing a candidate from the bar, without clauses in the composing text, and ← committed the composing text |

- Gboard's colors are those of `SPAN_CONVERT_HIGHLIGHT` and `SPAN_BEFORE_CURSOR` in the Android code of Mozc, which Gboard's Japanese input comes from.
- The position of the cursor within the composing text reaches the editor only through these spans. Gboard's ◀ during composition sends `setComposingText` with the same text and new spans.
- Moving the focus to another clause (a physical → with Gboard) sends the same text with new spans, so an editor that compares only the text sees no change.
- Gboard's colors are translucent (alpha 0x66 and 0x19), meant to blend with any background, and leave the text its color. Simeji's are opaque and light, with black text, meant to look the same on any background.

## 4. Drawing

| Drawn | Result |
|---|---|
| Gboard's background colors over the terminal's background, the text in the terminal's color | On black, the clause being converted (red, alpha 0x66) and the text before the cursor stand out; the other clauses (alpha 0x19) are barely visible, so only the edges of the clause being converted show where clauses split. On white, and on Solarized Dark, every clause shows, much as in an `EditText` |
| Simeji's background colors only, the text in the terminal's color (white) | The clauses on `#fff0ffff` were white on near white and could not be read |
| Simeji's background and text colors | Readable on black and on white. On black, the composing text is a light block, as it would be in a light app |

The cursor bar stayed after the composing text, while the colors showed the cursor within it, so the two disagreed when the user moved the cursor.
