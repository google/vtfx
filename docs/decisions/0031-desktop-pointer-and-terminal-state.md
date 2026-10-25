# Desktop pointer input and the terminal's state

- Status: Approved (2026-10-15, approved by a human in a review of the review queue. The shape of `TerminalState` and of the right click were chosen by a human on 2026-10-14; the agent decided the details); the copy path of `copySelection()` superseded by [0083](0083-gate.md), and `TerminalLinkClick` as a data class by [0037](0037-api-shape-before-1.0.md)
- Date: 2026-10-14
- Related: Requirement 5.4, sections 1, 3, 4, 5, and 6.1 of [compose-input.md](../design/compose-input.md), [0012](0012-touch-and-selection.md) (touch and selection), [0019](0019-extensibility.md) (links), `TerminalState`, `TerminalMouse`, `TerminalGestures`

## Background

An app had no way to copy from its own menu or shortcut (Ctrl+Shift+C), and a mouse was handled as touch: Chromebooks, DeX, and tablets with a mouse need desktop input. [0012](0012-touch-and-selection.md) decided only touch: the selection was reachable only from the long press menu.

## Decision

### `TerminalState`

A Compose state holder that the app passes to `Terminal` and through which it reads and changes the selection: `hasSelection` (snapshot state), `copySelection()`, `selectAll()`, and `clearSelection()` ([compose-input.md](../design/compose-input.md) section 5). The agent decided the details:

- `copySelection()` keeps the selection, as Ctrl+C in a text field and Ctrl+Shift+C in desktop terminals do; the app calls `clearSelection()` if it wants it gone. The menu's Copy still drops it, as the menu goes away.
- `selectAll()` selects the scrollback and the screen, as the Select all of desktop terminals does, and the long-press menu gets Select all too. Copying all the text is bounded ([compose-input.md](../design/compose-input.md) section 4): a clip goes to the system in one Binder transaction, which fails above about 1 MB, and the text is built on the main thread.
- A state not attached to a `Terminal` does nothing, and a state passed to two terminals acts on the one that entered the composition last. Throwing was not chosen: during an animated transition, the old and the new screen are composed together for a moment, with the same state.
- Main thread only, as Compose state is, with no run-time check, as for the other internal display state.
- During synchronized output (mode 2026), copying and the link of a click read the current screen while the display defers its redraw for up to 200 ms, so the host can change the text under a selection or the OSC 8 link of a cell without the user seeing it. This is kept (decided by a human after a security review): the window is short, the URL is shown before it is opened (`TerminalSession.openUrl`), and it was not reproduced.

### Mouse

A pointer whose `PointerType` is `Mouse` gets desktop behavior; touch, stylus, and anything else keep the gestures of [0012](0012-touch-and-selection.md). The behavior is in section 3 of [compose-input.md](../design/compose-input.md). Its principles:

- Without mouse reporting, the primary button selects as in desktop terminals (drag, double and triple click, Shift+click), the wheel scrolls as a swipe does, and the secondary button shows the long press menu or calls the app's `onContextClick`, for the app's own menu. A click without a drag is a tap.
- With mouse reporting, the host gets the buttons, the wheel, and the motion it asked for. **Shift** leaves the mouse to the terminal, as in xterm, and Shift never reaches the host from the mouse.
- **Scrolled back** (decided by a human after a security review): while the display shows the scrollback, the mouse is the terminal's even if the host asks for it, exactly as if Shift were held, and so are touches. A report carries only a row of the screen, so a click on a row of the scrollback would reach the host as a click on a row that the user does not see (vim with `mouse=a` moves its cursor there). Once the display is back at the bottom, the host gets the mouse again. The agent decided that a button's press decides for its drag and its release, so that the host never gets a press without its release or a release without its press, and that a swipe decides when it starts, so that a swipe that reaches the bottom stops there.
- The middle button does nothing without reporting: Android has no primary selection to paste, and pasting into a shell by accident is a risk.
- No menu after a mouse selection: desktop apps do not pop a toolbar when the mouse selects; the right click shows it.
- A click focuses the terminal and asks for the software keyboard, as a tap does. With a hardware keyboard attached, Android normally does not show it (Gboard's "Show on-screen keyboard" is off by default then), so vtfx does not try to tell desktop-like devices apart.
- Horizontal scrolling is not reported: `MouseButton` has no such buttons, and few hosts use them.

### Link clicks

`onLinkClick` takes a `TerminalLinkClick`: the `TerminalLink`, the `PointerType` of the pointer that clicked, and the `PointerKeyboardModifiers` held, Compose's own types, so that an app can open links only on Ctrl+click with a mouse and still on a tap. It is a class, with room for more properties (the position, for example). With a mouse while the host asks for the mouse, a click on a link goes to the host unless Shift is held ([compose-input.md](../design/compose-input.md) section 6.1).

## Alternatives considered

- **Methods on `TerminalSession` (`session.copySelection()`)**: The selection belongs to one display of the session ([0007](0007-compose-session.md)); a session shown twice has two selections, and a session without a UI has none.
- **`selectAll()` selecting only what the display shows**: Simpler, and it would need no new access to the scrollback, but it is not what Select all means in desktop terminals.
- **No limit on copying all the text**: With a large scrollback, the clip fails on the device (`TransactionTooLargeException`), and the text is built on the main thread.
- **Using the snapshot that the display drew for copies and link clicks, or ignoring them while a redraw is deferred**: Not taken; see the synchronized output above.
- **`onLinkClick: (TerminalLink, PointerType, PointerKeyboardModifiers) -> Unit`**: Every new property would break every caller; a class can grow.
- **vtfx's own modifier flags instead of `PointerKeyboardModifiers`**: Duplicates Compose's type, whose `isCtrlPressed` and the other extensions apps already know.
- **Ctrl+click on a link overriding mouse reports**: Ctrl+click is a meaningful report for some hosts (bit 16), and Shift is already the xterm convention for taking the mouse back.
- **Reporting the mouse while scrolled back, at the screen's rows**: The host gets clicks on rows that the user does not see.
- **Scrolling to the bottom on a click, then reporting it while scrolled back**: The click lands on a row that the user did not aim at.
- **Dropping the mouse while scrolled back**: The scrollback could not be selected or scrolled with the mouse while the host asks for it, without Shift.
- **A toolbar after every mouse selection**: Usual on touch, unusual with a mouse, where the right click asks for the menu.
- **Middle click pasting from the clipboard**: X's middle click pastes the primary selection, which Android lacks; pasting the clipboard into a shell on an accidental middle click could run commands.
- **Treating every pointer with buttons as a mouse (touchpads, stylus buttons)**: The device reports differ, and could not be checked without devices; the stylus keeps touch gestures, which suit handwriting-style use.

## Impact of reversing

- `TerminalState` is public API; removing it means a new way for the app to reach the selection.
- To change what Select all covers, change `TerminalDisplay.selectAll` and `selectedText`, and the KDoc of `TerminalState`.
- The mouse's behavior is in `MouseHandler` (`TerminalMouse.kt`); section 3 of [compose-input.md](../design/compose-input.md) and the KDoc of `Terminal` follow it.
- Whether the host gets the mouse while scrolled back is `isReporting` in `TerminalMouse.kt` and `isReportingTouches` in `TerminalGestures.kt`, both through `TerminalDisplay.isScrolledBack`.
