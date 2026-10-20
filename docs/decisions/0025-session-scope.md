# TerminalSession usable in app tests, and CoroutineScope

- Status: Superseded by [0026](0026-session-module.md)
- Date: 2026-10-14
- Related: [0007](0007-compose-session.md), [0021](0021-thread-checks.md), [0024](0024-thread-conventions.md), requirements 4.2 and 6.5

## Summary

The main use of a terminal without a UI is unit tests of terminal apps, and what those tests check is the app's production code, which receives a `TerminalSession`. A separate headless type could not be passed to that code, so this decision chose to make the same `TerminalSession` work in JUnit without Robolectric. `TerminalSession`, then in `vtfx-compose`, used `Handler` and `Looper` for the main-thread check, for posting to the main thread, and for two timers, and `Looper.getMainLooper()` failed in the constructor under AGP's mock `android.jar`.

It decided: (1) no separate headless type or module; (2) `TerminalSession` takes a required `CoroutineScope`, whose dispatcher sets the session's thread and whose `Job` its lifetime, replacing `Handler`; (3) tests do not need `Dispatchers.setMain()`; (4) the session's thread is the thread that constructed it, and is checked; (5) the requirements cover Android and JVM tests only; (6) a JUnit test without Robolectric guards against regressions.

[0026](0026-session-module.md) kept item 1 and the motivation of using the same type as production in app tests, and reversed items 2 to 6: `TerminalSession` moved to the JVM module `vtfx-session`, is thread-safe, and has no timers and no scope. The current design is in section 2 of [session-and-threads.md](../design/session-and-threads.md).
