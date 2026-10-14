# Checking the calling thread

- Status: Withdrawn (2026-10-14, replaced by [0026](0026-session-module.md) in a discussion with a human. `TerminalSession` was made thread-safe, so the operations limited to the main thread, and their checks, are gone. The answer to the reason for rejecting the alternative "make every operation thread-safe" is in 0026)
- Original status: Provisional (2026-10-13, introduced at a human's request. The agent decided the details of the scope of the check)
- Date: 2026-10-13
- Related: Requirement 6.5 (documenting the contract of the public API), threads in [0007](0007-compose-session.md), section 8 of [ime-contract.md](../design/ime-contract.md), `TerminalSession` in `vtfx-compose`

## Background

The contract of `TerminalSession` is that `feed` is called from any thread and everything else from the main thread ([0007](0007-compose-session.md)). The IME editing model (`ImeEditor`) and the display state (Compose state, work scheduled with a `Handler`) are confined to the main thread and are not thread-safe.

This contract was only written in the KDoc and usage.md, and was checked neither statically nor at run time. If the app called `sendKey` and the like from another thread (such as an SSH library's receive thread), it would contend with IME calls over the editing model, causing a data race. Writes to Compose state do not throw even from another thread, so the state would silently break without crashing.

## Decision

- **At run time**: Public members of `TerminalSession` limited to the main thread check the `Looper` at the entry and throw `IllegalStateException` if not on the main thread. It is a programming error, so it is neither silently ignored nor forwarded to the main thread. The targets are `title`, `stickyModifiers`, `directInput` (both read and write), `hyperlinkAt`, `openUrl`, `sendKey`, `sendText`, `paste`, and `pasteFromClipboard`.
- **Allowed on any thread**: The constructor, `feed`, and `snapshot`. usage.md already said `snapshot` could be called from any thread, but this disagreed with the class's KDoc, so the KDoc was aligned. The constructor does not touch thread-confined state, and the instrumented test on a device (`RenderConsistencyTest`) also creates it on the instrumentation thread, so it may be called from any thread.
- **Statically**: Mark `TerminalSession` `@MainThread`, and mark the constructor, `feed`, `snapshot`, and the private functions that run on the `feed` thread `@AnyThread`. Mark `TerminalSessionListener` `@MainThread`, showing implementers that it is called on the main thread. lint's `ThreadConstraint` detects, inside `vtfx-compose`, mistakes that directly call main-thread-only functions from `feed`. At the introduction, it was made to distinguish lambdas passed to `runOnMain` (a `@MainThread` parameter) from paths that check the `Looper` and then call on the spot (which lint cannot read, so they were suppressed with `@SuppressLint`).
- **Internal declarations are not checked**: `internal` members are called only by `Terminal` and the IME adapter, from the main thread. `ImeEditor` in `vtfx-input` is in a JVM module that does not depend on Android, so it cannot be checked with `Looper`. Both are protected by the checks at the entry points of `TerminalSession`.

### Limits of the static check

With this repository's Gradle setup, lint did not detect wrong calls from the app's modules. Temporary code calling `sendKey` from a `@WorkerThread` function was put in the sample app and `:vtfx-sample:lintDebug` was run, but it was not reported. The same code inside `vtfx-compose` was reported, and calling a `@MainThread` function inside the sample app in the same way was reported. The annotations remain in the class files. The presumed reason is that lint does not read the annotations of project dependencies, but the cause has not been confirmed. Whether the app's lint detects it against an AAR fetched from Maven has not been confirmed either. Therefore the main protection for apps is the run-time check, and the annotations serve as documentation that shows the contract in the IDE.

## Alternatives considered

- **If not on the main thread, forward to the main thread and run there**: The call would become asynchronous and could not be used for things that return a result, such as `openUrl`. The order of calls would also become invisible to the app. It would also hide mistakes.
- **Make every operation thread-safe (protect it with a lock)**: The order of `ImeEditor` calls is meaningful together with IME calls ([ime-contract.md](../design/ime-contract.md)). Even serializing with a lock would break the flow of edits as seen by the IME.
- **Annotations only**: As in the limits above, mistakes by apps cannot be detected.
- **Do not check reads such as `title`**: Reading Compose state does not break even from another thread, but reads are also checked to keep the simple rule "everything except `feed` and `snapshot` is main thread".

## Impact of reversing

Remove the calls to `checkMainThread()` in `TerminalSession`, or change the targets. Revise `mainThreadOperationsFailOnAnotherThread` and `anyThreadOperationsWorkOnAnotherThread` in `TerminalSessionTest`, section 3 of usage.md, and this decision. To remove the annotations, also remove `@SuppressLint("ThreadConstraint")`.
