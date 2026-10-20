# Desktop pointer input and the terminal's state

- Status: Approved (2026-10-15, approved by a human in a review of the review queue. The shape of `TerminalState` and of the right click were chosen by a human on 2026-10-14; the agent decided the details); the copy path of `copySelection()` superseded by [0042](0042-user-clipboard-through-gate.md), and `TerminalLinkClick` as a data class by [0037](0037-api-shape-before-1.0.md)
- Date: 2026-10-14
- Related: Requirement 5.4, [0012](0012-touch-and-selection.md) (touch and selection), [0019](0019-extensibility.md) (links), `TerminalState`, `TerminalMouse`, `TerminalGestures`

## Background

An app had no way to copy from its own menu or shortcut (Ctrl+Shift+C), and a mouse was handled as touch: Chromebooks, DeX, and tablets with a mouse need desktop input. [0012](0012-touch-and-selection.md) decided only touch: the selection was reachable only from the long press menu.

## Decision

### `TerminalState`

- A Compose state holder: `val state = rememberTerminalState()`, passed as `Terminal(session, state = state)`. By default, `Terminal` remembers one that the app cannot reach. It has room for more state later (the scroll position, for example).
- `hasSelection` is backed by snapshot state, so a composable that reads it (an enabled Copy item) is recomposed when the selection changes. The selection stays in the display's coordinates and is dropped when the display changes, as [0012](0012-touch-and-selection.md) decided.
- `copySelection()` copies through the session's `ClipboardAccess`, as the menu's Copy does, so clipboard access stays in vtfx-gate-android. It keeps the selection, as Ctrl+C in a text field and Ctrl+Shift+C in desktop terminals do; the app calls `clearSelection()` if it wants it gone. The menu's Copy still drops it, as the menu goes away.
- `selectAll()` selects the scrollback and the screen, as the Select all of desktop terminals does; on the alternate screen, which has no scrollback, only the screen. The display highlights all that it shows.
- Copying all the text reads the newest 10,000 lines at most (fewer when they have more than 2 Mi cells, to bound the memory on the main thread), taken at once under the session's lock (`TerminalSession.lines`, internal), and keeps the newest whole lines that fit in 256 Ki characters. A clip goes to the system in one Binder transaction, which fails above about 1 MB, and 256 Ki characters are 512 KB of UTF-16. Empty lines at the end of the screen are left out.
- The menu (long press, and the right click below) has Copy (only while there is a selection), Paste, and Select all, which selects all and shows the menu again to copy.
- Not attached to a `Terminal` (before it enters the composition, after it leaves): `hasSelection` is false and the functions do nothing. Passed to two terminals at once: the one that entered the composition last wins, and when it leaves, the state acts on none until a terminal enters again. Throwing was not chosen: during an animated transition, the old and the new screen are composed together for a moment, with the same state.
- Main thread only, as Compose state is. No run-time check, as for the other internal display state.
- During synchronized output (mode 2026), the display defers redraws and the drop of the selection for up to 200 ms, while `copySelection()` and the link of a click read the current screen, so the host can change the text under a selection or the OSC 8 link of a cell without the user seeing it. This is kept (2026-10-15, decided by a human after a security review): the window is short, the URL is shown before it is opened (`TerminalSession.openUrl`), and it was not reproduced. Using the snapshot that the display drew, or ignoring copies and link clicks while a redraw is deferred, were the alternatives.

### Mouse

A pointer whose `PointerType` is `Mouse` is handled by `terminalMouse`, over the grid and its padding; touch, stylus, and anything else keep the gestures of [0012](0012-touch-and-selection.md). The mouse's events are consumed in the initial pass, so the touch gestures and the ancestors' scrolling do not see them.

| Input | Host does not ask for the mouse | Host asks for the mouse (modes 9, 1000, 1002, 1003) |
|---|---|---|
| Wheel | Scrolls back through the scrollback, 3 lines a notch; on the alternate screen, 3 arrow keys a notch (as a swipe, xterm's alternateScroll) | Wheel buttons 64 and 65, one report a notch, at the pointer's cell |
| Primary button | A drag selects from the press to the pointer; a double click selects a word and a triple click a line (a logical line, through the rows that autowrap joins, within the display), and the drag that follows extends from them; Shift+click extends the selection from its far end. A click without a drag drops the selection and is a tap: a link calls `onLinkClick`, else the terminal is focused and the software keyboard requested | Press and release reported; the terminal is focused as by a tap |
| Secondary button | `onContextClick` if the app gave one, else the menu of the long press (Copy, Paste, Select all) at the click. The selection stays | Press and release reported |
| Middle button | Nothing (Android has no primary selection to paste, and pasting into a shell by accident is a risk) | Press and release reported |
| Motion | The pointer icon: a hand over a link, the I-beam elsewhere | With a button held: reported in mode 1002 and 1003 when the cell changes; without: reported in mode 1003 when the cell changes. The pointer icon is the arrow |

- **Shift** leaves the mouse to the terminal while the host asks for it, as in xterm: Shift+drag selects, Shift+wheel scrolls, and Shift+click on a link calls `onLinkClick`. The reports carry Alt and Ctrl (bits 8 and 16); Shift never reaches the host from the mouse.
- **Scrolled back** (2026-10-15, decided by a human after a security review): while the display shows the scrollback, the mouse is the terminal's even if the host asks for it, exactly as if Shift were held (the left column of the table): a drag selects, the wheel scrolls, a click on a link calls `onLinkClick`, and the right click shows the menu or calls `onContextClick`. A report carries only a row of the screen, so a click on a row of the scrollback would reach the host as a click on a row that the user does not see (vim with `mouse=a` moves its cursor there). Once the display is back at the bottom, the host gets the mouse again; turning the wheel down to the bottom hands it back, and the next notch is reported. The agent decided the details:
  - "Scrolled back" is a scroll offset above 0 on the main screen. The alternate screen has no scrollback to show, so the offset that the main screen kept does not count there.
  - A button's press decides for its drag and its release, so that the host never gets a press without its release or a release without its press: a press reported at the bottom is released to the host even if Shift+wheel scrolls back before the release, and a press made scrolled back is not reported, nor its release, even if the wheel reaches the bottom before the release. Each scroll event of the wheel decides for itself.
  - Motion without a button (mode 1003) is not reported while scrolled back, and the pointer icon is the terminal's (a hand over a link, the I-beam elsewhere). After the wheel scrolls the scrollback, the icon follows without waiting for the pointer to move.
  - Touch, which also reports to the host ([0012](0012-touch-and-selection.md)), follows the same rule: while scrolled back, a tap is a tap (a link calls `onLinkClick`, else the keyboard is requested) and is not reported, and a swipe scrolls the scrollback. A swipe decides when it starts, so that a swipe that reaches the bottom stops there instead of going on to scroll the host; the next swipe goes to the host.
- **Wheel steps.** The wheel's delta (`scrollDelta`, 1 a notch, in fractions for high-resolution wheels and touchpads) is added up, and each whole notch scrolls. Horizontal scrolling (buttons 66 and 67) is not reported: `MouseButton` has no such buttons, and few hosts use them.
- **Encodings.** vtfx-gate already encoded X10, normal, button-event and any-event tracking in the default, UTF-8, SGR, and urxvt encodings, and drops the events that the mode does not ask for; nothing was missing. Motion is sent only when the cell changes, so a fine mouse does not flood the host.
- **Multiple clicks** count when a press comes within the double-tap timeout of the last one, on the same cell, and go round from a triple click to a single one.
- **No menu after a mouse selection.** Desktop apps do not pop a toolbar when the mouse selects; the right click shows it.
- **Focus and the software keyboard.** A click focuses the terminal and asks for the software keyboard, as a tap does. With a hardware keyboard attached, Android normally does not show it (Gboard's "Show on-screen keyboard" is off by default then), so vtfx does not try to tell desktop-like devices apart.
- **`onContextClick: ((Offset) -> Unit)?`** gets the position in pixels from the top left of the `Terminal` (within the padding of its `modifier`). The app reads `TerminalState.hasSelection` to offer Copy, so the callback needs nothing else.
- **Touchpads.** Android reports a touchpad's taps and pointer moves with `PointerType.Touch` or `Mouse` depending on the device (not checked); two-finger scrolling arrives as scroll events or as a touch swipe, both of which scroll. Whether a touchpad behaves as a mouse is in [review-queue.md](../review-queue.md).

### Link clicks

- `onLinkClick` takes a `TerminalLinkClick`: the `TerminalLink`, the `PointerType` of the pointer that clicked, and the `PointerKeyboardModifiers` held, Compose's own types, so that an app can open links only on Ctrl+click with a mouse and still on a tap. A tap reports `PointerType.Touch` and the modifiers of its release event.
- Activation without a pointer reports `PointerType.Unknown` and no modifiers (`PointerKeyboardModifiers(0)`). No such path exists today: a screen reader's double tap on a row becomes a tap ([0015](0015-accessibility.md)).
- A data class, like `TerminalLink`, with room for more properties (the position, for example) before 1.0.
- With a mouse while the host asks for the mouse, a click goes to the host, links included, unless Shift is held; then the click is the terminal's, and calls `onLinkClick` with Shift in the modifiers. Ctrl+click goes to the host then (with the Ctrl bit), so an app that opens links on Ctrl+click opens them only while the host does not ask for the mouse, or on Ctrl+Shift+click. While the display shows the scrollback, every click is the terminal's, and calls `onLinkClick` with the modifiers held, Ctrl included.

## Alternatives considered

- **Methods on `TerminalSession` (`session.copySelection()`)**: The selection belongs to one display of the session ([0007](0007-compose-session.md)); a session shown twice has two selections, and a session without a UI has none.
- **`selectAll()` selecting only what the display shows**: Simpler, and it would need no new access to the scrollback, but it is not what Select all means in desktop terminals.
- **No limit on copying all the text**: With a large scrollback, the clip fails on the device (`TransactionTooLargeException`), and the text is built on the main thread.

- **`onLinkClick: (TerminalLink, PointerType, PointerKeyboardModifiers) -> Unit`**: Every new property would break every caller; a class can grow.
- **vtfx's own modifier flags instead of `PointerKeyboardModifiers`**: Duplicates Compose's type, whose `isCtrlPressed` and the other extensions apps already know.

- **Ctrl+click on a link overriding mouse reports**: Ctrl+click is a meaningful report for some hosts (bit 16), and Shift is already the xterm convention for taking the mouse back.
- **Reporting the mouse while scrolled back, at the screen's rows**: What was done before the security review; the host gets clicks on rows that the user does not see.
- **Scrolling to the bottom on a click, then reporting it while scrolled back**: The click lands on a row that the user did not aim at.
- **Dropping the mouse while scrolled back**: The scrollback could not be selected or scrolled with the mouse while the host asks for it, without Shift.
- **A toolbar after every mouse selection**: Usual on touch, unusual with a mouse, where the right click asks for the menu.
- **Middle click pasting from the clipboard**: X's middle click pastes the primary selection, which Android lacks; pasting the clipboard into a shell on an accidental middle click could run commands.
- **Treating every pointer with buttons as a mouse (touchpads, stylus buttons)**: The device reports differ, and could not be checked without devices; the stylus keeps touch gestures, which suit handwriting-style use.

## Impact of reversing

- `TerminalState` is public API; removing it means a new way for the app to reach the selection.
- To change what Select all covers, change `TerminalDisplay.selectAll` and `selectedText`, and the KDoc of `TerminalState`.
- The mouse's behavior is in `MouseHandler` (`TerminalMouse.kt`); the table above and the KDoc of `Terminal` follow it.
- Whether the host gets the mouse while scrolled back is `isReporting` in `TerminalMouse.kt` and `isReportingTouches` in `TerminalGestures.kt`, both through `TerminalDisplay.isScrolledBack`.
