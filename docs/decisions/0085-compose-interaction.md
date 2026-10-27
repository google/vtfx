# Interaction in the Compose UI

- Status: Approved
- Date: 2026-10-25
- Related: Requirements 4.1, 5.4, 5.5, 6.1, 6.3, and 6.5, [compose-input.md](../design/compose-input.md), sections 4.2 and 5 of [gate-policy.md](../design/gate-policy.md), 2.7 of [research/xterm-js-ime.md](../research/xterm-js-ime.md), [0083](0083-gate.md), [0084](0084-session-and-threads.md), [0087](0087-public-api.md), [0090](0090-distribution.md), [0081](0081-resource-limits.md)

## Background

Requirement 5.4 asks for physical keyboards and modifier keys, touch scrolling and mouse reporting, text selection, paste, and a means to build an extra key bar; requirement 6.3 asks that TalkBack can read the terminal and that the IME and accessibility do not constrain each other. What a touch or a click means depends on whether the host asks for the mouse. An app embedding `Terminal` needs its own copy command, shortcuts, search field, and layout. Host output is untrusted and can contain secrets (requirement 6.1).

## 1. Touch

A tap shows the keyboard, and is also reported as a click when the host asks for the mouse; a vertical swipe scrolls the scrollback, sends arrow keys on the alternate screen (as xterm's alternateScroll), or reports the wheel when the host asks for the mouse; a long press selects and shows a menu to copy and paste. Drags are never reported from touch. See section 2 of [compose-input.md](../design/compose-input.md).

**Why.** Swipes are kept for scrolling, so that there is a means of scrolling also when the host asks for the mouse.

**Alternatives considered.**
- **Report drags as mouse drags when the host asks for the mouse**: There would be no means of scrolling. Assigning one of the two to two-finger gestures is an option, but it tends to conflict with the IME and system gestures.

**Impact of reversing.** Change the branches in `TerminalGestures` and section 2 of [compose-input.md](../design/compose-input.md).

## 2. Mouse

A pointer whose `PointerType` is `Mouse` gets desktop behavior; touch, stylus, and any other pointer keep the gestures of topic 1 (sections 1 and 3 of [compose-input.md](../design/compose-input.md)):

1. Without mouse reporting, the primary button selects as in desktop terminals, the wheel scrolls as a swipe does, and the secondary button shows the long-press menu or calls the app's `onContextClick`, for the app's own menu. A click without a drag is a tap.
2. With mouse reporting, the host gets the buttons, the wheel, and the motion it asked for. Shift leaves the mouse to the terminal, as in xterm, and never reaches the host from the mouse.
3. The middle button does nothing without reporting.
4. No menu appears after a mouse selection; the right click shows it.
5. A click focuses the terminal and asks for the software keyboard, as a tap does; vtfx does not try to tell desktop-like devices apart.
6. Horizontal scrolling is not reported.

**Why.** Chromebooks, DeX, and tablets with a mouse need desktop input, which follows desktop terminals, and Shift follows xterm. Android has no primary selection for the middle button to paste, and pasting into a shell by accident is a risk. Desktop apps do not pop a toolbar when the mouse selects. With a hardware keyboard attached, Android normally does not show the software keyboard (Gboard's "Show on-screen keyboard" is off by default then). `MouseButton` has no buttons for horizontal scrolling, and few hosts use them.

**Alternatives considered.**
- **A toolbar after every mouse selection**: Usual on touch, unusual with a mouse, where the right click asks for the menu.
- **Middle click pasting from the clipboard**: X's middle click pastes the primary selection, which Android lacks; pasting the clipboard into a shell on an accidental middle click could run commands.
- **Treating every pointer with buttons as a mouse (touchpads, stylus buttons)**: The device reports differ, and could not be checked without devices; the stylus keeps touch gestures, which suit handwriting-style use.

**Impact of reversing.** The mouse's behavior is in `MouseHandler` (`TerminalMouse.kt`); section 3 of [compose-input.md](../design/compose-input.md) and the KDoc of `Terminal` follow it.

## 3. The pointer while scrolled back

While the display shows the scrollback, the mouse is the terminal's even if the host asks for it, exactly as if Shift were held, and so are touches; back at the bottom, the host gets them again. A button's press decides for its drag and its release, and a swipe decides when it starts, so that a swipe that reaches the bottom stops there. See sections 1 to 3 of [compose-input.md](../design/compose-input.md).

**Why.** A report carries only a row of the screen, so a click on a row of the scrollback would reach the host as a click on a row that the user does not see (vim with `mouse=a` moves its cursor there). Deciding at the press keeps the host from getting a press without its release or a release without its press.

**Alternatives considered.**
- **Reporting the mouse while scrolled back, at the screen's rows**: The host gets clicks on rows that the user does not see.
- **Scrolling to the bottom on a click, then reporting it**: The click lands on a row that the user did not aim at.
- **Dropping the mouse while scrolled back**: The scrollback could not be selected or scrolled with the mouse while the host asks for it, without Shift.

**Impact of reversing.** Change `isReporting` in `TerminalMouse.kt` and `isReportingTouches` in `TerminalGestures.kt`, which both use `TerminalDisplay.isScrolledBack`.

## 4. Selection and copying

The selection stays on the text the user selected: its cells are named by line numbers that survive scrolling, as the search's matches are (topic 15), so output and the user's scrolling move it with its text. It is dropped when that text changes, as copying reads it (the characters, their widths, concealment, and the autowrap between selected lines, not the style); when the lines are numbered again (resizing, clearing the scrollback, switching screens); when all its lines leave the scrollback, its first lines being cut off when only they leave; on the user's input; and on a tap or a click. A long press or a drag stops selecting when its selection is dropped. Copying is the user's action, so the OSC 52 policy ([0083](0083-gate.md)) does not apply; it goes through the gate (section 4.2 of [gate-policy.md](../design/gate-policy.md)). See section 4 of [compose-input.md](../design/compose-input.md).

**Why.** Dropping the selection on any change of the display would make it hard to use: output that does not touch the selected text (a log growing below it, a clock in a status line, a right prompt) would drop it at once, and so would scrolling to see its context. Desktop terminals keep the selection with its text (Alacritty, kitty, VTE, xterm); the ones that look at the text drop it when it changes. Comparing the text that copying would give drops the selection exactly when copying would give text other than what was selected, and a change of style alone, which copying ignores, keeps it. Lines in the scrollback do not change while the numbering stays, so a check reads only the selected lines that the screen held, at most a screen of them, also when they scrolled into the scrollback before the check. Cutting off lines that left the scrollback, as Alacritty does, keeps the rest that the user selected. Input drops the selection because it returns the display to the bottom, where the user moves on, as typing does in desktop terminals.

**Alternatives considered.**
- **Hold the selection in the display's coordinates and drop it whenever the display changes**: No line numbers needed, but output anywhere and scrolling drop the selection.
- **Drop the selection when the emulator writes into or erases its cells, as Alacritty does for erasures**: Needs tracking in `vtfx-core` of the cells each operation touches, and drops the selection when a program rewrites the same text, as shells redrawing their prompt do.
- **Compare whole lines, styles included**: Simpler, but a redraw in other colors or a change elsewhere on a selected line (a right prompt) would drop it.
- **Drop the whole selection when its first lines leave the scrollback**: Simpler, but a selection of all the text would be dropped by any output once the scrollback is full.
- **Keep the selection on the user's input**: Typing returns the display to the bottom and changes the line of the prompt; keeping the selection there is of little use, and the app can copy before.

**Impact of reversing.** Change `checkSelection` and the selection's coordinates in `TerminalDisplay` and `TerminalSelection`, the gestures in `TerminalGestures` and `TerminalMouse`, the drawing in `TerminalRenderer`, and section 4 of [compose-input.md](../design/compose-input.md).

## 5. `TerminalState`

`TerminalState` is a Compose state holder that the app passes to `Terminal` to read and change the selection and the search (topic 14), as section 5 of [compose-input.md](../design/compose-input.md) describes.

1. `copySelection()` keeps the selection, as Ctrl+C in a text field and Ctrl+Shift+C in desktop terminals do; the app calls `clearSelection()` if it wants it gone. The menu's Copy still drops it, as the menu goes away.
2. `selectAll()` selects the scrollback and the screen, as the Select all of desktop terminals does; the long-press menu has it too. Copying all the text is bounded (section 4 of [compose-input.md](../design/compose-input.md)).
3. A state not attached to a `Terminal` does nothing, and a state passed to two terminals acts on the one that entered the composition last.
4. Main thread only, as Compose state is, with no run-time check, as for the other internal display state.

**Why.** An app needs to copy from its own menu or shortcut (Ctrl+Shift+C), not only from the long-press menu. A clip goes to the system in one Binder transaction, which fails above about 1 MB, and the text is built on the main thread.

**Alternatives considered.**
- **Methods on `TerminalSession` (`session.copySelection()`)**: The selection belongs to one display of the session ([0084](0084-session-and-threads.md)); a session shown twice has two selections, and a session without a UI has none.
- **`selectAll()` selecting only what the display shows**: Simpler, and it would need no new access to the scrollback, but it is not what Select all means in desktop terminals.
- **No limit on copying all the text**: With a large scrollback, the clip fails on the device (`TransactionTooLargeException`), and the text is built on the main thread.
- **Throwing when a state is passed to two terminals**: During an animated transition, the old and the new screen are composed together for a moment, with the same state.

**Impact of reversing.** `TerminalState` is public API; removing it means a new way for the app to reach the selection. To change what Select all covers, change `TerminalDisplay.selectAll` and `selectedText`, and the KDoc of `TerminalState`.

## 6. Copies and link clicks during synchronized output

During synchronized output (mode 2026), copying and the link of a click read the current screen while the display defers its redraw for up to 200 ms, so the host can change the text under a selection or the OSC 8 link of a cell without the user seeing it. This is accepted (section 4 of [compose-input.md](../design/compose-input.md)).

**Why.** The window is short, the URL is shown before it is opened (`TerminalSession.openUrl`), and it has not been reproduced.

**Alternatives considered.**
- **Using the snapshot that the display drew for copies and link clicks, or ignoring them while a redraw is deferred**: Not taken, for the reasons above.

## 7. Link clicks

1. A tap or a click on a link calls the app's `onLinkClick` instead of showing the keyboard. vtfx never opens a link from a click: the app shows the URL and opens it with `TerminalSession.openUrl` when the user confirms (requirement 6.1, [0083](0083-gate.md), section 5 of [gate-policy.md](../design/gate-policy.md)). Without the callback, links cannot be clicked.
2. While reporting, clicks go to the host regardless of links; with a mouse, a click with Shift held calls `onLinkClick` instead.
3. `onLinkClick` takes a `TerminalLinkClick`: the link, the `PointerType` of the pointer that clicked, and the `PointerKeyboardModifiers` held, Compose's own types. It is a class, with room for more properties (the position, for example).

See section 6.1 of [compose-input.md](../design/compose-input.md).

**Why.** The pointer type and the modifiers let an app open links only on Ctrl+click with a mouse and still on a tap.

**Alternatives considered.**
- **`onLinkClick: (TerminalLink, PointerType, PointerKeyboardModifiers) -> Unit`**: Every new property would break every caller; a class can grow.
- **vtfx's own modifier flags instead of `PointerKeyboardModifiers`**: Duplicates Compose's type, whose `isCtrlPressed` and the other extensions apps already know.
- **Ctrl+click on a link overriding mouse reports**: Ctrl+click is a meaningful report for some hosts (bit 16), and Shift is already the xterm convention for taking the mouse back.

## 8. Hardware keys

1. The numeric keypad sends SS3 sequences with DECKPAM (or DECNKM), the same as xterm with NumLock off (section 7.1 of [compose-input.md](../design/compose-input.md)).
2. `Terminal` takes `onPreImeKeyEvent: ((KeyEvent) -> Boolean)?`, which `TerminalInputView.dispatchKeyEventPreIme` calls with each hardware key event before the IME; returning true consumes the event (section 7.2 of [compose-input.md](../design/compose-input.md)). This is the per-terminal key interception that [0087](0087-public-api.md) calls for.

**Why.** App shortcuts (Ctrl+Shift+C and V) must be caught with a physical keyboard and Gboard, which consumes the keys before the views' `dispatchKeyEvent`, where Compose runs `onPreviewKeyEvent` (section 7.2 of [compose-input.md](../design/compose-input.md)). Compose's own pre-IME modifier (`onPreInterceptKeyBeforeSoftKeyboard`) is experimental and runs only when a Compose node has the focus, while here the `View` for the IME connection has it (`AndroidComposeView` passes the pre-IME dispatch to its focused child view). The name says when it is called, like Android's `dispatchKeyEventPreIme` and `onKeyPreIme`, and that it sees every key (`onShortcut` would suggest that vtfx decides what a shortcut is). Compose's `KeyEvent` is what Compose apps already match with `key`, `isCtrlPressed`, and `type`; `nativeKeyEvent` gives the Android event, such as its repeat count.

**Alternatives considered.**
- **Keep `Modifier.onPreviewKeyEvent` on an ancestor and only correct the documentation**: It sees only the keys the IME passes on, so the app would have to override `Activity.dispatchKeyEvent`, which cannot tell which of several terminals a key is for ([0087](0087-public-api.md)).
- **Pass Android's `KeyEvent`**: Apps would need Android's meta-state flags instead of the Compose extensions they use elsewhere.

**Impact of reversing.** Removing `onPreImeKeyEvent` removes the parameter of `Terminal` (an API change; [0087](0087-public-api.md)) and the override in `TerminalInputView`, and changes section 7.2 of [compose-input.md](../design/compose-input.md).

## 9. Extra key bar and sticky modifiers

The library has no key bar UI: the app builds one with `TerminalSession.sendKey` and `stickyModifiers`, which apply to the next typed key or character and are then cleared, as the sample app does (section 8 of [compose-input.md](../design/compose-input.md)).

**Why.** Requirement 5.4 asks for a means to build an extra key bar.

## 10. Cursor blinking

The cursor blinks only when its style asks for it, and only while focused; the character blink attribute (SGR 5) is not drawn. The user's input, and output that moves the cursor (as in xterm.js), show the cursor and restart its phase (section 9 of [compose-input.md](../design/compose-input.md)).

**Why.** Restarting the phase keeps the cursor on while the user types, also when the host does not echo.

## 11. Default size of `Terminal`

`Terminal` prefers 80 × 24 cells (xterm's default, and `TerminalSession`'s), the way `Text` prefers the size of its text, clamped to the incoming constraints. Laid out with no width or height, it logs a warning and does not resize the session (section 10.1 of [compose-input.md](../design/compose-input.md)).

**Why.** Without a size of its own, a `Terminal` without a size in its `modifier` was laid out 0 × 0 where the constraints allowed (for example, in a `Column` without `weight`), and silently resized the session to 1 × 1, which made the host redraw for one cell and reflowed the scrollback to one column.

**Alternatives considered.**
- **Prefer the session's current size**: A terminal in loose constraints would keep whatever size it last had, and the preferred size would change with every resize.
- **Fill the constraints' maximum**: A terminal in a `Column` would push the views after it out of the screen, and in a scrolling container it would have no maximum to fill.
- **Throw on zero constraints**: A layout passes through 0 × 0 during animations and in tests, and a crash there would be worse than a warning.

**Impact of reversing.** To drop the default size, remove `preferredGridSize` from `Terminal`; a `Terminal` without a size then takes the minimum of its constraints again. To resize to one cell again instead of keeping the size, remove the early return in the `onSizeChanged` of the grid. Update section 10 of [compose-input.md](../design/compose-input.md).

## 12. Inner padding of `Terminal`

`TerminalStyle.padding: PaddingValues`, zero by default, drawn in the grid's default background color, with the grid, the view for the IME, and the rows for accessibility inset by `Modifier.padding` (section 10.2 of [compose-input.md](../design/compose-input.md)).

**Why.** Without it, the grid touched the edges of `Terminal`, and `Modifier.padding` around it showed the colors behind it rather than the terminal's background. Zero by default keeps existing layouts and the goldens. `PaddingValues` is Compose foundation's type, which apps already use with `Modifier.padding`; like `Color` and `TextUnit` of Compose UI elsewhere in `TerminalStyle`, it comes from a dependency that `vtfx-compose` declares as `implementation` and that a Compose app has anyway. The background color makes the padding look part of the terminal. Insetting keeps the coordinates the grid's, so the gestures, the menu anchor, and the `CursorAnchorInfo` need no change. A change of the style makes the paints and the cell size again, padding included; the padding is not split out, as it rarely changes.

**Alternatives considered.**
- **Padding drawn by the renderer, with the grid offset inside one `Canvas`**: The gestures, the menu anchor, and the IME's positions would all have to subtract the offset.

**Impact of reversing.** Remove `TerminalStyle.padding` (an API change) and the `padding` modifiers, the background, and the tap detector in `Terminal`, and update section 10 of [compose-input.md](../design/compose-input.md).

## 13. Accessibility

See section 11 of [compose-input.md](../design/compose-input.md).

1. **A node per row**, so that screen readers move row by row and read the touched row with explore by touch.
2. **Computed only when queried**, so that the rendering load does not increase without an accessibility service.
3. **No click action on rows.**
4. **The view for the IME is not exposed.**
5. **Rows are sensitive data**: only services that declare `isAccessibilityTool` (TalkBack, Switch Access, and so on) see them. On API 29 to 33, the platform cannot tell a tool from another service ([0090](0090-distribution.md)).
6. **Content Capture and screen sharing are left to the app**: the library sets neither.
7. **No automatic reading of new output (live regions).**

**Why.** Compose reports the bounds of clickable nodes enlarged to the minimum touch target (48dp), and rows are shorter than that, so the bounds overlapped and the focus frame and the touched row were misaligned; explore by touch sends a double tap on a node without a click action as a tap at the focus position, which focuses the terminal and shows the keyboard like a normal tap. An exposed view for the IME, covering the grid, took the focus of explore by touch; the IME connects through input focus, independent of the accessibility tree, so the editing buffer the IME sees and the content the screen reader reads do not constrain each other (there is no problem like the shared textarea of xterm.js). Host output can contain secrets, and apps that obtained accessibility permissions could read the tree; as a cost, reading apps that do not declare `isAccessibilityTool` cannot read the terminal. Compose's Content Capture does not look at `isSensitiveData`, and the means of stopping it (`ContentCaptureManager.isEnabled`, an experimental API) affects the whole process; `Modifier.sensitiveContent`, which hides content from screen sharing and recording, gets in the way when the terminal should be shared and shown (explaining operations and so on). With live regions, apps that rewrite the whole screen (htop and so on) would make reading never stop; if needed, consider them as a setting the app can choose.

**Alternatives considered.**
- **The whole screen as one node, leaving moving by line to movement at character granularity**: TalkBack then reads only the whole screen at once; moving by line with Compose's `text` node requires passing the result of text layout, which does not correspond to the terminal grid.
- **A click action on rows, avoiding the enlargement of bounds**: The enlargement happens inside Compose's accessibility implementation, and there is no public means of stopping it from the node's side.

**Impact of reversing.** Change `RowSemantics` and `TerminalRowSemanticsNode` in `Terminal.kt`, `TerminalSemanticsTest`, and section 11 of [compose-input.md](../design/compose-input.md). To remove the sensitive mark, delete `isSensitiveData` in `applySemantics` and revise `TerminalSemanticsTest.rowsAreShownOnlyToAccessibilityTools`.

## 14. Search: where it is held and its API

The app has the search field; the terminal finds the matches, highlights them, and scrolls to them ([0087](0087-public-api.md)). See section 13 of [compose-input.md](../design/compose-input.md) for this topic and topics 15 and 16.

1. The state of a search belongs to the display, as the selection does: `TerminalDisplay` holds it, and `TerminalState` (topic 5) is the app's handle on it, which keeps the query while the display is out of the composition ([0095](0095-keeping-the-view.md)).
2. `TerminalColors` takes `searchMatch` and `searchCurrentMatch`, required like `selection`.

**Why.** Two `Terminal`s showing one session search apart, and a session without a UI has no search. Moving to the next match is an event, like Select all, so the API is calls on the state rather than a parameter of `Terminal`. Before 1.0, parameters may be added without keeping the previous signature ([0087](0087-public-api.md)).

**Alternatives considered.**
- **Hold the search in `TerminalSession`**: One search for every display, and one for JVM programs without a UI, but the session holds no display state ([0084](0084-session-and-threads.md)), and a search scrolls a display. A JVM program can read the lines with `TerminalSession.snapshot`.
- **A query as a parameter of `Terminal`**: Moving to a match is an event.

**Impact of reversing.** Change the search of `TerminalDisplay`, `TerminalState`, and the drawing in `vtfx-compose`, and section 13 of [compose-input.md](../design/compose-input.md). Removing the colors from `TerminalColors` or the functions from `TerminalState` changes the public API.

## 15. Search: following the output

1. Matches are named by line numbers that survive scrolling, as image placements are. Lines in the scrollback do not change, so a search keeps their matches and, on each output, reads only the lines that entered the scrollback since, the part of a line that goes on into the screen, and the screen, a bounded number of times per redraw ([0081](0081-resource-limits.md)). Resizing, clearing the scrollback, and switching screens number the lines again, and the search starts over.
2. The text of the scrollback is kept while searching, with the columns of its characters and the concealed characters, until the search is cleared or the display leaves the composition.

**Why.** An incremental search must keep up with two kinds of change. Each change of the query searches again, over a default scrollback of 10,000 lines of up to 120 columns: the kept text spares reading and laying out the lines again, which is most of the work. Output goes on while the user searches, as in a log that keeps flowing, and lines enter and leave the scrollback and reflow: line numbers let the search follow them.

**Alternatives considered.**
- **Drop the search on output**: No line numbers needed, but a flowing log could not be searched.
- **Read and lay out every line at each change of the query**: No memory kept, but several times the work, all of it on the main thread, with the session's lock held while the lines are copied.
- **Search on another thread**: The main thread would stay free, but stale results would have to be dropped as the query and the output change, and the modules that do not depend on Android have no executor to run it on. Not needed at the measured cost (timed on the JVM, not on ART: section 14 of [device-checks.md](../device-checks.md)).

**Impact of reversing.** Change `SearchIndex` and `Terminal.searchLines` in `vtfx-core`, the search of `TerminalDisplay` in `vtfx-compose`, and section 13 of [compose-input.md](../design/compose-input.md).

## 16. Search: matching

1. **Literal text, with an option to ignore case**, compared one UTF-16 unit at a time as `String.regionMatches` does, in time linear in the text and the query (Knuth-Morris-Pratt). Regular expressions are not offered. Width and Unicode forms are not normalized until asked for.
2. **What is text is what the links read** and, but for concealed cells, what copying gives (rows that autowrap joins are one text, in parts for very long lines by [0081](0081-resource-limits.md)). A match that covers a concealed cell (SGR 8) is not a match, as for links.
3. **At most 1,000 matches are kept**, the newest; `TerminalSearchResult.isMatchCountCapped` tells the app to show the count as a lower bound.
4. **The search starts from the bottom and goes up**, as terminals do: a new query makes current the nearest match at or above the current one. Only moving to a match (a new query, `findNext`, `findPrevious`) scrolls the display; output and the user's input do not move it for the search.

**Why.** Linear time means that text, which the host decides, cannot make the search slow. Android's regular expressions cannot be stopped once started ([0087](0087-public-api.md)), and a search runs on the main thread at every keystroke, also on half-typed patterns. The display does not draw a concealed cell, so a highlight there would show text that the user cannot see. The cap bounds the memory and drawing of text full of matches, which the host decides. Keeping the nearest match keeps the view where it is while the user types more of a word.

**Alternatives considered.**
- **Regular expressions**: See the reasons above.

**Impact of reversing.** Change `SearchIndex` in `vtfx-core`, the search of `TerminalDisplay` and its drawing in `vtfx-compose`, and section 13 of [compose-input.md](../design/compose-input.md).

## 17. Selecting by words and lines

1. A drag after a double click extends the selection by words, and after a triple click by lines: from the end of the pressed word or line farther from the pointer to the far end of the word or line under it. A blank is a word of its own, so over blanks the selection grows a cell at a time. A selection keeps the unit of the press that made it (`TerminalSelection.unit`).
2. Moving the finger after a long press extends the selection by words in the same way.
3. Shift+click extends the selection by the unit that made it, from its far end: by words after a double click or a long press, by lines after a triple click, by cells after a drag or Select all.

**Why.** Desktop terminals do so (xterm, VTE, kitty, Alacritty, macOS Terminal), as do text editors: the user who double-clicks wants whole words, and a drag that went on by cells would cut the word under the pointer. Android's text fields and Compose's selectable text extend a long press by words too, and a finger is less precise than a pointer, which makes cell boundaries harder still to hit. xterm and Alacritty extend by the unit of the selection when it is extended later, too. Keeping the unit in the selection lets every drag and Shift+click extend it in one way, whatever made it, also a long press that a mouse then extends.

**Alternatives considered.**
- **Extending by cells from the pressed word or line**: Simpler, but unlike desktop terminals and Android's text fields, and it needs a precise pointer to end on a word boundary.
- **Shift+click always by cells**: The unit would need keeping only during a drag, but Shift+click after a double click would cut the word at the click.
- **Switching to cells when the drag turns back, as Android's `TextView` handles do**: Finer control within a word, but a state to keep for each drag, and unlike desktop terminals.

**Impact of reversing.** Change `TerminalSelection.extendedTo` and the callers in `TerminalMouse` and `TerminalGestures`, and sections 2 and 3 of [compose-input.md](../design/compose-input.md).
