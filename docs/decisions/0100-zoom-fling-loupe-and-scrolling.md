# Zooming, flinging, the loupe, and scrolling from the app

- Status: Provisional
- Date: 2026-11-03
- Related: Requirements 4.1 and 5.4, sections 2, 4, and 5 of [compose-input.md](../design/compose-input.md), [usage.md](../usage.md), [0084](0084-session-and-threads.md), [0085](0085-compose-interaction.md), [0095](0095-keeping-the-view.md), [0097](0097-selection-handles.md)

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
