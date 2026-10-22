# URL schemes that GatePolicy refuses

- Status: Provisional
- Date: 2026-10-22
- Related: Requirement 6.1 (opening unintended URLs), sections 5.2 and 6 of [gate-policy.md](../design/gate-policy.md), [0006](0006-gate-defaults.md), [0043](0043-url-open-results.md), `GatePolicy`, `AndroidUrlOpener`

## Background

`GatePolicy.urlSchemes` accepted any scheme. An app that added `file` let the user open `file:` links that the host wrote, and `AndroidUrlOpener` passed them to `startActivity`. In apps that target API 24 or later, Android throws `FileUriExposedException` for an intent whose data is a `file:` URI leaving the app, and `AndroidUrlOpener` converts only `ActivityNotFoundException` and `SecurityException` into results ([0043](0043-url-open-results.md)), so the exception crashed the app after the user confirmed the link (found in a security review on 2026-10-21). Whether it is thrown depends on the scheme, which the host chooses, not on which apps are installed.

## Decision

1. `GatePolicy` refuses `file` in `urlSchemes`, in any case, with `IllegalArgumentException`. Section 5.2 of [gate-policy.md](../design/gate-policy.md) says so.
2. `AndroidUrlOpener` is unchanged: the gate decides which URLs are opened, and vtfx-gate-android only performs what the gate allowed.
3. `content` stays allowed if an app adds it: starting an activity for a `content:` URL does not throw by default, and the app that takes it decides whether it can read it.

## Alternatives considered

- **Convert `FileUriExposedException` into `Refused` in `AndroidUrlOpener`**: no change to `GatePolicy`, but every `file:` link would be offered to the user and then fail, and the opener would take a decision that belongs to the gate. The failure is known when the policy is built, so failing then is earlier.
- **Refuse `content` too**: such links rarely work without a grant of access, but they do not crash the app, and refusing them would take away a scheme that some apps may use.
- **Leave it to the app**: the crash comes from host output, after an action of the user, in a configuration that looks harmless.

## Impact of reversing

- Remove the check from `GatePolicy`, its test in `AttackTest.Hyperlinks`, and the sentence in section 5.2 of gate-policy.md.
- Decide again what `AndroidUrlOpener` does with `FileUriExposedException`; catching nothing brings back the crash.
