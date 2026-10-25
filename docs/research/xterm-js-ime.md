# Research: IME handling in xterm.js

Status: Record. It records research on the xterm.js commit below and is not kept up to date. What vtfx does is in [ime-contract.md](../design/ime-contract.md) and section 3.2 of [architecture.md](../design/architecture.md), and the decisions are [0082](../decisions/0082-ime-editing-model.md).

Target: xterm.js master `c58ea3637f3968e0e6e79cd92cf9aace7ef89ee2` (2026-08-30)

All file paths are relative to the root of the xterm.js repository.

## 1. Conclusion

xterm.js has no IME policy for Android. It uses its DOM-based mechanism for desktop browsers as is on mobile too.

- Browser Support in the README officially targets only evergreen desktop browsers (Chrome / Edge / Firefox / Safari) and Electron.
- The only IME tests are unit tests that feed synthesized sequences of DOM events (`src/browser/input/CompositionHelper.test.ts`). There are no tests with real IMEs or on Android.

## 2. Mechanism

### 2.1 Hidden textarea

- An invisible `<textarea>` is placed at the cursor position, and the IME types into this textarea (`_syncTextArea` in `src/browser/CoreBrowserTerminal.ts`).
- All xterm.js can observe are DOM events (`keydown` / `keypress` / `input` / `compositionstart` / `compositionupdate` / `compositionend`) and the value of the textarea.
- Android's `InputConnection` calls are handled inside the browser. They are invisible to xterm.js.
- It sets `autocomplete` / `autocorrect` / `autocapitalize` to `off` and `spellcheck` to `false`. Whether these are respected depends on the browser and the IME.

### 2.2 Displaying composing text

Handled by `compositionupdate` and `updateCompositionElements` in `src/browser/input/CompositionHelper.ts`.

- Composing text is displayed in a separate div overlaid at the cursor position (the composition view).
- The textarea is also moved to the same position and size, so that the IME's candidate window appears near the cursor.
- Long composing text is truncated with `direction: rtl` and a `max-width` of the terminal width, so that its end is visible.

### 2.3 Committing

Handled by `CompositionHelper._finalizeComposition`.

- The `data` of `compositionend` is not used. After waiting for `setTimeout(0)`, the relevant range of the textarea's value is extracted and sent.
- The code comments give the following two reasons.
  - In Chromium, the `data` of `compositionend` cannot be trusted.
  - In Korean, a following vowel moves the final consonant to the next syllable, so the `data` of the last `compositionupdate` is not correct either.

### 2.4 When a key other than for conversion is pressed during composition

Handled by `CompositionHelper.keydown`.

- When a key other than a modifier, CapsLock, or keyCode 229 arrives, the composing text is committed and sent immediately, and then the key is handled.
- The main purpose is to send the composing text before the command that Enter executes.

### 2.5 Input without composition (keyCode 229)

Handled by `CompositionHelper._handleAnyTextareaChanges`.

With Android's software keyboards, the keyCode of keydown is almost always 229, and the pressed key is unknown. Therefore the textarea's value is compared before and after `setTimeout(0)` to guess what to send.

| Change | What is sent |
|---|---|
| The value got longer | The difference. However, it is a naive computation with `newValue.replace(oldValue, '')`. |
| The value got shorter | Only one DEL (0x7f), however many characters were removed. |
| Same length but different content | The whole value of the textarea. |

Input from physical keyboards also goes through the IME on Android. Under fast typing, races occurred on this path, duplicating characters (fixed by `d03416467` "Fix key duplication on Android with physical keyboards").

### 2.6 Surrounding text visible to the IME

- The surrounding text visible to the IME is the textarea's value, that is, the text the user typed. The terminal's screen contents are not included.
- The textarea is cleared in the following cases.
  - When Ctrl+C (ETX) or Enter (CR) is handled on the normal path of `_keyDown`
  - When focus is lost (`_handleTextAreaBlur`)
- There is no sign of the disclosed range having been designed explicitly. It ends up this way as a result of the behavior above.

### 2.7 Relation to accessibility

- Screen reader mode uses the same textarea as the IME.
- Therefore, in screen reader mode, input handling through the `input` event is disabled (`_inputEvent`). In `compositionstart` too, the selection is used rather than the textarea's length, to avoid conflicting with screen reader mode.
- The IME and accessibility share the same buffer and constrain each other.

## 3. Known weaknesses

- **Weak at rewriting committed text**: The difference guessing in 2.5 makes what is sent diverge from the actual edit for word replacement by autocorrect and for deleting multiple characters at once. This is a problem with the IMEs with predictive text and Vietnamese Telex described in [ime-languages.md](ime-languages.md).
- **Timing-dependent**: It depends on reading the DOM state after `setTimeout(0)`, and races occur under fast typing.
- **Not observable**: Since what the IME actually called cannot be observed, the expected behavior cannot be written as a contract.

## 4. Implications for vtfx

### 4.1 Different premises

vtfx can implement `InputConnection` itself. It can receive calls such as `setComposingText` / `commitText` / `deleteSurroundingText` / `finishComposingText` / `sendKeyEvent` directly, so neither difference guessing nor `setTimeout` is needed.

This difference is the premise for writing "what is sent for which call" in requirement 5.3 as a contract and testing it deterministically.

### 4.2 What to adopt

- When a key other than for conversion arrives during composition, commit first and then send the key.
- Overlay composing text at the cursor position, and align the candidate window position there too.
- Decide in advance how to fit long composing text within the terminal width.

### 4.3 What to avoid

- Guessing input from differences in a buffer.
- Sharing the same buffer between the IME and accessibility.
- A state in which input keeps accumulating without the range disclosed to the IME being made explicit.
