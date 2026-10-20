# Checking the calling thread

- Status: Superseded by [0026](0026-session-module.md)
- Date: 2026-10-13
- Related: Requirement 6.5 (documenting the contract of the public API), [0007](0007-compose-session.md), [0024](0024-thread-conventions.md)

## Summary

When `TerminalSession` was in `vtfx-compose`, its contract was that `feed` and `snapshot` may be called from any thread and every other operation only from the main thread, because the IME editing model and the Compose display state were confined to the main thread. This decision made the main-thread-only operations check the `Looper` at their entry and throw `IllegalStateException` on another thread, and annotated them with `@MainThread` and `@AnyThread` for lint. lint did not report wrong calls from the app's modules, so the run-time check was the main protection for apps.

It rejected making every operation thread-safe, on the grounds that serializing `ImeEditor` calls with a lock would break the flow of edits as the IME sees it. [0026](0026-session-module.md) moved `TerminalSession` to `vtfx-session` and made it thread-safe, answering that concern, and the checks and annotations were removed from it. The current thread conventions are in section 1 of [session-and-threads.md](../design/session-and-threads.md).
