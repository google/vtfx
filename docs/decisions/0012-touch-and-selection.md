# Touch interaction, selection, extra key bar

- Status: Approved (2026-10-13, approved by a human in the final review)
- Date: 2026-10-12
- Related: Requirement 5.4, `TerminalGestures`, `TerminalSelection`, `TerminalSession.stickyModifiers`

## Background

Requirement 5.4 asks for physical keyboards and modifier keys, touch scrolling and mouse reporting, text selection, paste, and a means to build an extra key bar. The meaning of touch interactions changes depending on whether the host requests the mouse and so on, so it needed to be decided.

## Decision

### Touch

| Interaction | Normal | Alternate screen | Host requests the mouse |
|---|---|---|---|
| Tap | Shows the keyboard. Clears the selection | Same as left | In addition to the same as left, reports a left click |
| Vertical swipe | Scrolls back through scrollback | Sends arrow keys (same as xterm's alternateScroll) | Reports the wheel |
| Long press | Selects a word, and moving the finger extends the range. Releasing shows the copy and paste menu | Same as left | Same as left (mouse dragging is not reported) |

- Moving the finger down shows earlier content (the same direction as turning the wheel up).
- When showing scrollback, the viewed lines do not move even when new output arrives. Typing returns to the bottom.
- Selecting by dragging in apps that request the mouse (visual selection with vim's `mouse=a` and so on) cannot be done by touch. Using swipes for scrolling was given priority.
- A tap on a hyperlink (OSC 8) cell calls the `onLinkClick` the app passed to `Terminal`, and does not show the keyboard (added in M13 as `onHyperlinkClick`, and replaced in [0019](0019-extensibility.md) with `onLinkClick`, which also covers links in the text). Links are opened by `TerminalSession.openUrl` after the app shows the URL and confirms; vtfx never opens a link just from a tap (requirement 6.1, [0006](0006-gate-defaults.md)). If `onLinkClick` is not passed, links cannot be tapped. When the host requests the mouse, taps are reported to the host regardless of links.

### Selection

- The selection is held in display coordinates. It is cleared when the display changes because of output, scrolling, input, or size changes. The selection cannot be kept in apps with continuous output (htop and so on), but this keeps it simple by avoiding tracking coordinates (across eviction from scrollback and reflow).
- If the selection is cleared while selecting by long press, that long press does not recreate a selection or show the menu until the finger is released. The display coordinates at the start of the long press point to a different character in the new display, or fall outside the range (found in a code review).
- The copied text has the trailing whitespace of each line removed, with lines separated by newlines. Rows wrapped by autowrap are joined without a newline. A fullwidth character is included in full even if only half of it is selected.
- Copying writes to the `ClipboardAccess` passed to the session. It is the user's action, so the OSC 52 policy (0006) does not apply.
- 2026-10-14: A mouse has its own behavior: drags select, and the host gets drags when it asks for the mouse ([0031](0031-desktop-pointer-and-terminal-state.md)). The table above is for touch.
- 2026-10-14: The menu also has Select all, which selects the scrollback and the screen, and the app reaches the selection through `TerminalState` ([0031](0031-desktop-pointer-and-terminal-state.md)).
- 2026-10-15: While the display shows the scrollback, taps and swipes act as in the Normal column even if the host requests the mouse, until the display is back at the bottom; a swipe that starts in the scrollback stops at the bottom (decided by a human after a security review, [0031](0031-desktop-pointer-and-terminal-state.md)).

### Extra key bar

- The library does not include a key bar UI (requirement 5.4). The app sends keys with `TerminalSession.sendKey`, and setting Ctrl or Alt in `stickyModifiers` applies the modifier to the next typed key or character. Modifiers are cleared after one use.
- When a modifier is applied to the first of multiple characters committed by the IME, the IME's input history and what the host received diverge, so the input history is reset.
- The sample app has a key bar that uses this API (Esc, Tab, Ctrl, Alt, arrows).

### Keys

- The numeric keypad sends SS3 sequences with DECKPAM (or DECNKM) (the same as xterm with NumLock off).

### Cursor blinking

- Only when the host specifies blinking (DECSCUSR, mode 12), the cursor blinks every 0.5 seconds while focused. The character blink attribute (SGR 5) is not drawn.
- Each input from the user (a key, an IME edit, a paste, or input sent with the session's API) shows the cursor and starts its blink phase again, so that the cursor stays on while the user types, also when the host does not echo. So does output from the host that moves the cursor, as in xterm.js, which restarts the blinking when the cursor's position differs after the host's output; output that leaves the cursor where it was, such as a redraw that returns it, does not. The position is compared each time the display updates (added 2026-10-15 at a human's request).

## Alternatives considered

- **Report drags in apps that request the mouse as mouse drags**: There would be no means of scrolling. Assigning it to two-finger gestures is an option, but it tends to conflict with the IME and system gestures.
- **Hold the selection in absolute scrollback positions and keep it across output**: Positions change with eviction due to the scrollback limit and with the reflow of M10. Revisit if needed.

## Impact of reversing

Change the branches in `TerminalGestures` and the triggers for clearing the selection in `TerminalSession`.
