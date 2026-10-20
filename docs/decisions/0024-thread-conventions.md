# Thread conventions per module

- Status: Approved (2026-10-15, approved by a human in a review of the review queue. The approach was decided in a discussion with a human on 2026-10-14, and the agent decided the details)
- Date: 2026-10-14
- Related: Requirement 6.5 (documenting the contract of the public API), [0021](0021-thread-checks.md) (checking the callers of `TerminalSession`), 2.1 of [architecture.md](../design/architecture.md), section 8 of [ime-contract.md](../design/ime-contract.md)

## Background

Thread contracts were written individually per type, and there was no overall convention. As a result, there were the following inconsistencies and gaps.

- The KDoc of `Terminal` (vtfx-core) said "all calls from one thread", but `TerminalSession` called it from both the `feed` thread and the main thread, serialized with a lock.
- The KDoc of `ImeEditor` (vtfx-input) said "the UI thread on Android". This is how vtfx-compose uses it, not a contract of vtfx-input, which does not depend on Android.
- `HostConnection`, `ClipboardAccess`, and `UrlOpener` (vtfx-gate), which apps implement, had no rules about which thread they are called on or how exceptions are handled.
- `TerminalSession` called the replies to host queries (`HostConnection.write`) and OSC 52 (`ClipboardAccess`) on the `feed` thread while holding the session's lock. Therefore, if the app called `feed` synchronously from within `write`, it could reenter the emulator on the `feed` thread, or deadlock with reversed lock order on the main thread. Reading the clipboard is an inter-process call, so `modes()` and the like on the main thread were also kept waiting during it.

The option of calling `HostConnection` only on the main thread was also considered, but not taken (see the alternatives).

## Decision

### Principle 1: Modules that do not depend on Android do not assume a thread

vtfx-core, vtfx-input, and vtfx-gate do not assume a particular thread (the main thread or the like) and have nothing equivalent to a `Looper`. They can be used from any thread, and outside Android (JVM tests, headless use). Only the Android modules, vtfx-gate-android and vtfx-compose, deal with the main thread.

### Principle 2: The KDoc of each public type says which of the following it is

| Category | Meaning |
|---|---|
| Immutable | Usable from any thread. |
| Thread-safe | May be called from multiple threads at the same time. |
| Not thread-safe | Do not call concurrently. Which thread it is called from does not matter. The caller serializes (with a lock or anything else that creates a happens-before relation between calls). |
| Main-thread-only | Call only on the main thread. Nothing checks it at run time (2026-10-20, correction: this said that a call from another thread fails a run-time check, but [0021](0021-thread-checks.md), which added the checks, was withdrawn on 2026-10-14 and the checks were removed). |

### Principle 3: Rules for calling the app's code

Rules common to calls of callbacks, listeners, and interfaces the app implements (`HostConnection` and so on).

1. **Thread**: Unless stated otherwise, called synchronously on the same thread as, and within, the call that triggered it. Anything called on another thread says so explicitly.
2. **Locks**: The app's code is not called while holding the library's internal locks. Only locks for serializing calls are an exception, and holding them is stated explicitly (`HostWriter`, the input lock of `TerminalSession`).
3. **Reentry**: The app's code does not call the object that called it. Where it can be detected, it is detected with `check` and becomes `IllegalStateException`. However, notifications called after releasing locks and finishing processing can explicitly allow calling the object that called them (`TerminalSessionListener`; [0026](0026-session-module.md)).
4. **Exceptions**: The app's code does not throw. If it does, the exception is propagated to the caller as a programming error, not swallowed. The state of the object after it propagates is not guaranteed.
   - 2026-10-19: [0043](0043-url-open-results.md) applies this to failures that the platform documents for what the app's code does, such as no app to open a URL: they are not exceptions in this sense. The interface returns them as values (`UrlOpener.open` returns a result), and the implementation converts only the exceptions it expects for them (`ActivityNotFoundException` and `SecurityException` from `startActivity` in `AndroidUrlOpener`) and lets any other propagate. vtfx catches nothing around the call.

### Application per module

| Module | Type | Category and conventions |
|---|---|---|
| vtfx-core | `Terminal` | Not thread-safe. `TerminalEventListener` is called from the same thread within `write`, and must not call `Terminal` (detected). |
| vtfx-core | Values such as `TerminalSnapshot`, `KeyStroke`, `Hyperlink`, `LinkPattern`, `TerminalLimits` | Immutable. |
| vtfx-input | `ImeEditor` | Not thread-safe. `Listener` is called from the same thread within the editing call. Confining it to Android's UI thread is how vtfx-compose uses it. |
| vtfx-gate | `HostWriter` | Thread-safe. Calls `HostConnection.write` holding a lock for serialization. |
| vtfx-gate | `TerminalGate`, `GatePolicy` | `TerminalGate` has no state of its own and is thread-safe if its dependencies are. `GatePolicy` is immutable. |
| vtfx-gate | `HostConnection` | Implemented by the app. Called from any thread, one call at a time in order (never concurrently or nested). Must not block (queue and send on another thread). Must not call vtfx from within. |
| vtfx-gate | `ClipboardAccess`, `UrlOpener` | Implemented by the app. May be called from any thread, so implement them thread-safely. |
| vtfx-gate-android | `AndroidClipboard`, `AndroidUrlOpener` | Thread-safe. |
| vtfx-session | `TerminalSession` | Thread-safe ([0026](0026-session-module.md)). One lock guards the state, and user input is serialized up to sending with another lock (a lock for serialization). |
| vtfx-session | `TerminalSessionListener` | Called on the thread of the triggering call, without holding the session's lock. May call the session. Notifications of the size, title, and modifiers report the latest value, so even when multiple threads change them, the last notification is the current value ([0026](0026-session-module.md)). |
| vtfx-compose | `TerminalDisplay` inside `Terminal` | Confined to the main thread. Forwards notifications from the session to the main thread. |

`TerminalSession` calls the app's `HostConnection`, `ClipboardAccess`, and `UrlOpener` on the thread that called the operation for user operations (until 2026-10-14, limited to the main thread), and on the thread that called `feed` for host requests (replies to queries and OSC 52). Both are called after releasing the session's lock (principle 3, item 2). Host requests that arise within `feed` are collected inside the lock and handled in the order they arose after releasing the lock. The values of queries (the cursor position and so on) are fixed by the state at the time the requests were collected, so delaying the handling does not change the replies.

Calling `feed` from within `HostConnection.write` is forbidden as principle 3, item 3, and detected at the entry of `feed`. It fails as soon as `feed` is called, not only when a reply arises, so it is found deterministically regardless of host output. Triggering another send (`sendKey` and so on) from within `HostConnection.write` is also detected, at the entry of the session's input operation, before the IME editing model changes: the input lock is reentrant, so the check in `HostWriter` alone would come after the model had taken the input for sent. Likewise, an operation that `TerminalSession.edit` runs, which holds the session's lock, is detected when it sends input or calls `feed`.

## Alternatives considered

- **Call `HostConnection.write` only on the main thread**: It aligns with 0021's model of "everything except `feed` and `snapshot` is main thread". However, replies to host queries would take a detour through the reading thread, the main thread, and the writing thread, and would be delayed when the main thread is clogged by drawing or GC. vim and tmux judge terminal features with reply timeouts, so protocol correctness would depend on UI responsiveness. Android cannot use the network on the main thread, so the app has to hand off from `write` to an I/O thread anyway, and the guarantee of being on the main thread does not help the app. It also goes against principle 1 (vtfx-gate would assume a `Looper`).
- **Queue sends and have whichever thread dequeue and send them in order**: Calling `feed` from within `write` would not nest and could be allowed. However, `HostWriter`, which is under review, would gain mutable state (the queue, and whether dequeuing is in progress). The user's keys might also be sent on another thread, and which thread exceptions propagate to would become hard to understand. Only loopback hosts that reply locally want to call `feed` from within `write`, and the sample app avoids it with `Handler.post`. Not taken until a situation that needs it appears.
- **The app passes an `Executor`, and `write` is called on it**: The app can do the same by writing `HostConnection { bytes -> executor.execute { ... } }`. Not a reason to add an API.
- **Catch exceptions from the app's code, log them, and continue**: It hides mistakes. Send failures (a dropped connection and so on) are handled by the app at its own layer, and what propagates to vtfx is a programming error.
- **Express the thread categories with annotations (`@ThreadSafe` and so on)**: Annotations usable in JVM modules need a new dependency (JSR 305 or Error Prone), and are not checked by lint or the like. Written in the KDoc.

## Impact of reversing

- To call `HostConnection` on a particular thread, change the handling of host requests in `TerminalSession.feed` to forward them to that thread. Also revise the KDoc of `HostConnection` and usage.md.
- To allow `feed` from within `write`, remove the check at the entry of `TerminalSession.feed`, and turn `HostWriter` into a send queue.
- The related tests are those in `TerminalSessionTest` for handling host requests outside the lock, and for rejecting `feed` and input from within `write` and from within an edit operation.
