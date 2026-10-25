# Touch interaction, selection, extra key bar

- Status: Approved (2026-10-13, approved by a human in the final review); the copy path (copying through the session's `ClipboardAccess`) superseded by [0083](0083-gate.md), and reporting taps and swipes to the host while the display shows the scrollback by [0031](0031-desktop-pointer-and-terminal-state.md)
- Date: 2026-10-12
- Related: Requirement 5.4, sections 2, 4, 7.1, 8, and 9 of [compose-input.md](../design/compose-input.md), [0031](0031-desktop-pointer-and-terminal-state.md) (the mouse), `TerminalGestures`, `TerminalSelection`, `TerminalSession.stickyModifiers`

## Background

Requirement 5.4 asks for physical keyboards and modifier keys, touch scrolling and mouse reporting, text selection, paste, and a means to build an extra key bar. The meaning of touch interactions changes depending on whether the host requests the mouse and so on, so it needed to be decided.

## Decision

- **Touch** ([compose-input.md](../design/compose-input.md) section 2): a tap shows the keyboard, and is also reported as a click when the host asks for the mouse; a vertical swipe scrolls the scrollback, sends arrow keys on the alternate screen (as xterm's alternateScroll), or reports the wheel when the host asks for the mouse; a long press selects a word, extends it as the finger moves, and shows a menu to copy and paste. Drags are never reported to the host from touch: swipes are kept for scrolling. Moving the finger down shows earlier content, and the viewed lines of the scrollback stay put as output arrives until the user types.
- **Links**: a tap on a link calls the app's callback instead of showing the keyboard, and vtfx never opens a link from a tap: the app shows the URL and opens it with `TerminalSession.openUrl` when the user confirms (requirement 6.1, [0083](0083-gate.md)). Without the callback, links cannot be tapped. When the host asks for the mouse, taps go to the host regardless of links.
- **Selection** ([compose-input.md](../design/compose-input.md) section 4): held in display coordinates, and dropped whenever the display changes (output, scrolling, input, size). If the selection is dropped during a long press, that long press selects nothing more and shows no menu. The copied text drops the trailing blanks of each line, joins the rows that autowrap joined, and includes a wide character whole. Copying is the user's action, so the OSC 52 policy ([0083](0083-gate.md)) does not apply.
- **Extra key bar** ([compose-input.md](../design/compose-input.md) section 8): the library has no key bar UI. The app sends keys with `TerminalSession.sendKey`, and sets Ctrl or Alt in `stickyModifiers` to apply them to the next typed key or character, after which they are cleared. When they change what the host gets, the IME's input history is reset. The sample app has a key bar that uses this API.
- **Keys** ([compose-input.md](../design/compose-input.md) section 7.1): the numeric keypad sends SS3 sequences with DECKPAM (or DECNKM), the same as xterm with NumLock off.
- **Cursor blinking** ([compose-input.md](../design/compose-input.md) section 9): the cursor blinks only when its style asks for it, and only while focused; the character blink attribute (SGR 5) is not drawn. The user's input, and output that moves the cursor (as in xterm.js), show the cursor and restart its phase, so that the cursor stays on while the user types, also when the host does not echo.

## Alternatives considered

- **Report drags in apps that request the mouse as mouse drags**: There would be no means of scrolling. Assigning it to two-finger gestures is an option, but it tends to conflict with the IME and system gestures.
- **Hold the selection in absolute scrollback positions and keep it across output**: Positions change with eviction due to the scrollback limit and with reflow. Revisit if needed.

## Impact of reversing

Change the branches in `TerminalGestures` and the triggers for clearing the selection in `TerminalDisplay`, and sections 2 and 4 of [compose-input.md](../design/compose-input.md).
