# Move TerminalSession to a JVM module

- Status: Approved (2026-10-15, approved by a human in a review of the review queue, including the details decided during implementation. The approach was decided in a discussion with a human on 2026-10-14, and the agent decided the details)
- Date: 2026-10-14
- Related: 3.6 of [architecture.md](../design/architecture.md) (the resulting design), [0025](0025-session-scope.md) (the decision replaced), [0007](0007-compose-session.md) (owning the session), [0021](0021-thread-checks.md) (checking the calling thread), [0024](0024-thread-conventions.md) (thread conventions), requirements 3, 4.1, and 4.2

## Background

[0025](0025-session-scope.md) decided that, so that app unit tests can use the same `TerminalSession` as production, `TerminalSession` takes a `CoroutineScope` and drops `Handler`. In a later discussion, a human judged as follows.

- It is wrong for the class representing the state of the terminal session to live in vtfx-compose.
- Add to vtfx's goals being able to emulate terminal behavior from JVM programs without a UI. Only Android provides a UI, and runtimes other than the JVM, such as Kotlin Multiplatform, are out of scope. Requirements 1, 3, 4.1, and 4.2 were revised accordingly.

Dividing the contents of the current `TerminalSession` by responsibility, most of it does not depend on the UI: the terminal, the gate, the IME editing model (`ImeEditor`, a JVM type in vtfx-input), sending, and bells and titles. What depends on the UI is invalidating the display (Compose state), the scrollback position, the selection, batching resizes over 150 ms, waiting up to 200 ms to draw during synchronized output, converting colors, and the connection with the IME's `View`. All three uses of `Handler` (checking for the main thread, posting to the main thread, the two timers) are on the UI side.

It cannot be moved to vtfx-core. `TerminalSession` uses vtfx-gate and vtfx-input, but vtfx-core depends on nothing, and its having no means of sending to the host is a security boundary.

## Decision

1. **Create a new JVM module `vtfx-session` and move `TerminalSession` there.** It depends on vtfx-core, vtfx-input, and vtfx-gate. vtfx-compose depends on vtfx-session. 0025's "no separate headless type" is kept. There is one type, `TerminalSession`, used by the app's production code, its tests, and JVM programs alike.
2. **`TerminalSession` holds only the state of the terminal session.** The display state moves to vtfx-compose and is held internally by the `Terminal` composable. The division is in 3.6 of [architecture.md](../design/architecture.md).
3. **`TerminalSession` is thread-safe and does not assume a thread** (principle 1 of 0024). Any operation may be called from any thread, and it serializes internally. The app's code is called after releasing internal locks (principle 3 of 0024). 0025's `CoroutineScope` and "session thread", and 0021's main thread check, go away from `TerminalSession`.
4. **`TerminalSession` has no timers.** `resize` is applied immediately. Batching resizes and the upper bound on waiting for synchronized output are done by vtfx-compose. Therefore vtfx-session depends on neither kotlinx-coroutines nor Compose (minimizing dependencies, requirement 6.5).
5. **`TerminalSessionListener` is called on the thread of the triggering call** (principle 3, item 1 of 0024). Bells and titles on the thread that called `feed`, and resizes on the thread that called `resize`. The current promise of calling on the main thread goes away. vtfx-compose receives the notifications needed to update the display through an entry point used only between modules (`@InternalVtfxApi`), and forwards them to the main thread.
6. **App tests can be written synchronously.** There are no timers or thread assumptions, so it is enough to `feed` from JUnit and look at `snapshot`, without Robolectric or virtual time.

### Details decided during implementation (2026-10-14)

These are the points the design of the implementation left to be decided during implementation.

- **A means of reading state from Compose**: Add `onTitleChanged` and `onStickyModifiersChanged` to the listener, and have the app copy them into its own Compose state. No state holder is added to vtfx-compose. `directInput` is changed only by the app, so it is not notified. The sample app's extra key bar tracks modifiers in the state of `SampleViewModel`, and holds direct input in its own state.
- **Number of observers**: Multiple are allowed (`CopyOnWriteArrayList`). Even if two `Terminal`s display the same session at the same time, both are updated.
- **Lifetime of `scrollOffset`**: It goes away with `Terminal`, and a recreated one returns to the bottom. It is not kept. Rotation involves resizing and reflow, so even if kept, the same lines may not be visible. The first size of a recreated `Terminal` is applied immediately without waiting 150 ms.
- **Colors for color queries**: No public API is added. `Terminal` sets the colors of `TerminalStyle` through an `@InternalVtfxApi` entry point, and until then (and for sessions without a display), color queries are not answered. Previously they were answered with `TerminalColors.Dark` even before being displayed.
- **Entry point for IME operations**: vtfx-compose operates `ImeEditor` inside the lock with `TerminalSession.edit` (`@InternalVtfxApi`). Sending happens after releasing the lock, holding only the input lock. Reading the composing text for drawing also goes through the same entry point.
- **Order of value notifications** (2026-10-14, after a code review): `onResize`, `onTitleChanged`, and `onStickyModifiersChanged` report the latest value without holding a lock. If another thread changes the same value while one thread is notifying, the notifying thread reports that change as the latest value after it returns. Intermediate values are collapsed. This way, even when changed from multiple threads, the last notification is the current value. Notifying while holding a lock was not adopted, because it would hang if a listener waited for a session operation on another thread. As long as it is used from a single thread (the size and modifiers of Compose's `Terminal`), notifications arrive as before, on the thread that made the change, within that call. Queuing every value instead of keeping the latest would take the same code (the ordering without a lock is what costs, not the collapsing), but the queue would have no bound and would report stale sizes to the host; it was not adopted (2026-10-15, confirmed by a human).
- **Calls from listeners**: Listeners are called after releasing locks and finishing processing, so listeners may call the session. This is stated explicitly as an exception to principle 3, item 3 of 0024.
- **Internal division**: It was not divided into the terminal and gate part and the input part. Having the relation between the two locks (take the input lock first) visible within one class makes it easier to verify correctness.

### Relation to the option rejected by 0021

[0021](0021-thread-checks.md) rejected "make every operation thread-safe" on the grounds that the order of `ImeEditor` calls is meaningful together with IME calls, and that serializing with a lock would break the flow of edits as seen by the IME. This decision considers that concern not to apply. IME calls are separate messages arriving one at a time on the main thread, and even when confined to the main thread, extra key bar operations and resets caused by host output forwarded to the main thread could interleave between them. Taking a lock per call gives the same granularity of interleaving. Sending in the middle of a batch edit (pastes and so on) is something `ImeEditor` already handles (`TerminalSessionTest.pasteDuringABatchEditFollowsTheComposingText`).

## Alternatives considered

- **Keep 0025: leave it in vtfx-compose and pass a `CoroutineScope`**: App tests would work, but JVM programs would depend on vtfx-compose (an Android library) and Compose. The state of the terminal session and the display state would remain together in one class.
- **Move it to vtfx-core**: As in the background, it breaks the direction of dependencies and the security boundary.
- **Publish `HeadlessTerminal` separately and have `TerminalSession` (vtfx-compose) use it internally**: As in the background of 0025, app tests could not pass it to production code.
- **Limit input operations to one thread (not thread-safe)**: On Android, calling from the main thread is enough. However, when host output moves the cursor to another line, the IME's input history is reset (5.1 of ime-contract.md). In vtfx-session, which has no mechanism for forwarding to the main thread, this touches `ImeEditor` from the `feed` thread, so internal serialization is needed anyway. For JVM programs (automation that calls from a reading thread and a test thread, and so on), thread-safety is also easier to use.
- **Publish notifications as `StateFlow`**: Easy to read with Compose's `collectAsState`, but vtfx-session would depend on kotlinx-coroutines. A listener needs no dependency.
- **Give `TerminalSession` a setting for whether to notify on the main thread**: An Android concept would enter the JVM module. Apps can move threads themselves.

## Impact of reversing

- If `TerminalSession` goes back to vtfx-compose, the shape of 0025 (passing a `CoroutineScope`) is the next candidate.
- The public API changes: the package of `TerminalSession`, the threading promise of the listener, and how `title` is read. Revise the session section and app testing section of usage.md, the module structure in architecture.md, `config/boundaries.toml`, and the tables in 0007, 0021, and 0024.
