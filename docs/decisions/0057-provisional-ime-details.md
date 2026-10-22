# Details of IME input left open by a security review

- Status: Provisional
- Date: 2026-10-22
- Related: Sections 4.2, 5.1, and 6.3 of [ime-contract.md](../design/ime-contract.md), section 8 of [compose-input.md](../design/compose-input.md), [0004](0004-ime-editor-details.md), [0033](0033-sticky-modifiers-and-composing-text.md), [0055](0055-arming-sticky-modifiers-resets.md), `ImeEditor`, `TerminalSession`

## Background

A security review left points of IME input whose expected behavior the documents did not settle. They were decided provisionally, one item each.

## Decision

1. **A committed HT resets.** The IME may commit text with HT (`\t`), which is sent as is (section 4.2), and the app may send HT with `sendText` or `sendKey`. The host receives the same byte as for the Tab key, and a shell completes the word, so the input history no longer predicts its line, as section 5.1 says of Tab. The input history is reset after sending text with HT. HT is still sent as text, not as the Tab key, so the bytes do not change.
2. **A DEL from an IME edit does not take sticky modifiers.** With Ctrl armed, BS as `KEYCODE_DEL` sends Ctrl+BS and clears the modifiers, while a deletion with `deleteSurroundingText` sends a plain DEL and leaves them armed. The deletion is part of an IME edit, whose difference is DELs and text, not a key; sending it with modifiers would also change what the edit deletes. Since arming resets the input history ([0055](0055-arming-sticky-modifiers-resets.md)), BS right after arming finds nothing for the IME to delete, and IMEs then send `KEYCODE_DEL`, which takes the modifiers. Only a deletion of text typed after arming is a plain DEL. No code changes.
3. **A mouse press commits the composing text and resets.** A click reported to the host (vim in insert mode, for example) can move the host's cursor, so the input history no longer predicts the line, and the composing text typed before the click belongs before it. A press reported with `TerminalSession.sendMouse` (wheel steps included) first commits and sends the composing text and resets, as a key does. Releases and motion do neither: motion comes in streams, also while the user composes text, and would keep committing it.
4. **`TerminalSession.sendText` removes control characters without failing.** It follows the rule of committed text (section 4.2): line breaks are Enter, HT is kept, and the other control characters are removed. Its KDoc and [usage.md](../usage.md) now say so and point to `sendKey` for Esc and keys with Ctrl. It does not throw: text from an app's settings or snippets may carry stray controls, and the same rule for every path of text keeps the input history matching what the host receives.

## Alternatives considered

- Item 1: **Send a committed HT as the Tab key**: the key encoding sends HT too, so this changes nothing but the shape of `HostInput`, and an HT within text would split it.
- Item 2: **Apply the modifiers to the first DEL of an edit**: an edit can delete several characters for one key, and a replacement sends DELs that the user did not type.
- Item 3: **Leave mouse reports out of the editing model**: the composing text would arrive after the click, at the new position, and a later correction would send DELs at that position.
- Item 4: **Reject text with control characters with `IllegalArgumentException`**: it fails fast, but an app that sends text from elsewhere would crash on a stray control, and Esc is a key, not text, for every other path.

## Impact of reversing

Item 1: remove the check of HT from `ImeEditor.flush`, and revise `committedTabIsSentAndResetsLikeTheTabKey` and sections 4.2 and 5.1 of [ime-contract.md](../design/ime-contract.md). Item 2: make `TerminalSession` apply the modifiers to `HostInput.Delete`, and revise section 8 of [compose-input.md](../design/compose-input.md). Item 3: remove the call of `sendPendingInput` from `TerminalSession.sendMouse`, and revise its tests and section 5.1 of [ime-contract.md](../design/ime-contract.md). Item 4: make `TerminalSession.sendText` reject control characters other than line breaks and HT, and revise `controlsInTheAppsTextAreRemoved` in `TerminalSessionTest`, its KDoc, and [usage.md](../usage.md).
