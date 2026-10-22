# A line feed after a paste in the same batch edit is pasted too

- Status: Provisional (decided by a human after a check on the emulator, 2026-10-22)
- Date: 2026-10-22
- Related: Requirement 6.1, sections 4.2 and 11 of [ime-contract.md](../design/ime-contract.md), [0050](0050-ime-text-of-several-lines-is-a-paste.md), `ImeEditor.commitText`

## Background

[0050](0050-ime-text-of-several-lines-is-a-paste.md) makes text that the IME commits with a line break a paste, so that a clipboard suggestion of Gboard reaches the shell bracketed and its lines do not run one by one. On the emulator, Gboard 18.4 commits such a suggestion in two calls in one batch edit: `commitText` with the text up to its trailing line feed, then `commitText` with the line feed alone. The line feed alone is Enter under 0050, so a suggestion of several lines that ends with a line feed was pasted and then run at once by the Enter after it, which bracketed paste is there to prevent; and a suggestion of one line and a line feed was typed and run.

## Decision

- **A line feed alone that the IME commits after a paste in the same batch edit is a paste too**, sent bracketed like the paste before it, so the shell inserts it without running the lines. The editing model keeps whether the batch edit in progress has sent a paste, and forgets it when the outermost batch edit ends or the connection closes. It need not directly follow the paste: Gboard does not commit Enter in the batch edit of a paste.
- **A suggestion of one line and a line feed stays as it is**: the line is typed and the line feed is Enter, and the line runs. A human judged this acceptable: the line is visible in the suggestion, and nothing hides in it the way lines after the first can.

## Alternatives considered

- **Treat a line feed alone that follows any commit in the same batch edit as a paste**: this would also bracket a suggestion of one line, but an IME that commits a word and Enter in one batch edit would then bracket every Enter, and the shell would not run the line.
- **Join the commits of a batch edit and decide at its end**: the editing model sends a paste at once, also during a batch edit (6.4), so it would hold pastes back and change when they reach the host.
- **Leave Gboard's line feed as Enter**: the lines of a suggestion with a trailing line feed run without being looked at, which is the case 0050 is for.

## Impact of reversing

Remove `pastedInBatch` from `ImeEditor` and its use in `commitText`, the tests of a line feed after a paste in `ImeEditorTest` and `TerminalSessionTest`, and the sentences on it in sections 4.2 and 11 of [ime-contract.md](../design/ime-contract.md).
