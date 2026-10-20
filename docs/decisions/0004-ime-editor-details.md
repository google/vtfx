# Details of the IME editing model

- Status: Approved (2026-10-13, approved by a human in the final review)
- Date: 2026-10-12
- Related: [ime-contract.md](../design/ime-contract.md), requirement 5.3, `ImeEditor` in `vtfx-input`

## Background

The IME contract defines the effect of each call, but implementing it revealed points that the text of the contract alone does not settle, and points where following the text literally is inconvenient.

## Decision

| Item | Decision | Reason |
|---|---|---|
| Limit of the input history (an open question in section 10 of the contract) | 1024 code points. Reset when exceeded. Can be changed with an argument of `ImeEditor`. | Long enough for a one-line command. Limiting the text shown to the IME also suits privacy (requirement 6.1). |
| Range of `setComposingRegion` | Only ranges ending at the cursor (the end of the editing buffer) are accepted. An empty range is treated as `finishComposingText`. | Making a range that does not end at the end into composing text would leave committed text after the composing text, breaking invariant 1. The handling of an empty range is the same as `BaseInputConnection`. |
| `replaceText` and composing text | Commit the composing text, then replace. The result of the replacement becomes input history. | `replaceText` is used to correct committed text (autocorrect, double-space period). No case in which the replaced range touches composing text has been observed. |
| A character key arrives during composition | Commit the composing text and add the character after it. | Applying "handled the same as `commitText`" in 6.3 of the contract literally would replace the composing text with the character, losing the user's input. |
| BS or a non-character key arrives during composition | Commit and send the composing text, then send the key. | The note in 6.3 of the contract (non-character keys from an extra key bar) is applied equally to keys from the IME and physical keyboards. The order of sending matches the order of input. |
| Key up | All ignored (keys act on down). | This subsumes "ignore an up without a corresponding down" in 6.3 of the contract. No state is needed to track the correspondence of down and up. |
| The part of `deleteSurroundingText` outside the range | Send as many DELs as UTF-16 units. | The content of the text outside the range is unknown, so it cannot be converted to code points. When the IME erases outside the range, it is in place of BS, and measurements show one unit at a time. |
| Control characters not sent (4.2 of the contract) | `ImeEditor` removes them from committed, composing, replacement, and character key text, and does not put them in the editing buffer. The gate removes the same characters. A test confirms that the two rules agree (`DroppedControlsAgreementTest` in `vtfx-session`; 2026-10-20, correction: this said the test was in `vtfx-compose`). | Leaving them in the editing buffer would make the input history longer than what reached the host (a violation of invariant 3), and later differences would send DELs even for characters not sent, erasing the host's characters before the input history (found in a code review). Compared to counting only the characters sent, the text shown to the IME also matches the host. Putting the removal only in the gate would need a path returning the gate's result to the editing model, so it is not taken. |
| A committed newline | Output as the Enter key (`HostInput.Key`) without including the newline in `HostInput.Text`. CR LF is one Enter. | What Enter sends (CR, or CR LF with LNM) can be centralized in key encoding. The result is the same as "turn `\n`, `\r\n`, `\r` into CR" in 4.2 of the contract. |

## Alternatives considered

- **Accept ranges other than at the end in `setComposingRegion`, and hold the text after it as a continuation of the input history**: The buffer structure would become "history + composing + history", complicating the computation of differences and sending DELs. Not taken until a need is observed.

## Impact of reversing

Change the corresponding methods of `ImeEditor` and the corresponding tests in `ImeEditorTest`. No trace in spec/ime goes through these branches.
