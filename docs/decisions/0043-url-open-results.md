# What opening a URL returns

- Status: Approved (2026-10-20, approved by a human in a review of the review queue)
- Date: 2026-10-19
- Related: Requirement 6.1, [gate-policy.md](../design/gate-policy.md), principle 3 of [0024](0024-thread-conventions.md), [0006](0006-gate-defaults.md), [0016](0016-public-api.md), [0037](0037-api-shape-before-1.0.md), [0042](0042-user-clipboard-through-gate.md), the item "Links taken by a protected activity" in [review-queue.md](../review-queue.md)

## Background

`UrlOpener.open` returned nothing, and `TerminalGate.openUrl` and `TerminalSession.openUrl` returned `true` whenever the gate allowed the URL. `AndroidUrlOpener` caught `ActivityNotFoundException` and ignored it, so when no app could open a link the user had confirmed, nothing happened and the app was told that it had been opened; the sample app's message for a link that was not opened never appeared. `AndroidUrlOpener` did not catch `SecurityException`, which `startActivity` throws when the activity that takes the intent requires a permission that the app does not hold (found in a security review on 2026-10-14). `UrlOpener` said that implementations must not throw, and principle 3 of [0024](0024-thread-conventions.md) treats an exception from the app's code as a programming error that propagates, so such a link would have crashed the app after the user confirmed it.

Both failures are documented outcomes of `startActivity`, not programming errors: they depend on which apps are installed, which the app cannot know beforehand. Principle 3 did not say how an interface that the app implements reports them.

## Decision

The results and how `AndroidUrlOpener` produces them are described in sections 5.3 and 6 of [gate-policy.md](../design/gate-policy.md).

1. `UrlOpener.open` returns a `UrlOpenerResult`: `OpenUrlResult.Opened` (an app was started), `OpenUrlResult.NoApp` (no app takes the URL), or `OpenUrlResult.Refused` (the system refused to start the app that takes it, for example because that app requires a permission).
2. `TerminalGate.openUrl` and `TerminalSession.openUrl` return an `OpenUrlResult`: what the `UrlOpener` returned, unchanged, or `OpenUrlResult.NotAllowed` when the gate did not call it, because the policy does not allow the URL or there is no `UrlOpener` (the cases that returned `false` before). `UrlOpenerResult` is a subtype of `OpenUrlResult`, so an opener cannot return `NotAllowed` and the gate needs no check to pass the result on. `canOpenUrl` stays a `Boolean`: whether `openUrl` would get past `NotAllowed`.
3. `OpenUrlResult` is closed ([0037](0037-api-shape-before-1.0.md)): a new kind of result is added only in a major version, and apps match it exhaustively, without `else`, to tell the user why a confirmed link did not open. The kinds follow what Android documents for starting an activity, and a failure that an opener cannot name is a programming error, not a new kind of result. [0016](0016-public-api.md) lists it with the other closed types.
4. The results are `data object`s, for a readable `toString`. They have no constructor, `copy`, or `componentN`, which are what [0037](0037-api-shape-before-1.0.md) keeps data classes out of the public API for.
5. How principle 3, item 4 of [0024](0024-thread-conventions.md) (the app's code does not throw) applies to failures that the platform documents: the interface the app implements returns them as values, and the implementation converts only the exceptions it expects for them and lets any other exception propagate. `AndroidUrlOpener` converts `ActivityNotFoundException` into `NoApp` and `SecurityException` into `Refused`, both from `startActivity`. vtfx does not catch anything around the call.

## Alternatives considered

- **Return a `Boolean`**: the smallest change, but the app could not tell the user why a link did not open, and a success flag is what the house rules replace with a type.
- **Catch every exception in `AndroidUrlOpener`, or in the gate, and report a failure**: no crash from any cause, but it swallows programming errors, against principle 3 of [0024](0024-thread-conventions.md).
- **One type for the opener and the gate**: simpler, but an opener could return `NotAllowed`, which the gate would have to check or pass on.
- **An open shape, on which apps must write `else` ([0037](0037-api-shape-before-1.0.md))**: new kinds could come in a minor version, but every app would need a generic message, and the kinds are bounded by what the platform documents.
- **A separate result for a session without a `UrlOpener`**: the app decides whether to pass one, so it already knows; `canOpenUrl` does not separate the two either.

## Impact of reversing

- Make `UrlOpener.open` return nothing and `openUrl` return a `Boolean` again, and remove `OpenUrlResult` and `UrlOpenerResult`, with their entries in `api/`.
- Decide again what `AndroidUrlOpener` does with `SecurityException`; catching nothing brings back the crash.
- Revert the sample app's messages, [usage.md](../usage.md), the tests in vtfx-gate, vtfx-gate-android, and vtfx-session, and the notes in [0016](0016-public-api.md) and [0024](0024-thread-conventions.md).
