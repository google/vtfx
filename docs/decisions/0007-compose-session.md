# Structure of the session and the Composable

- Status: Approved (2026-10-13, approved by a human in the final review); the threads superseded by [0024](0024-thread-conventions.md) and [0026](0026-session-module.md), the IME connection through `PlatformTextInputModifierNode` by [0082](0082-ime-editing-model.md), passing a `HostWriter` by [0027](0027-session-takes-connection.md), and the single accessibility node by [0015](0015-accessibility.md)
- Date: 2026-10-12
- Related: 3.5 of [architecture.md](../design/architecture.md), sections 2 and 4 of [session-and-threads.md](../design/session-and-threads.md), sections 5 and 7 of [ime-contract.md](../design/ime-contract.md), [0042](0042-user-clipboard-through-gate.md), requirements 5.3, 5.5, and 6.3, `vtfx-compose`

## Background

`vtfx-compose` connects the modules and makes them a public API. The approaches to the shape of the public API, threads, the IME connection, rendering updates, and accessibility needed to be decided.

## Decision

### Shape of the public API

- The app owns the `TerminalSession`, so the session lives outside the Composable and survives configuration changes (section 4 of [session-and-threads.md](../design/session-and-threads.md)). The app passed a `HostWriter` made from its own `HostConnection`, so that `vtfx-compose` does not refer to the connection; [0027](0027-session-takes-connection.md) replaced this with passing the `HostConnection` itself.
- Changes in the terminal size are reported to the app with `TerminalSessionListener.onResize`. Notifying the host (SSH window-change, PTY TIOCSWINSZ) depends on the connection method, so the app does it.
- For an extra key bar, `TerminalSession.sendKey(KeyStroke)` and `sendText` are public (requirement 5.4).
- `Terminal(session, modifier, style)` handles display and input. The number of columns and rows is determined by the size of the Composable.

### Threads

`feed` (host output) could be called from any thread, with the terminal model protected by a lock, and other operations from the main thread. Screen updates are batched into one post to the main thread, which increments Compose state (a version number) to invalidate only drawing, with no recomposition. [0024](0024-thread-conventions.md) and [0026](0026-session-module.md) replaced the rest: the session is thread-safe, listeners are called on the thread of the triggering call, and the batching is done by a `TerminalDisplay` per `Terminal` (section 2 of [session-and-threads.md](../design/session-and-threads.md)).

### IME

- The IME connection was to be our own `InputConnection` made with Compose's `PlatformTextInputModifierNode`. Compose's wrapper does not forward `replaceText`, so [0082](0082-ime-editing-model.md) replaced this with a `View` placed behind the terminal.
- The `InputConnection` is a thin adapter that delegates to `ImeEditor`, and does not use the `Editable` of `BaseInputConnection`. The adapter sends selection notifications (`updateSelection`) and `CursorAnchorInfo`.
- "Host output moved the cursor to another line", a trigger for resetting the input history, is judged by the session from the terminal's state before and after `feed`; following the echo of long input over autowrapped rows is not a move ([0013](0013-resize-and-lifecycle.md); 5.1 of [ime-contract.md](../design/ime-contract.md)).
- Pastes (the IME's context menu and `pasteFromClipboard`) read from the `ClipboardAccess` passed to the session. `vtfx-compose` does not use `ClipboardManager` directly. [0042](0042-user-clipboard-through-gate.md) routes the reading through the gate.

### Rendering

- Runs of ASCII with the same style are drawn at once, and other characters per cell; characters not in the monospace font (CJK and so on) are drawn with a fallback font. The details and the optimization are in [0079](0079-emulation-scope.md) and [0014](0014-performance.md).
- Composing text is drawn overlaid from the terminal's cursor cell, wrapping at the right edge. An underline shows that it has not been sent.
- In reverse video (DECSCNM), the foreground color is used for the cursor color, because the default cursor color becomes the same as the background.

### Accessibility

The text of the displayed screen was exposed as the `text` of one Compose semantics node, computed only when semantics are requested. [0015](0015-accessibility.md) replaced the single node with a node per row.

### Test environment

The SDK for tests run with Robolectric is 36. With compileSdk 37, Robolectric 4.17 and the Espresso event injection used by Compose's test rule fail with `NoSuchMethodException`.

## Alternatives considered

- **Limit `feed` to the main thread**: The implementation would be simpler, but large output would clog the main thread. Many apps (SSH libraries and so on) receive data on another thread.
- **Use `AndroidView` and our own `View` for the IME connection**: Initially, Compose's official API was judged to be enough; [0082](0082-ime-editing-model.md) changed to this.

## Impact of reversing

The public API (the arguments of `TerminalSession`, `TerminalSessionListener`) was revisited in M13, and the public surface and compatibility policy are recorded in [0016](0016-public-api.md). The threading approach is now that of [0024](0024-thread-conventions.md) and [0026](0026-session-module.md).
