# Research: differences in IME characteristics by language

Status: Record. It records research done before the repository was published (2026-10-15) and is not kept up to date. The current contract is in [ime-contract.md](../design/ime-contract.md), and the decisions it led to are [0004](../decisions/0004-ime-editor-details.md) and [0017](../decisions/0017-ime-scope.md).

Taking Japanese IMEs as the baseline, this organizes the IMEs that need handling different from Japanese in a terminal emulator.

Unless otherwise noted, the behavior of each IME in this document is based on general knowledge. Measurements with Gboard on the emulator are in [ime-gboard-emulator.md](ime-gboard-emulator.md), and where they disagree with this document, they take precedence.

## 1. Baseline: Japanese IMEs

- They keep composing text uncommitted, and the user commits explicitly.
- Text before committing is not sent to the host; it is sent on commit.
- The IME basically never rewrites committed text later.

From the terminal's point of view, this is one of the easier kinds.

## 2. IMEs with predictive text (English and others)

Japanese speakers also use these daily for typing commands, so they are the most important in practice.

- **Cannot send one character at a time immediately (does not happen with Gboard)**: IMEs with predictive text may hold the whole word being typed as composing text and not commit until a space. Treated the same as Japanese, pressing `j` in vim's normal mode would do nothing.
  - Measured: With Gboard on both the emulator and a real device, English did not use composing text and committed one character at a time immediately, so this problem did not occur ([ime-gboard-device.md](ime-gboard-device.md)). Other IMEs are unverified.
- **They rewrite committed text**: Autocorrect erases and replaces a committed word. The double-space period and automatic space insertion are similar. In a terminal, the text has already been sent to the host at commit time, so it cannot be undone.
  - Measured: Gboard's double-space feature and choosing a candidate arrived as `replaceText` and `commitText` on committed text. Without disclosing surrounding text, the output was wrong ([ime-gboard-device.md](ime-gboard-device.md)).
- **Pitfall of the workaround (confirmed by measurement)**: Setting `inputType` to visible password makes Gboard switch to an ASCII layout with a number row, and Japanese and Korean can no longer be entered. The same goes for `TYPE_NULL`, which Termux uses by default.

## 3. Korean (Hangul)

- In general, some IMEs make the composing range one syllable and commit automatically on the next jamo.
  - Measured: Gboard's Korean held the whole word as composing text and committed on a space and the like.
- The final consonant (batchim) moves. Typing "ㅏ" after "각" gives "가가".
  - Measured: In Gboard, this was handled within the composing range, and committed text was not rewritten.
- The handling of Enter, arrows, Ctrl+C, and so on pressed during composition differs by IME.
  - Measured: Gboard's Korean, on Enter during composition, committed with `commitText` and then called `commitText("\n")`. It arrives as a newline character, not a key event.

## 4. IMEs that rewrite the previous character (Vietnamese Telex and others)

- A later key adds a diacritic to the previous character, as in `aa` → `â` and `as` → `á`.
- Some IMEs implement this as composing text, and others erase the committed character and retype it.
- The latter may not work without disclosing surrounding text to the IME. Possible failures are that the diacritic is not added because the previous character is unknown, or that `deleteSurroundingText` is ignored and the result is doubled, like `aâ`.

## 5. Problems other than IMEs

- **Thai and Indic scripts**: Many combining characters and ligatures. The problem lies in character width and rendering rather than input (requirement 5.2).
- **Arabic and Hebrew**: bidi is out of the initial scope.
- **Dead keys on physical keyboards**: They arrive as KeyEvents without going through the IME, so they need to be composed ourselves with `KeyCharacterMap`.

## 6. Options for disclosing surrounding text to the IME

| Option | What is disclosed | What stops working | Privacy |
|---|---|---|---|
| A. No disclosure | Only the uncommitted input buffer | IMEs that rewrite committed text (autocorrect, some Telex) | Safest |
| B. Input history buffer | In addition to A, the text just committed and sent through the IME | Rewrites work only if DEL works as expected on the host | Low risk. The IME generated the text itself, so it already knows it |
| C. Screen contents | The terminal's screen contents | Nothing | High risk. Host output flows to the IME |

Notes on option B:

- When the IME calls `deleteSurroundingText` within the buffer, send as many DELs as characters deleted to the host, then send the new characters.
- Reset the buffer on Enter, arrows, physical keys, cursor movement due to host output, and so on, limiting the target of rewrites to roughly the word being typed.
- The IME can already send arbitrary bytes with `commitText`, so converting to DELs does not increase the IME's power.
- Whether deletion works correctly depends on the host. Shell line editing works almost as expected, but vim's normal mode and the like give unintended results.

xterm.js, without explicit design, ends up in a state close to option B (2.6 of [xterm-js-ime.md](xterm-js-ime.md)).

## 7. Proposal

- Originally, the proposal was to start with option A and add option B when needed. However, measurements showed that choosing candidates and the double-space feature, which are used daily with English Gboard, produce wrong output with option A. Therefore **the proposal is to adopt option B from the initial implementation** (section 4 of [ime-gboard-device.md](ime-gboard-device.md)).
- Decide the boundary from the start: "what is disclosed is limited to an input-side buffer separate from the screen contents". With this, widening to option B later does not create a path by which screen contents flow to the IME.
- In the IME matrix, include English Gboard (required) and Korean Gboard (for reports) in addition to Japanese Gboard.
