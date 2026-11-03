# Zooming, flinging, the loupe, and scrolling from the app

- Status: Provisional
- Date: 2026-11-03
- Related: Requirements 4.1 and 5.4, sections 2, 4, 5, 15, and 16 of [compose-input.md](../design/compose-input.md), section 7 of [ime-contract.md](../design/ime-contract.md), [usage.md](../usage.md), [0084](0084-session-and-threads.md), [0085](0085-compose-interaction.md), [0095](0095-keeping-the-view.md), [0097](0097-selection-handles.md)

## Background

Users of terminal apps on phones ask for what other touch UIs give: a pinch that zooms, a swipe that keeps scrolling after the finger leaves, a loupe that shows the text under the finger while selecting, and app commands that scroll and clear the scrollback. The display state (the scroll position, the selection, the search) is held by `TerminalDisplay`, and the app reaches it through `TerminalState` ([0085](0085-compose-interaction.md), [0095](0095-keeping-the-view.md)); the terminal state is held by `TerminalSession`, which has no UI ([0084](0084-session-and-threads.md)). `config/boundaries.toml` limits what `vtfx-compose` may refer to; a feature must fit in what it allows.

## 1. Clearing the scrollback from the app

`TerminalSession.clearScrollback()` clears the scrollback as the host's ED 3 (`CSI 3 J`) does, and tells the displays, which then show the screen, drop the selection, and search again, as after ED 3 (section 4 of [compose-input.md](../design/compose-input.md)). It draws at once, even during a synchronized update of the host, and tells the host nothing.

**Why.** The scrollback is the terminal's state, which the session holds, so the session clears it, also for an app without a UI, and every display of the session follows. ED 3 already defines what clearing means for the lines, their images, and their numbers, so the app's command does the same, and the displays need nothing new: they already follow ED 3. Users expect a menu item to act at once; mode 2026 is for the host's own updates.

**Alternatives considered.**
- **On `TerminalState`**: The scrollback is not display state, and a session shown by no `Terminal`, or by two, would have no single place to clear it.
- **Sending `CSI 3 J` through the session's input**: Input goes to the host, which would have to echo it back.
- **Clearing the screen too, as some terminals' "Clear" does**: The host does not know that its screen was cleared, and a full-screen program would be left with a blank screen until it redraws. Ctrl+L asks the host itself.

**Impact of reversing.** Remove `TerminalSession.clearScrollback` and `Terminal.clearScrollback` in `vtfx-core`, the mention in `SessionObserver.onOutput`, and the lines in [usage.md](../usage.md) and section 3 of [emulation.md](../design/emulation.md).

## 2. Scrolling from the app

`TerminalState` scrolls the display: `scrollBy(lines)`, `scrollByPages(pages)`, `scrollToTop()`, `scrollToBottom()`, and `isScrolledBack` as snapshot state (section 5 of [compose-input.md](../design/compose-input.md)). Negative numbers scroll back. On the alternate screen, they do nothing.

**Why.** The scroll position is display state, which `TerminalState` already gives the app for the selection and the search; a session shown by two terminals has two positions. Negative numbers toward earlier lines follow `LazyListState.scrollBy` and `ScrollState`, which count toward the end of the content, so an app's code reads the same; `TerminalDisplay` counts lines back from the bottom internally, which is not exposed. Pages are offered because the app cannot read the number of rows of the display (`TerminalSession.size` is internal), and Shift+Page Up and Down are the most common bindings of desktop terminals. `isScrolledBack` lets an app show a button to the bottom only when it does something. A swipe on the alternate screen sends arrow keys as xterm's alternateScroll does, but an app command that sends input to the host would be a key the user did not type; the app can send keys with `TerminalSession.sendKey` if it means to.

**Alternatives considered.**
- **Exposing the scroll offset and the size of the scrollback**: Enough for a scrollbar, but the offset changes as output arrives while scrolled back, and the app would scroll by lines counted from the bottom, the opposite of Compose's states. It can be added when an app needs a scrollbar.
- **`scrollTo(line)` with line numbers**: Line numbers change on resizing and clearing the scrollback, which the app cannot see.
- **Positive numbers scrolling back, as the display counts internally**: Opposite to Compose's scroll states.

**Impact of reversing.** Remove the functions and `isScrolledBack` from `TerminalState`, `scrolledBackState`, `scrollByPages`, `scrollToTop`, and `scrollToBottom` from `TerminalDisplay`, and the text in section 5 of [compose-input.md](../design/compose-input.md) and [usage.md](../usage.md).

## 3. Flinging

A swipe goes on as a fling after the finger is lifted, in every mode that the swipe scrolls in: through the scrollback, as arrow keys on the alternate screen, and as wheel reports (section 2 of [compose-input.md](../design/compose-input.md)). It slows down with Compose's default fling (`ScrollableDefaults.flingBehavior()`), from the speed of the finger that Compose's `VelocityTracker` measures. It stops at the end of the scrollback, at a touch, which is then not a tap, at the user's input, and when the app or the search scrolls the display; the arrow keys that it sends do not count as the user's input for this. A two-finger swipe on a touchpad flings the same way at its end.

**Why.** Every scrolling surface of Android flings, and a terminal's scrollback is long; without it, reading back takes many swipes. The fling scrolls as the swipe that started it did, so that it acts the same as a longer swipe would: in vim or less, a flick moves several lines, as in Termux. Compose's fling and `VelocityTracker` give the deceleration and the speed of the platform's lists, so a flick goes as far as in Compose's own scrolling; the user allowed the tracker's package (`androidx.compose.ui.input.pointer.util`, which holds no capabilities) in `config/boundaries.toml` for this. A touch that stops a fling is not a tap, as in Android's lists, so that stopping does not show the keyboard or click a link under the finger. The arrow keys of a fling reach the session as input, which returns a display to the bottom; if they stopped the fling, a fling on the alternate screen would end after its first key. The fling runs as a `LaunchedEffect` of the state that `TerminalDisplay` holds, since the module may not launch coroutines of its own (`kotlinx.coroutines.launch` is not among its allowed references either), and so that the user's input and the app's scrolling, which reach the display, can stop it.

**Alternatives considered.**
- **Flinging only through the scrollback**: The user asked for every mode, and a flick in a pager on the alternate screen is expected to keep moving too.
- **An estimate of the speed of vtfx's own**: A least-squares fit as Android's legacy tracker makes it worked, but would differ from Compose's lists in how far a flick goes, and is code to keep.
- **A touch that stops a fling also acting as a tap**: The keyboard would show, or a link open, when the user only meant to stop.
- **Not flinging a touchpad's pan**: The user asked for touchpads to act as fingers do. Android reports the end of a pan without a speed, so the speed comes from its moves, and the end, which moves nothing, is not taken as the fingers stopping; fingers that rest for 40 ms before they are lifted, as long as Compose's tracker waits, do not fling.

**Impact of reversing.** Remove the use of `VelocityTracker`, `Fling`, `SwipeScroller`'s use by a fling, `FlingRunner`, `TerminalDisplay.fling`, `stopFling`, and `sendScrollKey`, the package `androidx/compose/ui/input/pointer/util` in `config/boundaries.toml`, and the text in section 2 of [compose-input.md](../design/compose-input.md).

## 4. The loupe

While a long press selects and while a selection handle is dragged, Compose's `Modifier.magnifier` shows the platform's text loupe over the row under the finger (section 15 of [compose-input.md](../design/compose-input.md)).

**Why.** The finger covers the cells it selects, more so in a grid of small monospaced cells than in a text field; Android's text fields show the loupe for the same gestures, so users expect it there. `Modifier.magnifier` is in `androidx.compose.foundation`, which the boundaries allow, and it gives the platform's look, magnification, and placement, as Compose's own text fields use it; vtfx only says where. Centering it on the row, not on the finger, keeps the row level in the loupe as the finger wobbles, as `TextView` does. The menu of the last selection goes while a long press selects: on a device, it stayed over the row until the finger was lifted, and covered the loupe; for the same reason, and because it would point at other text, it goes when the display zooms, moves, or scrolls.

**Alternatives considered.**
- **Drawing a loupe of vtfx's own in a popup**: The terminal could draw its rows larger without the platform, but it would not match the device's other loupes, and would need a window of its own, as the handles do.
- **Showing it also for mouse selections**: A pointer does not cover the text; Android shows no loupe for a mouse.

**Impact of reversing.** Remove `TerminalDisplay.loupeCenter`, `showLoupe`, and `hideLoupe`, their calls in `TerminalGestures.kt` and `SelectionHandles.kt`, the `magnifier` modifier in `Terminal.kt`, and section 15 of [compose-input.md](../design/compose-input.md).

## 5. Zooming into the display

A pinch magnifies a part of the display, from 1 to 4 times, and the terminal keeps its columns and rows (section 16 of [compose-input.md](../design/compose-input.md)). A touchpad zooms with its pinch and with Ctrl+wheel, about the pointer, and moves the zoomed display with a two-finger swipe, as fingers do on the grid (section 3). One finger moves the zoomed display, and what the display cannot take at its top or bottom edge scrolls as a swipe does. The user's input moves the display to the cursor. Zooming is always on. `TerminalState` gives `zoom` and `resetZoom()` and keeps the zoom with the view.

**Why.** The user decided that a pinch zooms into the display rather than change the font size: the host's size does not change, so a full-screen program is not redrawn, the scrollback is not reflowed, and the line numbers that the selection, the search, and the saved view hold stay valid. The use is reading small text for a moment; a lasting change of the size is `TerminalStyle.fontSize`, which the app already owns. Below 1, the display would show empty space around the grid. Four times is enough to read 6 sp text at 24 sp. Moving the display before scrolling is how a zoomed page in a browser behaves, and keeps a swipe meaning one thing on the unzoomed display; scrolling with the rest lets the user read on without unzooming. Following the cursor while typing keeps the text the user types in view, as a zoomed text field does; it stops when the user moves the display, so that the user can look elsewhere while output arrives. The zoom is display state, as the scroll position is, and survives leaving the composition for the same reasons ([0095](0095-keeping-the-view.md)); it does not depend on the lines, so it is kept for another session too. The magnification is a `graphicsLayer` over the grid, so that Compose maps taps, long presses, semantics, and the loupe through it, and text recorded in `RenderNode`s is redrawn at the larger size.

**Alternatives considered.**
- **Ctrl+wheel going to the host, as other modifiers of the wheel do**: Browsers and desktop apps zoom with Ctrl+wheel, and some touchpads give a pinch as it. While the host asks for the mouse, the host still gets it, as every other wheel event, unless Shift is held.
- **Changing the font size, resizing the terminal**: Rejected by the user: the host is resized and redraws, and the scrollback reflows on each pinch.
- **A pinch that previews a scale and changes the font size on release**: The same resize at the end, with a jump from the preview to the new layout.
- **A parameter to turn zooming off**: The user decided that it is always on; an app that wants no zoom can call `resetZoom()`.
- **Zooming below 1 to show more columns**: The grid has no more columns to show.
- **One finger always moving the zoomed display, never scrolling**: The user would unzoom to scroll and zoom again.
- **Double tap to zoom**: A tap would wait for the double-tap timeout before showing the keyboard or clicking a link.

**Impact of reversing.** Remove the zoom of `TerminalDisplay` (`zoom`, `pan`, `setViewport`, `zoomBy`, `panBy`, `resetZoom`, `revealCursor`), `terminalPanAndZoom` in favor of a vertical swipe detector on the grid, the `graphicsLayer` and the conversions in `Terminal.kt`, `SelectionHandles.kt`, `TerminalMouse.kt`, and `CursorAnchorReporter.kt`, the zoom of `SavedView` and `TerminalState`, section 16 of [compose-input.md](../design/compose-input.md), and the text in section 7 of [ime-contract.md](../design/ime-contract.md) and [usage.md](../usage.md).
