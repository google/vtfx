# Details of IME input left open by a security review

- Status: Provisional
- Date: 2026-10-22
- Related: Sections 4.2, 5.1, and 6.3 of [ime-contract.md](../design/ime-contract.md), section 8 of [compose-input.md](../design/compose-input.md), [0004](0004-ime-editor-details.md), [0033](0033-sticky-modifiers-and-composing-text.md), [0055](0055-arming-sticky-modifiers-resets.md), `ImeEditor`, `TerminalSession`

## Background

A security review left points of IME input whose expected behavior the documents did not settle. They were decided provisionally, one item each.

## Decision

1. **A committed HT resets.** The IME may commit text with HT (`\t`), which is sent as is (section 4.2), and the app may send HT with `sendText` or `sendKey`. The host receives the same byte as for the Tab key, and a shell completes the word, so the input history no longer predicts its line, as section 5.1 says of Tab. The input history is reset after sending text with HT. HT is still sent as text, not as the Tab key, so the bytes do not change.
2. **A DEL from an IME edit does not take sticky modifiers.** With Ctrl armed, BS as `KEYCODE_DEL` sends Ctrl+BS and clears the modifiers, while a deletion with `deleteSurroundingText` sends a plain DEL and leaves them armed. The deletion is part of an IME edit, whose difference is DELs and text, not a key; sending it with modifiers would also change what the edit deletes. Since arming resets the input history ([0055](0055-arming-sticky-modifiers-resets.md)), BS right after arming finds nothing for the IME to delete, and IMEs then send `KEYCODE_DEL`, which takes the modifiers. Only a deletion of text typed after arming is a plain DEL. No code changes.

## Alternatives considered

- Item 1: **Send a committed HT as the Tab key**: the key encoding sends HT too, so this changes nothing but the shape of `HostInput`, and an HT within text would split it.
- Item 2: **Apply the modifiers to the first DEL of an edit**: an edit can delete several characters for one key, and a replacement sends DELs that the user did not type.

## Impact of reversing

Item 1: remove the check of HT from `ImeEditor.flush`, and revise `committedTabIsSentAndResetsLikeTheTabKey` and sections 4.2 and 5.1 of [ime-contract.md](../design/ime-contract.md). Item 2: make `TerminalSession` apply the modifiers to `HostInput.Delete`, and revise section 8 of [compose-input.md](../design/compose-input.md).
