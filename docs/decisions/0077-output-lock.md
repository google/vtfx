# An output lock serializes the feeds of the session

- Status: Approved (2026-10-25, approved by a human)
- Date: 2026-10-24
- Related: Requirement 6.1, sections 1.3 and 2.2 of [session-and-threads.md](../design/session-and-threads.md), item 3 of [0026](0026-session-module.md) (the locks replaced), [0024](0024-thread-conventions.md), [0065](0065-fair-state-lock.md), R5 of [reviews/2026-10-22-gate.md](../reviews/2026-10-22-gate.md), `TerminalSession.feed`

## Background

[0026](0026-session-module.md) gave `TerminalSession` two locks: one for the state, and a fair one that serializes user input up to sending. `feed` released the state lock, told the observers and the listener, and only then handed the replies and OSC 52 requests of the output to the gate, with no lock around that step. A listener may call the session, so input sent from `onBell` reached the host before the replies of the output that rang the bell; and of two feeds on different threads, the second could reach the gate first, sending its replies first or letting an older clipboard write overwrite a newer one (R5 of the review).

## Decision

1. **`TerminalSession` has a third lock, the output lock**, which guards no state and serializes `feed` from the interpretation of the output to the end of the gate's handling of its requests. It is taken before the state lock, and is fair, like the input lock.
2. **It is held while the gate calls `HostConnection.write`, `ClipboardAccess`, and `ReportedColors`**, as the input lock is held for input: an exception for locks that only serialize calls (rule 2 of section 1.3 of session-and-threads.md). It is not held while the session tells the observers or the listener, so listeners may still call the session.
3. **Calls into the session from the code that the gate calls fail.** `feed` and the input operations throw `IllegalStateException` on a thread that holds the output lock, before changing any state (rule 3 of section 1.3), since the lock is reentrant and such a call would otherwise nest and send its bytes before the rest of the outer feed's.

Section 2.2 of [session-and-threads.md](../design/session-and-threads.md) describes the result.

## Alternatives considered

- **Hand the requests to the gate under the state lock**: The app's code (`HostConnection`, `ClipboardAccess`) would run under the lock that guards the state, against [0024](0024-thread-conventions.md), and a frame or a key on the main thread would wait for the network.
- **Queue the requests and send them from one thread of the session**: The session would own a thread, which [0026](0026-session-module.md) avoids, and the replies would no longer be sent within the `feed` that caused them.
- **Take the input lock for feeds too**: Input would wait for the interpretation of output, and output for a slow `HostConnection.write` of input, with no gain in order: replies and input are only ordered by when each is produced.

## Impact of reversing

Remove the output lock and its reentry checks from `TerminalSession`, the tests of the order of replies, clipboard writes, and notifications in `TerminalSessionTest` (among them `repliesOfAFeedAreSentBeforeTheListenerFeedsMore` and `feedingFromTheClipboardThatTheGateCallsFails`), and the output lock from section 2.2 of session-and-threads.md and from section 3.6 of [architecture.md](../design/architecture.md). The replies of separate feeds could then be reordered again.
