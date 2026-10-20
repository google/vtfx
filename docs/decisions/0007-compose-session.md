# Structure of the session and the Composable

- Status: Approved (2026-10-13, approved by a human in the final review)
- Date: 2026-10-12
- Related: 3.5 of [architecture.md](../design/architecture.md), sections 5 and 7 of [ime-contract.md](../design/ime-contract.md), requirements 5.3, 5.5, and 6.3, `vtfx-compose`

## Background

`vtfx-compose` connects the modules and makes them a public API. The approaches to threads, the IME connection, rendering updates, and accessibility needed to be decided.

## Decision

### Shape of the public API

- The app owns the `TerminalSession`. The app creates `HostWriter(HostConnection)` from its own host connection and passes it. The code of `vtfx-compose` does not refer to `HostConnection` (a restriction in `config/boundaries.toml`).
  - 2026-10-14: [0027](0027-session-takes-connection.md) changed this so that `TerminalSession` takes `HostConnection` directly and creates the `HostWriter` itself. `vtfx-compose` still does not refer to `HostConnection`.
- Changes in the terminal size are reported to the app with `TerminalSessionListener.onResize`. Notifying the host (SSH window-change, PTY TIOCSWINSZ) depends on the connection method, so the app does it.
- For an extra key bar, `TerminalSession.sendKey(KeyStroke)` and `sendText` are public (requirement 5.4).
- `Terminal(session, modifier, style)` handles display and input. The number of columns and rows is determined by the size of the Composable.

### Threads

- `feed` (host output) can be called from any thread. The terminal model is protected by a lock. Other operations are called from the main thread. This contract is checked at run time ([0021](0021-thread-checks.md)).
- Events from the terminal model are handled on the thread that called `feed`. The gate handles replies and the clipboard directly, and titles and bells are handed to the main thread.
- Screen updates are batched into one post to the main thread, which increments Compose state (a version number) to invalidate only drawing. No recomposition happens.
- 2026-10-14: [0026](0026-session-module.md) moved `TerminalSession` to `vtfx-session` and made it thread-safe. "Other operations are called from the main thread" above, and handing titles and bells to the main thread, no longer apply. Listeners are called on the thread of the triggering call. Batching screen updates into one post is done by a `TerminalDisplay` per `Terminal`.

### IME

- ~~Create our own `InputConnection` with Compose's `PlatformTextInputModifierNode`.~~ The e2e revealed that Compose's wrapper does not forward `replaceText`, so this changed to connecting through a `View` placed behind the terminal ([decisions/0008](0008-ime-view-and-selection.md)).
- The `InputConnection` is a thin adapter that delegates to `ImeEditor`, and does not use the `Editable` of `BaseInputConnection`. The adapter sends selection notifications (`updateSelection`) and `CursorAnchorInfo`.
- Of the triggers for resetting the input history (5.1 of the contract), "host output moved the cursor to another line" is judged by comparing the cursor's row and the alternate screen state before and after `feed`. It also resets when the echo of long input wraps. The impact is limited to later autocorrects and the like being rejected as outside the range (to be revisited in M10; a TODO was left in the code). Resolved in M10: when the rows before and after are all joined by wrapping, it is not treated as a move ([0013](0013-resize-and-lifecycle.md)).
- Pastes (the IME's context menu and `pasteFromClipboard`) read from the `ClipboardAccess` passed to the session. `vtfx-compose` does not use `ClipboardManager` directly.

### Rendering

- Runs of ASCII with the same style are drawn at once, and other characters are drawn per cell. Characters not in the monospace font (CJK and so on) are drawn with a fallback font whose widths do not fit the cells. Performance optimization was done in M11 ([0014](0014-performance.md)).
- Composing text is drawn overlaid from the terminal's cursor cell, wrapping at the right edge. An underline shows that it has not been sent.
- In reverse video (DECSCNM), the foreground color is used for the cursor color, because the default cursor color becomes the same as the background.

### Accessibility

- The text of the displayed screen is exposed as the `text` of Compose semantics. It is computed only when semantics are requested (when there is an accessibility service or a test), and invalidated when the screen changes. The details of reading (change notifications, ranges) were decided in M12 ([0015](0015-accessibility.md)).

### Test environment

- The SDK for tests run with Robolectric is 36. With compileSdk 37, Robolectric 4.17 and the Espresso event injection used by Compose's test rule fail with `NoSuchMethodException`.

## Alternatives considered

- **Limit `feed` to the main thread**: The implementation would be simpler, but large output would clog the main thread. Many apps (SSH libraries and so on) receive data on another thread.
- **Use `AndroidView` and our own `View` for the IME connection**: Initially, Compose's official API was judged to be enough, but 0008 changed to this.

## Impact of reversing

The public API (the arguments of `TerminalSession`, `TerminalSessionListener`) was revisited in M13, and the public surface and compatibility policy were recorded in [0016](0016-public-api.md). To change the threading approach, change `TerminalSession`.
