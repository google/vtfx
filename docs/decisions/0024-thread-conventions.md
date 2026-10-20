# Thread conventions per module

- Status: Approved (2026-10-15, approved by a human in a review of the review queue. The approach was decided in a discussion with a human on 2026-10-14, and the agent decided the details)
- Date: 2026-10-14
- Related: Requirement 6.5 (documenting the contract of the public API), section 1 of [session-and-threads.md](../design/session-and-threads.md) (the resulting conventions), [0021](0021-thread-checks.md), [0026](0026-session-module.md), [0043](0043-url-open-results.md), section 8 of [ime-contract.md](../design/ime-contract.md)

## Background

Thread contracts were written individually per type, and there was no overall convention. As a result, there were the following inconsistencies and gaps.

- The KDoc of `Terminal` (vtfx-core) said "all calls from one thread", but `TerminalSession` called it from both the `feed` thread and the main thread, serialized with a lock.
- The KDoc of `ImeEditor` (vtfx-input) said "the UI thread on Android". This is how vtfx-compose uses it, not a contract of vtfx-input, which does not depend on Android.
- `HostConnection`, `ClipboardAccess`, and `UrlOpener` (vtfx-gate), which apps implement, had no rules about which thread they are called on or how exceptions are handled.
- `TerminalSession` called the replies to host queries (`HostConnection.write`) and OSC 52 (`ClipboardAccess`) on the `feed` thread while holding the session's lock. Therefore, if the app called `feed` synchronously from within `write`, it could reenter the emulator on the `feed` thread, or deadlock with reversed lock order on the main thread. Reading the clipboard is an inter-process call, so `modes()` and the like on the main thread were also kept waiting during it.

## Decision

1. **Principle 1: Modules that do not depend on Android do not assume a thread.** `vtfx-core`, `vtfx-input`, `vtfx-gate`, `vtfx-session`, `vtfx-font`, and `vtfx-font-gate` have nothing equivalent to a `Looper` and can be used from any thread and outside Android. Only the Android modules deal with the main thread (section 1.1 of [session-and-threads.md](../design/session-and-threads.md)).
2. **Principle 2: The KDoc of each public type says which thread category it is in**: immutable, thread-safe, not thread-safe (the caller serializes), or main-thread-only (section 1.2).
3. **Principle 3: Rules for calling the app's code** (section 1.3):
   1. Thread: unless stated otherwise, called synchronously on the thread of, and within, the call that triggered it.
   2. Locks: not called while holding the library's internal locks, except locks that only serialize calls, which are stated explicitly.
   3. Reentry: the app's code does not call the object that called it, and this is detected where it can be. Notifications called after releasing locks and finishing processing may explicitly allow it.
   4. Exceptions: the app's code does not throw; if it does, the exception propagates as a programming error and is not swallowed.
4. **The rules apply per module** as in section 1.4. In particular, `TerminalSession` calls `HostConnection`, `ClipboardAccess`, and `UrlOpener` after releasing the session's lock: on the thread of the operation for user operations, and on the thread that called `feed` for host requests, which are collected inside the lock and handled in order after it. `feed` and input from within `HostConnection.write` are rejected (sections 2.3 and 2.4).

## Alternatives considered

- **Call `HostConnection.write` only on the main thread**: It would align with 0021's model of "everything except `feed` and `snapshot` is main thread". However, replies to host queries would take a detour through the reading thread, the main thread, and the writing thread, and would be delayed when the main thread is clogged by drawing or GC. vim and tmux judge terminal features with reply timeouts, so protocol correctness would depend on UI responsiveness. Android cannot use the network on the main thread, so the app has to hand off from `write` to an I/O thread anyway, and the guarantee of being on the main thread does not help the app. It also goes against principle 1 (vtfx-gate would assume a `Looper`).
- **Queue sends and have whichever thread dequeue and send them in order**: Calling `feed` from within `write` would not nest and could be allowed. However, `HostWriter`, which is under review, would gain mutable state (the queue, and whether dequeuing is in progress). The user's keys might also be sent on another thread, and which thread exceptions propagate to would become hard to understand. Only loopback hosts that reply locally want to call `feed` from within `write`, and the sample app avoids it with `Handler.post`. Not taken until a situation that needs it appears.
- **The app passes an `Executor`, and `write` is called on it**: The app can do the same by writing `HostConnection { bytes -> executor.execute { ... } }`. Not a reason to add an API.
- **Catch exceptions from the app's code, log them, and continue**: It hides mistakes. Send failures (a dropped connection and so on) are handled by the app at its own layer, and what propagates to vtfx is a programming error.
- **Express the thread categories with annotations (`@ThreadSafe` and so on)**: Annotations usable in JVM modules need a new dependency (JSR 305 or Error Prone), and are not checked by lint or the like. Written in the KDoc.

## Impact of reversing

- To call `HostConnection` on a particular thread, change the handling of host requests in `TerminalSession.feed` to forward them to that thread. Also revise the KDoc of `HostConnection`, usage.md, and section 2.3 of [session-and-threads.md](../design/session-and-threads.md).
- To allow `feed` from within `write`, remove the check at the entry of `TerminalSession.feed`, and turn `HostWriter` into a send queue.
- The related tests are those in `TerminalSessionTest` for handling host requests outside the lock, and for rejecting `feed` and input from within `write` and from within an edit operation.
