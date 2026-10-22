# The state lock of the session is fair

- Status: Approved (2026-10-22, approved by a human)
- Date: 2026-10-22
- Related: Requirement 6.2, section 2.2 of [session-and-threads.md](../design/session-and-threads.md), [0014](0014-performance.md), [0024](0024-thread-conventions.md), [0026](0026-session-module.md), `TerminalSession`

## Background

`TerminalSession` guards the terminal, the IME editing model, and its fields with one state lock. `feed` holds it while it interprets a chunk of output, and the display takes it on the main thread for each frame (`snapshot`, and `edit` for the composing text), as do keys and other input. An app's reader thread calls `feed` again as soon as the previous call returns while the host prints a large file. The state lock was an intrinsic lock, which does not hand itself to a waiting thread: the feeding thread took it again within nanoseconds of releasing it, and on the JVM most frames waited hundreds of milliseconds for it, far beyond the 25 ms of drawing in [0014](0014-performance.md), whose rendering benchmark feeds an update every 16 ms and so did not measure output without a break.

## Decision

The state lock is a fair `ReentrantLock`, as the input lock already is ([0026](0026-session-module.md)). A thread that waits for it gets it when the holder releases it, so a frame or a key waits for at most the `feed` in progress, which interprets one chunk of the app's. `feed` keeps interpreting each chunk whole under the lock, so what a feed reports (line changes for the reset of the input history, bells, titles, and requests to the gate) does not change.

## Alternatives considered

- **Split a chunk in `feed` and release the lock between the pieces**: An intrinsic lock still hands itself back to the feeding thread, so this alone does not help, and the effects that `feed` reports once per call would be split or need to be gathered across the pieces.
- **Draw from a copy of the screen that `feed` publishes after each chunk**: Frames would not wait at all, but every chunk would pay for a copy of the screen, against the throughput targets of [0014](0014-performance.md), and input would still wait for the lock.
- **Yield in `feed` between chunks (`Thread.yield`)**: Whether a waiting thread runs is up to the scheduler, so it does not bound the wait.

A fair lock costs a handoff to the waiting thread each time there is one, which happens about once per frame and per key; without a waiting thread it costs about as much as an unfair one. The throughput benchmark of [0014](0014-performance.md) measures `vtfx-core` without the session, and does not change.

## Impact of reversing

Make the state lock in `TerminalSession` an intrinsic lock again, remove `aReaderOfTheScreenWaitsForAFewFeedsAtMostWhileOutputFloods` from `TerminalSessionTest`, and remove the sentences on fairness of the state lock in section 2.2 of [session-and-threads.md](../design/session-and-threads.md).
