# The terminal session and threads

- Status: Approved
- Date: 2026-10-25
- Related: Requirements 1, 3, 4.1, 4.2, 5.3, 5.4, 5.5, 6.1, 6.2, and 6.5, [session-and-threads.md](../design/session-and-threads.md), sections 2.1, 3.5, 3.6, and 5 of [architecture.md](../design/architecture.md), sections 5.1, 7, 8, and 12 of [ime-contract.md](../design/ime-contract.md), section 4.2 of [gate-policy.md](../design/gate-policy.md), sections 7.3 and 8.3 of [emulation.md](../design/emulation.md), [0082](0082-ime-editing-model.md), [0079](0079-emulation-scope.md), [0014](0014-performance.md), [0015](0015-accessibility.md), [0016](0016-public-api.md), [0080](0080-kitty-graphics.md), [0083](0083-gate.md), [0044](0044-boundaries-in-gradle-and-classes.md)

## Background

`vtfx-compose` connects the modules and makes them a public API, and `TerminalSession` ties together the terminal (`vtfx-core`), the IME editing model (`vtfx-input`), and the gate (`vtfx-gate`). The area had to decide where the session lives and what it holds, which threads each module may be called on and how the app's code is called, how the session serializes concurrent calls, how it reports changes, how the terminal size follows the display, and what survives configuration changes. The constraints: host output usually arrives on a thread other than the main thread; the JVM modules have no `Looper`; `vtfx-core` has no means of sending to the host, which is a security boundary; on mobile the size changes often (rotation, multi-window, foldables, the keyboard), and requirement 5.5 asks that resize notifications not repeat during the IME animation, that the session be kept across configuration changes, and that whether to reflow be decided.

## 1. Thread conventions of the modules

1. Modules that do not depend on Android (`vtfx-core`, `vtfx-input`, `vtfx-gate`, `vtfx-session`, `vtfx-font`, `vtfx-font-gate`) do not assume a thread and have nothing equivalent to a `Looper`; only the Android modules deal with the main thread (section 1.1 of [session-and-threads.md](../design/session-and-threads.md)).
2. The KDoc of each public type says which thread category it is in: immutable, thread-safe, not thread-safe (the caller serializes), or main-thread-only (section 1.2).
3. The app's code is called by four rules (section 1.3): thread (on the thread of, and within, the triggering call, unless stated otherwise); locks (not while holding the library's internal locks, except locks that only serialize calls, which are stated); reentry (the app's code does not call the object that called it, detected where it can be; notifications called after releasing locks and finishing processing may explicitly allow it); exceptions (the app's code does not throw; if it does, the exception propagates as a programming error and is not swallowed).
4. The rules apply per module as in section 1.4. `TerminalSession` calls `HostConnection`, `ClipboardAccess`, and `UrlOpener` outside its state lock: on the thread of the operation for user operations, and on the thread that called `feed` for host requests, which are collected inside the lock and handled in order after it. `feed` and input from within `HostConnection.write` are rejected (sections 2.3 and 2.4; the locks are in topic 3).

**Why.** Thread contracts written type by type, with no overall convention, were inconsistent: a JVM type described as used from "one thread" or "the UI thread" stated how `vtfx-compose` used it, not a contract of a module that does not depend on Android, and the interfaces that apps implement had no rules about which thread they are called on or how exceptions are handled. Calling the app's code while holding the session's lock lets an app that calls `feed` synchronously from within `write` reenter the emulator on the `feed` thread, or deadlock with reversed lock order on the main thread; and reading the clipboard is an inter-process call, which would keep `modes()` and the like on the main thread waiting.

**Alternatives considered.**
- **Call `HostConnection.write` only on the main thread**, matching a model in which every operation but `feed` and `snapshot` is main-thread-only: replies to host queries would detour through the reading thread, the main thread, and the writing thread, and be delayed when the main thread is clogged by drawing or GC. vim and tmux judge terminal features with reply timeouts, so protocol correctness would depend on UI responsiveness. Android cannot use the network on the main thread, so the app has to hand off from `write` to an I/O thread anyway, and the guarantee of being on the main thread does not help it. It also goes against item 1 (`vtfx-gate` would assume a `Looper`).
- **Queue sends and have whichever thread dequeue and send them in order**: calling `feed` from within `write` would not nest and could be allowed, but `HostWriter`, which is under security review, would gain mutable state (the queue, and whether dequeuing is in progress); the user's keys might be sent on another thread, and which thread exceptions propagate to would become hard to understand. Only loopback hosts that reply locally want to call `feed` from within `write`, and the sample app avoids it with `Handler.post`. Not taken until a situation that needs it appears.
- **The app passes an `Executor`, and `write` is called on it**: the app can do the same with `HostConnection { bytes -> executor.execute { ... } }`. Not a reason to add an API.
- **Catch exceptions from the app's code, log them, and continue**: it hides mistakes. Send failures (a dropped connection and so on) are handled by the app at its own layer, and what propagates to vtfx is a programming error.
- **Express the thread categories with annotations (`@ThreadSafe` and so on)**: annotations usable in JVM modules need a new dependency (JSR 305 or Error Prone), and are not checked by lint or the like.

**Impact of reversing.** To call `HostConnection` on a particular thread, change the handling of host requests in `TerminalSession.feed` to forward them to that thread, and revise the KDoc of `HostConnection`, `usage.md`, and section 2.3 of [session-and-threads.md](../design/session-and-threads.md). To allow `feed` from within `write`, remove the check at the entry of `TerminalSession.feed`, and turn `HostWriter` into a send queue. The related tests are those in `TerminalSessionTest` for handling host requests outside the lock, and for rejecting `feed` and input from within `write` and from within an edit operation.

## 2. `TerminalSession` in a JVM module, holding only the state of the session

1. `TerminalSession` is in the JVM module `vtfx-session`, which depends on `vtfx-core`, `vtfx-input`, and `vtfx-gate`; `vtfx-compose` depends on it. One type, `TerminalSession`, is used by the app's production code, its tests, and JVM programs alike; there is no separate headless type (section 2.1 of [session-and-threads.md](../design/session-and-threads.md), section 3.6 of [architecture.md](../design/architecture.md)).
2. The session holds only the state of the terminal session. The display state is held by a `TerminalDisplay` per `Terminal` composable in `vtfx-compose`, and several displays may observe one session at once (section 2.1).
3. The session has no timers: `resize` is applied at once, and batching resizes and the upper bound on waiting for synchronized output are done by `vtfx-compose` (topic 7). So `vtfx-session` depends on neither kotlinx-coroutines nor Compose (sections 2.1 and 2.6).
4. What belongs to a display is set by the display. The scrollback position goes away with the `Terminal` (section 4). The colors that answer color queries are set by `Terminal` through an `@InternalVtfxApi` entry point, a session without a display does not answer them, and no public API is added. IME operations of `vtfx-compose` run on `ImeEditor` inside the session's lock through `TerminalSession.edit` (`@InternalVtfxApi`) (section 2.1).
5. App tests can be written synchronously: with no timers and no thread assumptions, it is enough to `feed` from JUnit and look at `snapshot`, without Robolectric or virtual time.

**Why.** Emulating terminal behavior from JVM programs without a UI is a goal (requirements 1, 3, and 4.1; runtimes other than the JVM are out of scope, requirement 4.2), and the class representing the state of the terminal session does not belong in `vtfx-compose`. The main use of a terminal without a UI is unit tests of terminal apps, and what those tests check is the app's production code, which receives a `TerminalSession`; a separate headless type could not be passed to that code. Divided by responsibility, most of the session does not depend on the UI (the terminal, the gate, `ImeEditor`, sending, bells and titles); what does (invalidating the display, the scrollback position, the selection, batching resizes over 150 ms, waiting up to 200 ms to draw during synchronized output, converting colors, and the connection with the IME's `View`) belongs to a display, as did every use of `Handler` (checking for the main thread, posting to it, and the timers). The session cannot go to `vtfx-core`: it uses `vtfx-gate` and `vtfx-input`, but `vtfx-core` depends on nothing, and its having no means of sending to the host is a security boundary. Having no timers minimizes dependencies (requirement 6.5).

**Alternatives considered.**
- **Keep the session in `vtfx-compose` and pass it a `CoroutineScope`**, whose dispatcher sets the session's thread and whose `Job` its lifetime, in place of `Handler`, so that it works in JUnit: app tests would work, but JVM programs would depend on `vtfx-compose` (an Android library) and Compose, and the state of the terminal session and the display state would remain together in one class.
- **Move it to `vtfx-core`**: it breaks the direction of dependencies and the security boundary.
- **Publish a `HeadlessTerminal` separately and have `TerminalSession` (in `vtfx-compose`) use it internally**: app tests could not pass it to production code.
- **Keep the scrollback position across a recreated `Terminal`**: rotation involves resizing and reflow, so even if kept, the same lines may not be visible.

**Impact of reversing.** If `TerminalSession` goes back to `vtfx-compose`, passing it a `CoroutineScope` is the next candidate. The public API changes: the package of `TerminalSession`, the threading promise of the listener (topic 4), and how `title` is read. Revise the session section and app testing section of `usage.md`, the module structure in [architecture.md](../design/architecture.md), [session-and-threads.md](../design/session-and-threads.md), and `config/boundaries.toml`.

## 3. The session is thread-safe, with three fair locks

1. Any operation of `TerminalSession` may be called from any thread, and the calls are serialized internally. The locks are in one class, so that the relations between them can be verified in one place (section 2.2 of [session-and-threads.md](../design/session-and-threads.md)).
2. The state lock guards the terminal, the IME editing model, and the fields, and is a fair `ReentrantLock`. `feed` keeps interpreting each chunk of the app's whole under it.
3. The input lock serializes user input up to sending, is taken before the state lock, is fair, and is held while `HostConnection.write` runs for the input.
4. The output lock guards no state and serializes `feed` from the interpretation of the output to the end of the gate's handling of its requests. It is taken before the state lock and is fair. It is held while the gate calls `HostConnection.write`, `ClipboardAccess`, and `ReportedColors`, the exception for locks that only serialize calls (rule 2 of section 1.3), but not while the session tells the observers or the listener.
5. `feed` and the input operations throw `IllegalStateException` on a thread that holds the output lock, before changing any state (rule 3 of section 1.3; section 2.4).

**Why.** Internal serialization is needed even if input came from one thread: when host output moves the cursor to another line, the IME's input history is reset (5.1 of [ime-contract.md](../design/ime-contract.md)), which touches `ImeEditor` from the `feed` thread, and `vtfx-session` has no mechanism for forwarding to the main thread. For JVM programs (automation that calls from a reading thread and a test thread, and so on), thread-safety is also easier to use. Serializing `ImeEditor` calls with a lock does not break the flow of edits as the IME sees it: IME calls are separate messages arriving one at a time on the main thread, and even when confined to the main thread, extra key bar operations and resets caused by host output forwarded to the main thread could interleave between them, so a lock per call gives the same granularity of interleaving. Sending in the middle of a batch edit (pastes and so on) is something `ImeEditor` already handles (`TerminalSessionTest.pasteDuringABatchEditFollowsTheComposingText`).

The state lock is fair because an app's reader thread calls `feed` again as soon as the previous call returns while the host prints a large file, and an intrinsic lock does not hand itself to a waiting thread: the feeding thread takes it again at once, and frames (`snapshot`, and `edit` for the composing text) and keys wait far beyond the drawing target of [0014](0014-performance.md). A thread waiting for a fair lock gets it when the holder releases it, so a frame or a key waits for at most the `feed` in progress, which interprets one chunk of the app's; since each chunk is still interpreted whole, what a feed reports (line changes for the reset of the input history, bells, titles, and requests to the gate) does not change. A fair lock costs a handoff to the waiting thread each time there is one, about once per frame and per key; without a waiting thread it costs about as much as an unfair one. The throughput benchmark of [0014](0014-performance.md) measures `vtfx-core` without the session, and does not change.

The input lock is fair so that, while one thread is inside `HostConnection.write`, the threads with input take it in the order they came, and the host receives input in the order the IME editing model produced it.

The output lock exists because handing the requests of a `feed` to the gate after releasing the state lock, after telling the observers and the listener and with no lock around that step, reorders them: a listener may call the session, so input sent from `onBell` reached the host before the replies of the output that rang the bell; and of two feeds on different threads, the second could reach the gate first, sending its replies first or letting an older clipboard write overwrite a newer one. It is not held while the session tells the observers or the listener, so listeners may still call the session (topic 4). Calls into the session from the code that the gate calls are made to fail because the output lock is reentrant: such a call would otherwise nest and send its bytes before the rest of the outer feed's.

**Alternatives considered.**
- **Limit input operations to one thread (not thread-safe)**: on Android, calling from the main thread is enough, but the reset of the input history from the `feed` thread needs internal serialization anyway, and JVM programs find thread-safety easier to use.
- **Limit `feed` to the main thread**: the implementation would be simpler, but large output would clog the main thread, and many apps (SSH libraries and so on) receive data on another thread.
- **Divide the session into a terminal and gate part and an input part**: the relation between the locks (which is taken first) would be spread over two classes.
- **An intrinsic lock for input**: it may hand the lock to the latest waiting thread first (HotSpot does), so input made later could reach the host first while one thread is inside `HostConnection.write`.
- **Split a chunk in `feed` and release the state lock between the pieces**: an intrinsic lock still hands itself back to the feeding thread, so this alone does not help, and the effects that `feed` reports once per call would be split or need to be gathered across the pieces.
- **Draw from a copy of the screen that `feed` publishes after each chunk**: frames would not wait at all, but every chunk would pay for a copy of the screen, against the throughput targets of [0014](0014-performance.md), and input would still wait for the lock.
- **Yield in `feed` between chunks (`Thread.yield`)**: whether a waiting thread runs is up to the scheduler, so it does not bound the wait.
- **Hand the requests to the gate under the state lock**: the app's code (`HostConnection`, `ClipboardAccess`) would run under the lock that guards the state, against topic 1, and a frame or a key on the main thread would wait for the network.
- **Queue the requests and send them from one thread of the session**: the session would own a thread, which topic 2 avoids, and the replies would no longer be sent within the `feed` that caused them.
- **Take the input lock for feeds too**: input would wait for the interpretation of output, and output for a slow `HostConnection.write` of input, with no gain in order: replies and input are only ordered by when each is produced.

**Impact of reversing.** To make the state lock intrinsic again, change it in `TerminalSession`, remove `aReaderOfTheScreenWaitsForAFewFeedsAtMostWhileOutputFloods` from `TerminalSessionTest`, and remove the sentences on its fairness from section 2.2 of [session-and-threads.md](../design/session-and-threads.md). To remove the output lock, remove it and its reentry checks from `TerminalSession`, the tests of the order of replies, clipboard writes, and notifications in `TerminalSessionTest` (among them `repliesOfAFeedAreSentBeforeTheListenerFeedsMore` and `feedingFromTheClipboardThatTheGateCallsFails`), and the output lock from section 2.2 of session-and-threads.md and from section 3.6 of [architecture.md](../design/architecture.md); the replies of separate feeds could then be reordered again.

## 4. Notifications on the thread of the triggering call

`TerminalSessionListener` is called on the thread of the triggering call (rule 1 of section 1.3), after the locks are released, so listeners may call the session, as an explicit exception to the reentry rule. Nothing promises the main thread: `vtfx-compose` receives the notifications it needs through an entry point used only between modules (`@InternalVtfxApi`) and forwards them to the main thread. The listener reports titles and sticky modifiers (`onTitleChanged`, `onStickyModifiersChanged`) for apps to copy into their own Compose state; `vtfx-compose` adds no state holder. Value notifications report the latest value, so that the last one is the current value when several threads change it (section 2.5 of [session-and-threads.md](../design/session-and-threads.md)).

**Why.** This follows topic 1, and a JVM module has no main thread to promise; apps can move threads themselves.

**Alternatives considered.**
- **Publish notifications as `StateFlow`**: easy to read with Compose's `collectAsState`, but `vtfx-session` would depend on kotlinx-coroutines. A listener needs no dependency.
- **Give `TerminalSession` a setting for whether to notify on the main thread**: an Android concept would enter the JVM module. Apps can move threads themselves.
- **Notify value changes while holding a lock**: it would hang if a listener waited for a session operation on another thread.
- **Queue every value instead of reporting the latest**: it would take the same code (the ordering without a lock is what costs, not the collapsing), but the queue would have no bound and would report stale sizes to the host.

**Impact of reversing.** The threading promise of the listener and how `title` is read are public API (topic 2).

## 5. The session takes the connection to the host

1. `TerminalSession` takes `connection: HostConnection` as its first argument and creates the `HostWriter` right away during construction. The session does not keep the connection; it keeps only the `HostWriter` (section 2.1 of [session-and-threads.md](../design/session-and-threads.md)).
2. `HostWriter` is `@InternalVtfxApi`, not part of the public API. It is still only `vtfx-gate` that assembles and checks the bytes for the host.
3. `TerminalSession.kt` is not under `security_review` in `config/protected-paths.toml`.

Which modules may use the members of `HostConnection` is checked by the boundary check (section 5 of [architecture.md](../design/architecture.md), [0044](0044-boundaries-in-gradle-and-classes.md)).

**Why.** The only public declaration of `HostWriter` was its constructor, and to apps it was merely a wrapper to pass to `TerminalSession`, an extra type to create. Having apps create it kept `vtfx-compose` from holding the raw connection while the session lived there; in `vtfx-session` (topic 2) the session only hands the connection to `HostWriter`. `TerminalSession.kt` is left out of security review because it changes often and only hands the connection to `HostWriter`.

**Alternatives considered.**
- **Keep having the app create the `HostWriter`**: apps would keep creating a type that can do nothing. The boundary would not gain from it: what it protects, that only `vtfx-gate` uses the connection, is not compromised by `vtfx-session` receiving the connection.

**Impact of reversing.** Change the first argument of `TerminalSession` back to `HostWriter`, and remove `@InternalVtfxApi` from `HostWriter`. `config/boundaries.toml` needs no change: `vtfx-session` may name `HostConnection` but not use its members either way. Revert the examples in the sample app, the tests, `usage.md`, [architecture.md](../design/architecture.md), and [session-and-threads.md](../design/session-and-threads.md).

## 6. The app owns the session

1. The app owns the `TerminalSession`, which lives outside the composable, so the content and the modes survive the composable being recreated, as after a configuration change. `Terminal(session, modifier, style)` handles display and input (section 4 of [session-and-threads.md](../design/session-and-threads.md), section 3.5 of [architecture.md](../design/architecture.md)).
2. The IME input history does not survive: the session loses focus when the input `View` leaves the screen, which resets it (5.1 of [ime-contract.md](../design/ime-contract.md)). The display state goes away with the composable (topic 2).
3. Rotation, multi-window, and foldables are all treated as combinations of "the display size changes" and "the Activity is recreated", with no handling specific to each (section 4).
4. For an extra key bar, `TerminalSession.sendKey(KeyStroke)` and `sendText` are public (requirement 5.4, section 3.5 of [architecture.md](../design/architecture.md)).

**Why.** Requirement 5.5 asks that the session be kept across configuration changes. Requirement 5.4 leaves the extra key bar out of the library and asks for a means for the app to build it.

**Impact of reversing.** The arguments of `Terminal` and `TerminalSession`, and `sendKey` and `sendText`, are public API, whose surface and compatibility policy are in [0016](0016-public-api.md).

## 7. Resize and reflow

1. When the width changes, the main screen and the scrollback are reflowed, and the cursor moves with the character it was on; when the height changes, rows move between the screen and the scrollback so that the cursor stays visible. The alternate screen is not reflowed, only cut or padded (section 3.3 of [session-and-threads.md](../design/session-and-threads.md)).
2. Size changes are batched by time in the display (`TerminalDisplay`): the first size is applied at once, and later sizes only after the display size has stayed the same for 150 ms; drawing during the animation keeps the grid of the old size. `TerminalSession.resize` applies a size at once (topic 2; section 3.2).
3. The first `TerminalSession.resize` of a session reports `onResize` even when the size equals the one the session was created with, and later calls report only a change of the size or of the size of a cell in pixels ([0080](0080-kitty-graphics.md)). The first report is per session, not per `Terminal` (section 3.1).
4. Size changes are reported to the app with `TerminalSessionListener.onResize`, and the app notifies the host (SSH window-change, TIOCSWINSZ on a PTY) (section 3.1).

**Why.** Rotation is frequent on mobile, and without reflow the right side of long lines would be lost on every rotation. The apps on the alternate screen redraw at the new size. Batching by time makes the animations of showing the keyboard or rotating cause one reflow and one SIGWINCH (requirement 5.5), works the same for rotation and for dragging in multi-window, and is simple. Reporting the first `resize` lets the app wait for the first `onResize` before starting the host. Notifying the host depends on the connection method, so the app does it.

**Alternatives considered.**
- **Do not reflow (as xterm)**: the right side of long lines would be lost on every rotation.
- **Detect the end of the animation with the IME insets animation (`WindowInsetsAnimation`)**: it does not work for size changes other than the IME.

**Impact of reversing.** To stop reflowing, return `Emulator.resize` to truncation (`ScreenBuffer.resize` remains). The batching delay is changed with `RESIZE_DELAY_MILLIS` in `TerminalDisplay`. To report only changes again, drop the `resized` flag in `TerminalSession.resize`; apps that wait for the first `onResize` would then wait forever when the display fits the session's size.

## 8. The Compose display of a session

1. Screen updates are batched into one post to the main thread per `TerminalDisplay`, which increments Compose state (a version number) to invalidate only drawing, with no recomposition.
2. The IME's `InputConnection` is a thin adapter that delegates to `ImeEditor`, and does not use the `Editable` of `BaseInputConnection`. The adapter sends selection notifications (`updateSelection`) and `CursorAnchorInfo` (sections 7 and 12 of [ime-contract.md](../design/ime-contract.md); the `View` it is connected through is in [0082](0082-ime-editing-model.md)).
3. "Host output moved the cursor to another line", a trigger for resetting the input history, is judged by the session for each `feed`; the echo of long input that merely advanced to the next row by autowrap is not a move (5.1 of [ime-contract.md](../design/ime-contract.md)).
4. Pastes (the IME's context menu and `pasteFromClipboard`) read from the `ClipboardAccess` passed to the session, through the gate; `vtfx-compose` does not use `ClipboardManager` directly (section 4.2 of [gate-policy.md](../design/gate-policy.md), [0083](0083-gate.md)).
5. Runs of ASCII with the same style are drawn at once, and other characters per cell; characters not in the monospace font (CJK and so on) are drawn with a fallback font (section 7.3 of [emulation.md](../design/emulation.md); the details are in [0079](0079-emulation-scope.md) and [0014](0014-performance.md)). Composing text is drawn overlaid from the terminal's cursor cell, wrapping at the right edge, with an underline that shows it has not been sent (section 7 of [ime-contract.md](../design/ime-contract.md)). In reverse video (DECSCNM), the foreground color is used for the cursor (section 8.3 of [emulation.md](../design/emulation.md)).
6. The SDK for tests run with Robolectric is 36, not the compileSdk 37 (`TestSdk.kt` in the tests of `vtfx-compose`).

**Why.** In reverse video, the default cursor color becomes the same as the background. With SDK 37, Robolectric 4.17 and the Espresso event injection used by Compose's test rule fail with `NoSuchMethodException`.

