# Features of alacritty_terminal that vtfx lacks

- Status: Approved (2026-10-23, approved by a human in a review of the review queue; which features to support was chosen by a human)
- Date: 2026-10-23
- Related: Requirements 4 and 5.4, section 4 of [emulation.md](../design/emulation.md), sections 2 to 4 of [compose-input.md](../design/compose-input.md), [0005](0005-key-bytes-through-gate.md), [0009](0009-emulation-scope.md), [0012](0012-touch-and-selection.md), [0019](0019-extensibility.md), [0031](0031-desktop-pointer-and-terminal-state.md), [0047](0047-search.md)

## Background

alacritty_terminal, the terminal library of Alacritty (version 0.26.1-dev, with the `vte` 0.15 parser), has features that vtfx does not have. Some of these were already left out on purpose, with reasons recorded:

- Setting colors from the host (OSC 4, 10, 11, 12, 104, 110–112): [0009](0009-emulation-scope.md).
- Searching with regular expressions: item 4 of [0047](0047-search.md).
- Creating PTYs: section 4.2 of [REQUIREMENTS.md](../REQUIREMENTS.md).

The others had no recorded decision: the mouse pointer shape (OSC 22), the cursor shape by OSC 50, urgency hints (mode 1042), alternate scroll (mode 1007), a vi-like keyboard mode for the scrollback, block selection, selection of words bounded by punctuation (Alacritty's semantic selection), and the keyboard protocols (the kitty keyboard protocol, and xterm's modifyOtherKeys, which `vte` parses although alacritty_terminal does nothing with it). This decision says which of them vtfx takes.

## Decision

1. **The mouse pointer shape (OSC 22) is not supported.** The sequence is ignored. The pointer icon is vtfx's: a hand over a link, the I-beam elsewhere, and the arrow while the host gets the mouse (section 3 of [compose-input.md](../design/compose-input.md)). A shape chosen by the host would compete with the link's, and major apps do not send it.
2. **The cursor shape by OSC 50 (`CursorShape=`) is not supported.** It is an extension of Konsole and iTerm2 that DECSCUSR covers. In xterm, OSC 50 sets the font instead, which vtfx does not let the host do either ([fonts.md](../design/fonts.md)), so the number does not mean the same in every terminal.
3. **Urgency hints (mode 1042) are not supported.** The mode makes the bell mark the window as urgent, which X11 has and Android does not. The session already reports bells to the app (`TerminalSessionListener.onBell`), and the app decides how to tell the user.
4. **A vi-like keyboard mode for moving through the scrollback and selecting is not offered.** The library is used mostly by touch, where such a mode is of little use, and its keys would compete with the app's shortcuts ([0032](0032-keys-and-layout.md)). Revisit if desktop use with a keyboard ([0031](0031-desktop-pointer-and-terminal-state.md)) asks for it.
5. **Block selection is not offered for now.** It helps to copy a column of a table, but it has no natural gesture on touch, and the rules of copying (the trailing blanks, a wide character at either edge, rows that autowrap joins) would have to be decided again for a rectangle. Revisit when there is demand.
6. **Words bounded by punctuation are to be supported.** A word is now the run of non-blank characters around a cell (section 4 of [compose-input.md](../design/compose-input.md)), so a double click or a long press on `"foo/bar.txt"` or `(https://example.com)` takes the quotes or the parentheses too. Words will also end at a set of delimiters. The set, and whether the app can change it, are decided with the implementation, in a decision of their own.
7. **Alternate scroll (mode 1007) is to be supported.** vtfx always turns swipes and the wheel on the alternate screen into arrow keys (sections 2 and 3 of [compose-input.md](../design/compose-input.md)), and the host cannot turn this off, so an app on the alternate screen in which the arrow keys do something else (a REPL's history) gets them anyway. The mode will be set by default, keeping the current behavior; while the host resets it, swipes and the wheel on the alternate screen send nothing, and DECRQM reports it. It costs a flag in `TerminalModes`.
8. **The keyboard protocols (the kitty keyboard protocol and modifyOtherKeys) are decided apart**, in a decision of their own. They change the bytes that the gate sends for keys, which [0005](0005-key-bytes-through-gate.md) limits to what xterm encodes: modifyOtherKeys stays within that, and the kitty keyboard protocol does not.

## Alternatives considered

- **Support OSC 22**: The pointer icon over links would depend on what the host last set, for a sequence that major apps do not send.
- **Support OSC 50 as `CursorShape=`**: Gives a second way to do what DECSCUSR does, under a number that means the font in xterm.
- **A vi-like mode in the library**: A large state machine of keys and a second cursor, mainly for keyboard use, which the app could build if the library let it set the selection.
- **Block selection now**: The rules of copying a rectangle and a gesture for touch would have to be designed for a use that nobody has asked for yet.
- **Keep the words as runs of non-blank characters**: The simplest rule, but the usual targets of a double click, paths and URLs in quotes or parentheses, need the selection fixed by hand on a phone.
- **Leave mode 1007 out**: Few apps reset it, but the conversion that it controls is always on, and the cost is small.

## Impact of reversing

- Items 1 to 3: handle the sequence in `Emulator` and add it to section 3 of emulation.md; for OSC 22, also the pointer icon in `terminalMouse` of `vtfx-compose`.
- Items 4 and 5: add the mode or the shape of selection to `TerminalSelection` and `TerminalDisplay`, the gestures, the copying, and section 4 of compose-input.md.
- Item 6: once implemented, go back to the runs of non-blank characters in the word selection of `vtfx-compose`.
- Item 7: once implemented, remove the flag and its report, and turn swipes and the wheel on the alternate screen into arrow keys again in every case.
