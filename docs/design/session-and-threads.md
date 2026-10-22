# Session, threads, resize, and lifecycle

This document describes the thread conventions of all modules, the terminal session (`TerminalSession` in `vtfx-session`) and its boundary with the display (`vtfx-compose`), resizing, and the lifetime of the session. The module structure is in [architecture.md](architecture.md), and the threads of the IME calls in section 8 of [ime-contract.md](ime-contract.md).

## 1. Thread conventions

### 1.1 Modules that do not depend on Android do not assume a thread

`vtfx-core`, `vtfx-input`, `vtfx-gate`, `vtfx-session`, `vtfx-font`, and `vtfx-font-gate` do not assume a particular thread (the main thread or the like) and have nothing equivalent to a `Looper`. They can be used from any thread, and outside Android (JVM tests, JVM programs). Only the Android modules, `vtfx-gate-android`, `vtfx-compose`, and `vtfx-font-gate-android`, deal with the main thread ([decisions/0024](../decisions/0024-thread-conventions.md), [decisions/0026](../decisions/0026-session-module.md)).

### 1.2 Thread categories

The KDoc of each public type says which of the following it is ([decisions/0024](../decisions/0024-thread-conventions.md)). Annotations are not used for this in the JVM modules.

| Category | Meaning |
|---|---|
| Immutable | Usable from any thread. |
| Thread-safe | May be called from multiple threads at the same time. |
| Not thread-safe | Do not call concurrently. Which thread it is called from does not matter. The caller serializes (with a lock or anything else that creates a happens-before relation between calls). |
| Main-thread-only | Call only on the main thread. Nothing checks it at run time. |

### 1.3 Calling the app's code

Rules common to calls of callbacks, listeners, and interfaces the app implements (`HostConnection` and so on) ([decisions/0024](../decisions/0024-thread-conventions.md)):

1. **Thread**: Unless stated otherwise, the app's code is called synchronously on the same thread as, and within, the call that triggered it. Anything called on another thread says so explicitly.
2. **Locks**: The app's code is not called while holding the library's internal locks. Only locks for serializing calls are an exception, and holding them is stated explicitly (`HostWriter`, the input lock of `TerminalSession`).
3. **Reentry**: The app's code does not call the object that called it. Where it can be detected, it is detected with `check` and becomes `IllegalStateException`. Notifications called after releasing locks and finishing processing may explicitly allow calling the object that called them (`TerminalSessionListener`).
4. **Exceptions**: The app's code does not throw. If it does, the exception propagates to the caller as a programming error and is not swallowed; the state of the object after it propagates is not guaranteed. Failures that the platform documents for what the app's code does, such as no app to open a URL, are not exceptions in this sense: the interface returns them as values (`UrlOpener.open` returns an `OpenUrlResult`), and the implementation converts only the exceptions it expects for them (`ActivityNotFoundException` and `SecurityException` from `startActivity` in `AndroidUrlOpener`) and lets any other propagate. vtfx catches nothing around the call ([decisions/0043](../decisions/0043-url-open-results.md)).

### 1.4 Application per module

| Module | Type | Category and conventions |
|---|---|---|
| vtfx-core | `Terminal` | Not thread-safe. `TerminalEventListener` is called on the same thread within `write`, and must not call `Terminal` (detected). |
| vtfx-core | Values such as `TerminalSnapshot`, `KeyStroke`, `Hyperlink`, `LinkPattern`, `TerminalLimits` | Immutable. |
| vtfx-input | `ImeEditor` | Not thread-safe. `Listener` is called on the same thread within the editing call. Confining it to Android's UI thread is how `vtfx-compose` uses it. |
| vtfx-gate | `HostWriter` | Thread-safe. Calls `HostConnection.write` holding a lock for serialization. |
| vtfx-gate | `TerminalGate`, `GatePolicy` | `TerminalGate` has no state of its own and is thread-safe if its dependencies are. `GatePolicy` is immutable. |
| vtfx-gate | `HostConnection` | Implemented by the app. Called from any thread, one call at a time in order (never concurrently or nested). Must not block (queue and send on another thread). Must not call vtfx from within. |
| vtfx-gate | `ClipboardAccess`, `UrlOpener` | Implemented by the app or by vtfx-gate-android. May be called from any thread, so implement them thread-safely. |
| vtfx-gate-android | `AndroidClipboard`, `AndroidUrlOpener` | Thread-safe. |
| vtfx-session | `TerminalSession` | Thread-safe (section 2.2). |
| vtfx-session | `TerminalSessionListener` | Called on the thread of the triggering call, without holding the session's locks. May call the session (section 2.5). |
| vtfx-compose | `TerminalDisplay` inside `Terminal`, `TerminalState` | Main-thread-only. `TerminalDisplay` forwards notifications from the session to the main thread. |
| vtfx-compose | `TerminalStyle`, `TerminalColors` | Immutable. |
| vtfx-font | `FontFile`, `FaceInfo` | `FontFile` holds no state; `sanitize` takes up to seconds and is called on a background thread. `FaceInfo` is immutable. |
| vtfx-font-gate | `SanitizedFont`, `SanitizedFamily`, `FontLimits` | Immutable. |
| vtfx-font-gate-android | `SanitizedTypefaces` | Thread-safe. |

## 2. The terminal session

### 2.1 Responsibility

`TerminalSession` ties together the terminal of `vtfx-core`, the IME editing model of `vtfx-input`, and the `TerminalGate` of `vtfx-gate`: it interprets host output, sends user input, and handles host requests (requirements 3 and 4.1). It does not depend on Android, Compose, or kotlinx-coroutines, so JVM programs can emulate a terminal without a UI, and app tests can use the same session as production, synchronously, without Robolectric or virtual time ([decisions/0026](../decisions/0026-session-module.md)). There is no separate headless type.

The session takes the app's `HostConnection` as its first argument and hands it at once to the `HostWriter` it creates; it does not keep the connection ([decisions/0027](../decisions/0027-session-takes-connection.md)). Likewise it hands `ClipboardAccess` and `UrlOpener` to the `TerminalGate` without calling them ([decisions/0042](../decisions/0042-user-clipboard-through-gate.md)). Which modules may use the members of these types is checked by the boundary check (section 5 of [architecture.md](architecture.md)).

The session holds only the state of the terminal session. The state of a display is held by a `TerminalDisplay` per `Terminal` composable in `vtfx-compose`:

| vtfx-session (`TerminalSession`) | vtfx-compose (`TerminalDisplay` per `Terminal`) |
|---|---|
| The terminal, `TerminalGate`, `feed`, and handling requests to the gate (outside the lock) | Forwarding notifications to the main thread |
| Bells and titles, and their notifications | Invalidating rendering |
| `ImeEditor`, and deciding to reset the input history (5.1 of [ime-contract.md](ime-contract.md)) | The connection with the IME's `View` |
| Sending keys, text, pastes, mouse reports, and focus reports; opening URLs | The scrollback position, following scrollback, the selection, and copying it |
| `stickyModifiers`, `directInput` | Batching resizes over 150 ms (section 3.2) |
| `resize` (applied immediately), `snapshot`, modes | Waiting up to 200 ms to draw during synchronized output (mode 2026) |
| Answering color queries with the colors the display set (not answered without a display) | Setting the colors and the default cursor shape of `TerminalStyle` |

The display receives screen changes, sent input, and requests to restart the IME through `SessionObserver`, an `@InternalVtfxApi` interface. Several observers may be registered (`CopyOnWriteArrayList`), so two `Terminal`s showing the same session at once are both updated. IME operations of the display, including reading the composing text for drawing, go through `TerminalSession.edit` (`@InternalVtfxApi`), which runs the operation on `ImeEditor` inside the session's lock. The colors for color queries (OSC 4, 10, 11, and 12) are set by the display through `reportedColors` (`@InternalVtfxApi`); until a display sets them, and for sessions without a display, color queries are not answered. There is no public API for them.

`title` is read from the session, and its changes are reported with `onTitleChanged`; `stickyModifiers` likewise with `onStickyModifiersChanged`. An app that shows them in Compose copies them into its own state. `directInput` is changed only by the app, so it is not reported.

### 2.2 Locks

`TerminalSession` is thread-safe: any operation may be called from any thread, and the calls are serialized internally. It has two locks:

- **The state lock** guards the terminal, the IME editing model, and the fields.
- **The input lock** serializes user input up to sending, so that the host receives it in the order the IME editing model produced it, also from several threads. It is taken before the state lock and held while `HostConnection.write` runs for the input, which is the exception for locks that serialize calls (rule 2 of section 1.3). It is fair (`ReentrantLock(true)`): while one thread is inside `HostConnection.write`, the threads with input take it in the order they came.

An input operation (keys, text, pastes, sticky modifiers, direct input, mouse and focus reports, and IME operations through `edit`) takes the input lock, then runs on the editing model inside the state lock, collecting what to send; it sends after releasing the state lock, holding only the input lock; then, without either lock, it notifies the observers and the listener.

`feed` interprets the output inside the state lock and collects there what it has to report and the requests to the gate (replies to queries and OSC 52), in the order they arose. After releasing the lock, it applies a reset of the input history if the output moved the cursor to another line (as an input operation, unless an input operation on another thread already applied it before its edit), notifies the observers, the bell, and the title, and last hands the requests to the gate, so that an exception from the app's code leaves the session up to date. The values of queries (the cursor position and so on) are fixed by the state when the requests were collected, so handling them after the lock does not change the replies.

### 2.3 Calling the app's code

The session calls the app's `HostConnection`, `ClipboardAccess`, and `UrlOpener` on the thread that called the operation for user operations, and on the thread that called `feed` for host requests. None is called holding the state lock. `HostConnection.write` for user input is called holding the input lock, and every call of it holds the lock of `HostWriter`, which serializes the calls. `ClipboardAccess.read` for `pasteFromClipboard` is called before taking any lock.

### 2.4 Reentry

Calls back into the session that would break its state are detected with `check` and throw `IllegalStateException` (rule 3 of section 1.3):

- `feed` from within `HostConnection.write` is detected at the entry of `feed`. It fails as soon as `feed` is called, not only when a reply arises, so it is found regardless of host output. A host that answers at once, such as a local loopback, hands its output to another task.
- Sending input (`sendKey` and so on) from within `HostConnection.write` is detected at the entry of the input operation, before the IME editing model changes. The input lock is reentrant, so the check in `HostWriter` alone would come after the model had taken the input for sent.
- Sending input or calling `feed` from within an operation that `edit` runs, which holds the session's lock, is detected the same way.

### 2.5 Notifications

`TerminalSessionListener` is called on the thread of the triggering call, after the locks are released and the processing is finished, so a listener may call the session (the exception in rule 3 of section 1.3). Bells and titles come on the thread that called `feed`, once per `feed` (with the last title the output set); resizes on the thread that called `resize`; sticky modifiers on the thread that changed them. Nothing is forwarded to the main thread: `vtfx-compose` does that for the display itself.

`onResize`, `onTitleChanged`, and `onStickyModifiersChanged` report the latest value, without holding a lock (`LatestValueNotifier`). Each change is recorded inside the state lock, in order. If another thread changes the same value while one thread is reporting, the reporting thread reports the change as the latest value after its listener returns, and intermediate values are collapsed. So even when the value is changed from several threads, the last notification is the current value. A listener that changes the value is told again after it returns, not inside its own call. Used from a single thread (as Compose's `Terminal` does for the size and the modifiers), notifications arrive on the thread that made the change, within that call.

`SessionObserver` is called the same way: on the thread that made the change, without the session's locks held.

### 2.6 Lifetime

The session holds no resources besides memory and schedules no timers, so there is no `close()`; dropping it lets GC collect it. The app owns the session, and it may outlive any `Terminal` that displays it (section 4).

## 3. Resize

### 3.1 The session

`TerminalSession.resize(columns, rows, cellWidth, cellHeight)` resizes the terminal at once, and calls `TerminalSessionListener.onResize` if the size changed, if the size of a cell in pixels changed, or if it is the first `resize` of the session, even when the size is the one the session was created with ([decisions/0013](../decisions/0013-resize-and-lifecycle.md)). The app can therefore wait for the first `onResize` before starting the host (for example, before requesting the PTY), instead of starting it with the constructor's size and resizing it right away. The first report is per session, not per `Terminal`: a `Terminal` recreated after a configuration change does not report the same size again. A `resize` that fails its argument checks does not count as the first.

`onResize` has two overloads: `(columns, rows)`, and `(columns, rows, widthPixels, heightPixels)`, which calls the first by default. The pixels are the size of a cell that a display gave times the number of cells, or 0 by 0 if none was given ([decisions/0038](../decisions/0038-kitty-graphics.md)). Notifying the host (an SSH window-change request, TIOCSWINSZ on a PTY) depends on the connection, so the app does it from `onResize`.

The size is from 1 to `TerminalLimits.MAX_DIMENSION` in each direction, and a cell from 0 to 1000 pixels on each side, both 0 or neither. Before any `resize`, the session has the size given to its constructor, 80 × 24 by default (`DEFAULT_COLUMNS`, `DEFAULT_ROWS`).

### 3.2 The display

`Terminal` computes the number of columns and rows from its size inside the padding of `TerminalStyle`, and gives them, with the size of a cell in pixels, to its `TerminalDisplay` on every layout ([decisions/0013](../decisions/0013-resize-and-lifecycle.md), [decisions/0026](../decisions/0026-session-module.md)):

- The first size of a `TerminalDisplay` is applied to the session at once, also for a `Terminal` recreated after a configuration change.
- Later sizes are applied only after the size has stayed the same for 150 ms (`RESIZE_DELAY_MILLIS` in `TerminalDisplay`), so that during the animation of showing the keyboard or rotating, the reflow and the notification to the host (SIGWINCH) happen once. The same mechanism covers rotation, multi-window, and foldables; the state of the IME animation (`WindowInsetsAnimation`) is not used.
- During the animation, drawing keeps the grid of the old size, clipped to the composable.
- Where its constraints leave the size free, `Terminal` takes 80 × 24 cells, or as much of that as the constraints allow. Laid out with no width or height, it logs a warning and does not resize the session ([decisions/0032](../decisions/0032-keys-and-layout.md)).

### 3.3 Reflow

`vtfx-core` (`Emulator.resize`, `Reflow`) handles a size change as follows ([decisions/0013](../decisions/0013-resize-and-lifecycle.md)):

- When the width changes, the main screen and the scrollback are reflowed. The cells of a run of rows joined by autowrap (a logical line) are laid out again at the new width, and a wide character that does not fit at the end of a row goes to the next row. The blank that autowrap left at the end of a row before such a character is not a character, so it is not laid out again, unless the cursor is on it. A space the host wrote there remains as a character; a cell the host erased cannot be told from an empty one and is treated as empty. Link detection and copying a selection use the same judgment (`Line.endsWithWidePadding`). Trailing blanks of a logical line are dropped, except up to the cursor.
- The cursor moves along with the character it was on. It is reflowed as an insertion point: a cursor waiting to wrap (right after writing in the last column) is treated as the position where the next character will be written, so it stays right after the character just written; if that position is exactly the end of a new row, it stays waiting to wrap.
- The screen shows the last rows, ending at the last row with content or the cursor, and the rows above it are the scrollback. When the height decreases, upper rows move to the scrollback so that the cursor stays on the screen, and when it increases, they come back from the scrollback.
- The alternate screen is not reflowed: it is cut or padded with blanks, because apps on it (vim, htop, and so on) learn the new size and redraw. While it is shown, the saved cursor of the main screen (mode 1049) is reflowed with the main screen.
- The other saved cursors (DECSC) are not reflowed. DECRC clamps them to the screen, and restores a cursor waiting to wrap only if it lands in the last column, since a cursor waits to wrap nowhere else.
- A wide character cannot be shown at width 1, so it is dropped when the width becomes 1. A cursor on it stays at the insertion point where it was, on the same row, waiting to wrap if that row is full.
- A resize does not count as the cursor moving to another line, so it does not reset the IME input history (5.1 of [ime-contract.md](ime-contract.md)).

## 4. Lifecycle

The app owns the `TerminalSession` and keeps it outside the composable, for example in a `ViewModel` as the sample app does. When the composable goes away and is recreated, for example after a configuration change, the content, the modes, and the IME input history are kept in the session, and the input `View` reconnects to the session when focus returns to the recreated composable ([decisions/0007](../decisions/0007-compose-session.md), [decisions/0013](../decisions/0013-resize-and-lifecycle.md)).

The display state goes away with the composable. The scrollback position is not kept: a recreated `Terminal` shows the bottom, since a rotation resizes and reflows the screen and the same lines may not be visible anyway ([decisions/0026](../decisions/0026-session-module.md)).

Rotation, multi-window, and foldables are all treated as combinations of "the display size changes" and "the Activity is recreated", with no handling specific to each. They are tested through reflow and height changes (`TerminalTest` in vtfx-core), batching sizes (`TerminalDisplayTest`), the first report (`TerminalSessionTest`), and recreating the composable (`TerminalSemanticsTest.sessionOutlivesTheComposable`).
