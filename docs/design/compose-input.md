# Interaction in the Compose UI

This document describes how the `Terminal` composable of `vtfx-compose` handles the user's interaction: touch, the mouse, the selection and copying, links, hardware keys and their interception by the app, the extra key bar, cursor blinking, its own size and padding, and accessibility. It describes the current design; the reasons are in the decisions linked from each section. The IME's calls are in [ime-contract.md](ime-contract.md), and the place of `vtfx-compose` among the modules in section 3.5 of [architecture.md](architecture.md). How an app uses these features is in [usage.md](../usage.md).

## 1. Terms and routing of pointers

- **The display**: what one `Terminal` shows of its session, held by `TerminalDisplay` (one per `Terminal`): the scroll position in the scrollback, the selection, and when it redraws ([decisions/0026](../decisions/0026-session-module.md)). Positions on the display are cells of the display, not of the screen.
- **The host asks for the mouse**: the mouse tracking mode is not off (modes 9, 1000, 1002, 1003).
- **Scrolled back**: the display shows lines of the scrollback, that is, its scroll offset is above 0 and the session has scrollback (`TerminalDisplay.isScrolledBack`). The alternate screen has no scrollback, so the offset that the main screen kept does not count there.
- **Reporting**: the host gets a pointer's input as mouse reports. It does when the host asks for the mouse and the display is not scrolled back, and, for a mouse, Shift is not held (`isReportingTouches` in `TerminalGestures.kt`, `isReporting` in `TerminalMouse.kt`). A report carries a row of the screen, so it cannot name a row of the scrollback ([decisions/0031](../decisions/0031-desktop-pointer-and-terminal-state.md)).

A pointer whose `PointerType` is `Mouse` is handled by `terminalMouse` (section 3), over the grid and the inner padding (section 10). Its events are consumed in the initial pass, so the touch gestures and the ancestors' scrolling do not see them. Touch, stylus, and any other pointer are handled by `terminalGestures` on the grid (section 2) ([decisions/0012](../decisions/0012-touch-and-selection.md), [decisions/0031](../decisions/0031-desktop-pointer-and-terminal-state.md)).

## 2. Touch

| Gesture | Not reporting, main screen | Not reporting, alternate screen | Reporting |
|---|---|---|---|
| Tap | Drops the selection. On a link, calls `onLinkClick` (section 6); else hides the menu, focuses the terminal, and shows the software keyboard | Same as left | Drops the selection, reports a left press and release at the cell (links included), and shows the keyboard |
| Vertical swipe | Scrolls through the scrollback, a line per cell height moved | Sends Up or Down arrow keys, one per cell height moved (as xterm's alternateScroll) | Reports the wheel (buttons 64 and 65), one report per cell height moved, at the finger's cell |
| Long press | Selects the word under it; moving the finger extends the selection; lifting it shows the menu (section 4) at the finger | Same as left | Same as left; drags are not reported |

- A long press is a press held for the long-press timeout without moving beyond the touch slop. Moving the finger extends the selection from the far end of the word to the cell under the finger.
- Moving the finger down shows earlier content, the same direction as turning a wheel up.
- A swipe decides whether it reports when it starts: a swipe that scrolls the scrollback down to the bottom stops there instead of going on to scroll the host, and the next swipe goes to the host.
- The arrow keys of a swipe are sent with `TerminalSession.sendScrollKey`, without the sticky modifiers (section 8), which stay armed.
- If the display changes during a long press, which drops the selection, the rest of that long press selects nothing and shows no menu: the cells under the finger now hold other text.
- A stylus acts as touch; its taps report `PointerType.Stylus` to `onLinkClick`.
- While scrolled back, the display keeps showing the same lines as output arrives. When those lines leave the scrollback, it shows the oldest lines that are kept, or the screen if the scrollback was cleared. The user's input (a key, an IME edit, a paste, input sent with the session's API) returns the display to the bottom.

## 3. Mouse

| Input | Not reporting | Reporting |
|---|---|---|
| Wheel | Scrolls through the scrollback, 3 lines a notch; on the alternate screen, sends 3 arrow keys a notch (as a swipe) | Wheel buttons 64 and 65, one report a notch, at the pointer's cell |
| Primary button | A drag selects from the press to the pointer; a double click selects a word and a triple click a line (a logical line, through the rows that autowrap joins, within the display), and the drag that follows extends from them; Shift+click extends the selection from its far end. A click without a drag drops the selection and is a tap: on a link it calls `onLinkClick`, else the terminal is focused and the software keyboard requested | Press and release reported; the terminal is focused as by a tap |
| Secondary button | `onContextClick` if the app gave one, else the menu of the long press at the click. The selection stays | Press and release reported |
| Middle button | Nothing | Press and release reported |
| Motion | The pointer icon: a hand over a link (when `onLinkClick` is given), the I-beam elsewhere | With a button held: reported in modes 1002 and 1003 when the cell changes; without: reported in mode 1003 when the cell changes. The pointer icon is the arrow |

- **Shift** leaves the mouse to the terminal while the host asks for it, as in xterm: Shift+drag selects, Shift+wheel scrolls, and Shift+click on a link calls `onLinkClick`. Reports carry Alt and Ctrl (bits 8 and 16); Shift never reaches the host from the mouse.
- **Scrolled back**, the mouse is the terminal's even if the host asks for it, exactly as if Shift were held: a drag selects, the wheel scrolls, a click on a link calls `onLinkClick`, and the right click shows the menu or calls `onContextClick`. Once the display is back at the bottom, the host gets the mouse again: turning the wheel down to the bottom hands it back, and the next notch is reported.
  - A button's press decides for its drag and its release, so that the host never gets a press without its release or a release without its press: a press reported at the bottom is released to the host even if Shift+wheel scrolls back before the release, and a press made while scrolled back is not reported, nor is its release, even if the wheel reaches the bottom before the release. Each scroll event of the wheel decides for itself.
  - Motion without a button is not reported while scrolled back, and the pointer icon is the terminal's. After the wheel scrolls the scrollback, the icon follows without waiting for the pointer to move.
- **Wheel steps.** The wheel's delta (`scrollDelta`, 1 a notch, in fractions for high-resolution wheels and touchpads) is added up, and each whole notch scrolls. Horizontal scrolling (buttons 66 and 67) is not reported.
- **Encodings.** `vtfx-gate` encodes X10, normal, button-event, and any-event tracking in the default, UTF-8, SGR, and urxvt encodings, and drops the events that the mode does not ask for. Motion is sent only when the cell changes, so that a fine mouse does not flood the host.
- **Multiple clicks** count when a press comes within the double-tap timeout of the last one, on the same cell, and go round from a triple click to a single one.
- **No menu after a mouse selection**; the right click shows it.
- **Focus and the software keyboard.** A click focuses the terminal and asks for the software keyboard, as a tap does. With a hardware keyboard attached, Android normally does not show it, so vtfx does not tell desktop-like devices apart.
- **`onContextClick: ((Offset) -> Unit)?`** gets the position in pixels from the top left of the `Terminal` (within the padding of its `modifier`). The app reads `TerminalState.hasSelection` (section 5) to offer Copy.
- **If the display changes during a drag**, the rest of the drag selects nothing, as with a long press.
- **Touchpads.** Android reports a touchpad's taps and pointer moves with `PointerType.Touch` or `Mouse` depending on the device; two-finger scrolling arrives as scroll events or as a touch swipe, both of which scroll. Whether a touchpad behaves as a mouse is not verified (section 12).

## 4. Selection and copying

- **Where it is held.** The selection is held by the `TerminalDisplay`, in the display's coordinates: an anchor and a focus cell, inclusive, in either order (`TerminalSelection`). A session shown by two `Terminal`s has two selections, and a session without a UI has none ([decisions/0031](../decisions/0031-desktop-pointer-and-terminal-state.md)).
- **When it is dropped.** Whenever the display changes: on output from the host, on scrolling, on the user's input, and on resizing ([decisions/0012](../decisions/0012-touch-and-selection.md)). A tap and a mouse click without a drag drop it too.
- **Units.** A word is the run of non-blank characters around a cell, or the cell alone if it is blank. A line is a logical line: its rows from the one that the previous row does not wrap into to the one that does not wrap, within the display.
- **The copied text.** The trailing blanks of each line are dropped, and lines are separated by `\n`, except rows that autowrap joined, which are joined without one. A wide character is included whole even if only half of it is selected. The blank that autowrap leaves before a wide character that did not fit is not text.
- **The copy path.** Copying goes through `TerminalSession.copyToClipboard` (internal API) to `TerminalGate.copyToClipboard` in `vtfx-gate`, which writes to the `ClipboardAccess` given to the session; it returns false when there is no clipboard. It is the user's action, so the OSC 52 policy does not apply ([decisions/0042](../decisions/0042-user-clipboard-through-gate.md)).
- **The menu.** A long press, or a right click without `onContextClick`, shows the text toolbar with Copy (only while there is a selection; it drops the selection, as the menu goes away), Paste (`TerminalSession.pasteFromClipboard`), and Select all, which selects all and shows the menu again to copy.
- **Select all** selects the scrollback and the screen; on the alternate screen, which has no scrollback, only the screen. The display highlights all that it shows. The copied text is that of the newest lines, at most 10,000 lines and, to bound the memory on the main thread, at most 2 Mi cells (`MAX_COPIED_LINES`, `MAX_COPIED_CELLS` in `TerminalDisplay`), read at once under the session's lock (`TerminalSession.lines`, internal). Empty lines at the end are left out. The text is cut to the newest whole lines that fit in 256 Ki characters (`MAX_COPIED_LENGTH`), or to the end of a single line longer than that, without splitting a surrogate pair: a clip goes to the system in one Binder transaction, which fails above about 1 MB, and 256 Ki characters are 512 KB of UTF-16.
- **Synchronized output.** During synchronized output (mode 2026), the display defers redraws, and so the drop of the selection, for up to 200 ms, while copying and the link of a click read the current screen. The host can therefore change the text under a selection, or the OSC 8 link of a cell, without the user seeing it for that window. This is accepted ([decisions/0031](../decisions/0031-desktop-pointer-and-terminal-state.md)).

## 5. `TerminalState`

`TerminalState` is the app's handle on the selection, for its own Copy command or shortcut ([decisions/0031](../decisions/0031-desktop-pointer-and-terminal-state.md)).

- Created with `rememberTerminalState()` and passed as `Terminal(session, state = state)`. By default, `Terminal` remembers one that the app cannot reach.
- `hasSelection` is backed by snapshot state, so a composable that reads it (an enabled Copy item) is recomposed when the selection changes.
- `copySelection()` copies as the menu's Copy does (section 4), but keeps the selection, as Ctrl+C in a text field and Ctrl+Shift+C in desktop terminals do; it returns whether there was a selection to copy and a clipboard to copy it to. `selectAll()` selects as the menu's Select all does. `clearSelection()` drops the selection.
- Not attached to a `Terminal` (before it enters the composition, after it leaves), `hasSelection` is false and the functions do nothing. Passed to two terminals at once, it acts on the one that entered the composition last; when that one leaves, it acts on none until a terminal enters again.
- Main thread only, as Compose state is, without a run-time check.

## 6. Links

### 6.1 Kinds of links and clicks

- `TerminalLink` is an abstract class with an internal constructor, so that only vtfx makes links and a `when` over the kinds needs an `else` branch ([decisions/0037](../decisions/0037-api-shape-before-1.0.md)). Its kinds are `Explicit(text, hyperlink)`, an OSC 8 hyperlink, whose `text` is that of the cells around the clicked one with the same hyperlink, joined across the rows that autowrap joins; and `Matched(text, url, pattern)`, text that a pattern matched (section 6.2). Built-in URLs are `Matched` with `pattern == LinkPattern.Urls`, and their `url` is their `text`.
- `onLinkClick: ((TerminalLinkClick) -> Unit)?` gets a `TerminalLinkClick`: the `link`, the `pointerType` of the pointer that clicked, and the `keyboardModifiers` held, Compose's own types, so that an app can open links only on Ctrl+click with a mouse and still on a tap ([decisions/0031](../decisions/0031-desktop-pointer-and-terminal-state.md)). A tap reports `PointerType.Touch` and the modifiers of its release event. Activation without a pointer would report `PointerType.Unknown` and `PointerKeyboardModifiers(0)`; no such path exists, since a screen reader's double tap on a row becomes a tap (section 11).
- `TerminalLink`'s kinds and `TerminalLinkClick` are ordinary classes with internal constructors and value equality, not data classes, so that properties can be added ([decisions/0037](../decisions/0037-api-shape-before-1.0.md)).
- A click reaches `onLinkClick` only when it is not reporting (sections 2 and 3). With a mouse while the host asks for the mouse, a click goes to the host, links included, unless Shift is held; then it calls `onLinkClick` with Shift in the modifiers. Ctrl+click goes to the host then (with the Ctrl bit), so an app that opens links on Ctrl+click opens them only while the host does not ask for the mouse, or on Ctrl+Shift+click. While scrolled back, every click is the terminal's, and calls `onLinkClick` with the modifiers held, Ctrl included.
- vtfx never opens a link from a click. The app shows the URL, and calls `TerminalSession.openUrl` when the user confirms; the gate checks the URL ([decisions/0006](../decisions/0006-gate-defaults.md)). This holds for every kind of link: an OSC 8 hyperlink's text and URL come from the host apart, and text in the background's color or out of view can be part of a match ([decisions/0019](../decisions/0019-extensibility.md)).
- Without `onLinkClick`, links are not clickable, and the patterns are not matched, so only OSC 8 hyperlinks, which are always underlined, look like links.

### 6.2 Link patterns

- `LinkPattern(regex, urlTemplate)` (`vtfx-core`): `{0}` in the template is the whole match and `{n}` group n (an empty string if the group did not match). Other braces, and groups that the regular expression does not have, are rejected at construction.
- Each value put into the template is percent-encoded, so that the matched text, which the host decides, cannot change the host of the URL or add segments to it. A value of `.` or `..` stays as it is, and makes a dot segment that opening the URL removes, with the segment before it for `..` ([decisions/0019](../decisions/0019-extensibility.md)).
- `LinkPattern.Urls` finds the `http` and `https` URLs in the text with a linear scan without backtracking. The URL is the text itself, without the punctuation that usually ends a sentence and closing brackets without an opening one; only ASCII characters allowed in URLs are taken.
- Two patterns are equal if they have the same regular expression, options, and template, so a fresh list with equal patterns passed on each recomposition does not redo detection.
- `Terminal(linkPatterns = ...)` takes the patterns in order of priority; the default is `listOf(LinkPattern.Urls)`, and an empty list stops detection.

### 6.3 Finding links

`LinkFinder` (`vtfx-core`, internal API) finds the links on the display.

- Rows that autowrap joins are matched as one text, without the blank that autowrap leaves before a wide character that did not fit.
- Where matches overlap, the earlier pattern wins. Overlaps are decided on the text alone; then a match that covers a cell with an OSC 8 hyperlink, or a concealed cell (SGR 8), is not a link, and the matches of later patterns that it overlapped stay dropped. So a later pattern never makes a link inside an earlier pattern's match, and the OSC 8 hyperlink of a cell always wins. A concealed cell is not drawn, so a match over it would go to a URL that the user did not see ([decisions/0019](../decisions/0019-extensibility.md)).
- A logical line that the display cuts at its top or bottom is matched with its rows beyond the display, which the snapshot keeps, up to one screen of rows each way; only the part inside the display is a link. Where the line goes on beyond those rows too, a match that reaches the cut is not a link, since the text beyond could change it.
- Links are found on the main thread at every draw. The links of each logical line are kept by the line's content from one draw to the next, so only lines whose content changed are matched again. Overlaps are removed in time linear in the number of matches.
- vtfx sets no bound on the work of the app's regular expressions, which run on text from the host, and Android's regular expressions cannot be stopped once started. Giving patterns that take time linear in the text is the app's responsibility, as the KDoc of `LinkPattern` and [usage.md](../usage.md) say ([decisions/0019](../decisions/0019-extensibility.md)).
- The underlines of matched links are drawn over the line, outside the per-line drawing cache, like the selection: a match depends on the rows that autowrap joins, so it cannot be part of a line's cache key. OSC 8 hyperlinks are underlined with the line's cells. Their styles are in [decisions/0030](../decisions/0030-underline-color-and-decorations.md).
- Hovering a mouse over a link changes only the pointer icon, to a hand (section 3).

## 7. Hardware keys

### 7.1 Keys to the host

Hardware keys go to the focused terminal. `TerminalInputView` passes the keys that the IME did not consume to `KeyEventHandler`, which handles them by the same rules as the keys that the IME sends with `sendKeyEvent` (section 6.3 of [ime-contract.md](ime-contract.md)).

- Escape, Tab, Enter, the keys of the numeric keypad, Backspace (DEL), Insert, Forward Delete, Home, End, Page Up, Page Down, the arrows, and F1 to F12 are terminal keys, with Shift, Alt, Ctrl, and Meta as modifiers.
- Any other key is the character that it produces with Shift applied, with Alt, Ctrl, and Meta as modifiers. A key that produces no character, or a dead key (a combining accent), is not a terminal key and is not consumed (Back, for example).
- A release is consumed for the keys whose presses are, and has no effect.
- The numeric keypad sends its characters, Keypad Enter following LNM; with the application keypad (DECKPAM, or DECNKM, mode 66), it sends SS3 sequences, as xterm does with NumLock off, and `CSI 1 ; m` sequences with modifiers ([decisions/0012](../decisions/0012-touch-and-selection.md)).

### 7.2 Keys for the app

`Terminal(onPreImeKeyEvent = ...)` takes `((KeyEvent) -> Boolean)?` (Compose's `KeyEvent`), null by default ([decisions/0032](../decisions/0032-keys-and-layout.md)).

- `TerminalInputView.dispatchKeyEventPreIme` calls it with each key event that the window dispatches to the focused view before the IME: presses, repeated presses, and releases of hardware keys, only while the terminal has the focus. With several terminals, only the focused terminal's callback is called.
- Returning true consumes the event: the window then sends it neither to the IME nor to `dispatchKeyEvent`, so it does not reach the session. The KDoc asks the app to consume a key's release with its press, and to return false for the keys it does not use, including system keys such as Back, which the IME uses to hide itself.
- Keys from a software keyboard and keys sent with `TerminalSession.sendKey` do not pass through it.
- `Modifier.onPreviewKeyEvent` on an ancestor of `Terminal` sees only the keys that the IME passes on: Android dispatches a hardware key to the views' `dispatchKeyEventPreIme`, then to the IME, and only then, for the keys the IME did not consume, to the views' `dispatchKeyEvent`, where Compose runs `onPreviewKeyEvent`. Gboard consumes the keys of a physical keyboard and resends them through the `InputConnection`. The KDoc of `Terminal` and [usage.md](../usage.md) therefore recommend `onPreImeKeyEvent`.
- Robolectric has no IME, so `TerminalKeyInterceptionTest` dispatches as the window does: before the IME, then, unless consumed, after it.

## 8. Extra key bar and sticky modifiers

- The library has no key bar UI (requirement 5.4). The app builds one with `TerminalSession.sendKey`, `sendText`, `paste`, and `stickyModifiers` (section 5 of [usage.md](../usage.md)); the sample app has a key bar with Esc, Tab, Ctrl, Alt, and the arrows ([decisions/0012](../decisions/0012-touch-and-selection.md)).
- `stickyModifiers` apply to the next key, or to the first character of the next text that the IME editing model sends, and are then cleared. `TerminalSessionListener.onStickyModifiersChanged` reports each change, including the clearing. When the modifiers change a key or a character, the host no longer gets what the IME's input history predicts, so the input history is reset after the edit (section 5.1 of [ime-contract.md](ime-contract.md)).
- Arming sticky modifiers (setting modifiers that are not empty and differ from the current ones) first commits the composing text and sends it, with the edits not sent yet, without the new modifiers, as a key would before itself. If there was composing text, the input history is then reset, so that the IME, which still has the composing text, restarts. The modifiers then apply only to what is typed after arming, composing text included. Clearing them sends nothing ([decisions/0033](../decisions/0033-sticky-modifiers-and-composing-text.md)).
  - The pending input is sent even during a batch edit of the IME, as a paste is, since the app's call is not part of the IME's edit; the reset then waits for the end of the batch.
  - Arming without composing text sends only the edits not sent yet, if any, and does not reset, so that tapping Ctrl does not restart the IME every time.
  - If modifiers are already armed and the app adds another one, the composing text typed after the first takes the first, as it would have when it was committed.
- The keys of scroll gestures (section 2) do not take the sticky modifiers, which stay armed.

## 9. Cursor blinking

- The cursor blinks only when the cursor style blinks, and only while the terminal is focused: it is shown and hidden every 0.5 seconds. The style is the host's (DECSCUSR, mode 12), or, until the host sets it, the app's default (`TerminalStyle.cursorBlinking`, off by default; [decisions/0009](../decisions/0009-emulation-scope.md)). The character blink attribute (SGR 5) is not drawn ([decisions/0012](../decisions/0012-touch-and-selection.md)).
- Each input from the user (a key, an IME edit, a paste, or input sent with the session's API) shows the cursor and starts its phase again, so that the cursor stays on while the user types, also when the host does not echo. So does output from the host that moves the cursor, as in xterm.js; output that leaves the cursor where it was, such as a redraw that returns it, does not. The position is compared each time the display updates.

## 10. Size and inner padding of `Terminal`

### 10.1 Default size

`Terminal` prefers 80 × 24 cells (xterm's default, and `TerminalSession`'s), the way `Text` prefers the size of its text: the size is clamped to the incoming constraints, so fixed constraints (`size`, `fillMaxSize`, `weight`) decide it, loose ones give 80 × 24 cells or their maximum, and unbounded ones (inside a scrolling container) give 80 × 24 cells (`preferredGridSize` in `Terminal.kt`) ([decisions/0032](../decisions/0032-keys-and-layout.md)).

- A grid with no width or height: `Terminal` logs a warning with `android.util.Log` (tag `vtfx`) and does not resize the session, which keeps its size. A size that is not zero but holds less than one cell gives one column or row.
- How the session follows the size, after a delay, is in section 3.5 of [architecture.md](architecture.md) ([decisions/0013](../decisions/0013-resize-and-lifecycle.md)).

### 10.2 Inner padding

`TerminalStyle.padding: PaddingValues`, zero by default ([decisions/0032](../decisions/0032-keys-and-layout.md)).

- The padding is drawn in the grid's default background color, swapped with the foreground in reverse video (DECSCNM), as the renderer swaps the grid's.
- The grid's `Canvas`, the `TerminalInputView` for the IME, and the rows for accessibility are all inset by `Modifier.padding`. Their coordinates stay the grid's, so the touch gestures, the long-press menu's anchor, and the `CursorAnchorInfo` need no offset. The columns and rows are counted from the inset size, and the default size adds the padding.
- `Modifier.padding` follows the layout direction, so `start` is on the right in right-to-left layouts. The default size and the tap detector of the padding round the padding to pixels as `Modifier.padding` does.
- A tap in the padding focuses the terminal and shows the keyboard, as a tap on the grid does; long presses and drags in the padding do nothing. The mouse acts over the padding as over the grid (section 3), at the nearest cell.
- Each change of the style makes the paints and the cell size again, padding included.

## 11. Accessibility

[decisions/0015](../decisions/0015-accessibility.md) explains this section.

- **A node per row.** Each displayed row is one semantics node with the position and size of the row, and its `text` is the row's text without trailing blanks. Screen readers move row by row with swipes, and read the touched row with explore by touch. When the display is scrolled back, the rows of the scrollback that it shows are exposed. The rows are laid out by `RowSemantics` in `Terminal.kt`, over the grid without handling touches, and each row's semantics are those of a `TerminalRowSemanticsNode`.
- **Computed only when queried.** The rows' text is made only when an accessibility service (or a test) queries the semantics, once per change of the display, and shared by the row nodes. Without a service, drawing costs nothing more.
- **No click action on rows.** Compose would report the bounds of a clickable node enlarged to the minimum touch target, and the rows, shorter than that, would overlap. A screen reader's double tap on a row without a click action is sent by explore by touch as a tap at the focus position, so it focuses the terminal and shows the keyboard as a tap does.
- **The view for the IME is not exposed.** `TerminalInputView` is `IMPORTANT_FOR_ACCESSIBILITY_NO`. The IME connects through input focus, apart from the accessibility tree, so the IME's editing buffer and the text that the screen reader reads do not constrain each other (requirement 6.3).
- **Rows are sensitive data.** The row nodes have Compose's `isSensitiveData`. On Android 14 (API 34) and later, they are shown only to services that declare `isAccessibilityTool` (TalkBack, Switch Access, and so on): Compose sets `AccessibilityNodeInfo.setAccessibilityDataSensitive`, and returns no node for the requests of other services. On API 29 to 33, the platform cannot tell a tool from another service, and every accessibility service sees the rows ([decisions/0039](../decisions/0039-minsdk-29.md)).
- **Content Capture and screen sharing are the app's choice.** Compose's Content Capture does not look at `isSensitiveData`, and the means of stopping it affects the whole process; `Modifier.sensitiveContent` would also hide the terminal when it should be shared. vtfx sets neither, and section 6 of [usage.md](../usage.md) tells apps how.
- **No live regions.** New output is not read automatically.

## 12. Not verified on devices

The following are verified only with Robolectric and the emulator, and are listed in [review-queue.md](../review-queue.md) with the procedures in [device-checks.md](../device-checks.md):

- `onPreImeKeyEvent` with a real physical keyboard and Gboard, in English and in Japanese.
- The mouse and touchpads on a Chromebook, Samsung DeX, or a tablet with a mouse.
- Copying all the text with a full scrollback through the system clipboard.
- Reading and operating the rows with TalkBack.
