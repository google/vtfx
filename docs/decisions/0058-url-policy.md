# The URL schemes of GatePolicy

- Status: Provisional
- Date: 2026-10-22
- Related: Requirement 6.1 (opening unintended URLs), sections 5.2 and 6 of [gate-policy.md](../design/gate-policy.md), [0006](0006-gate-defaults.md), [0043](0043-url-open-results.md), `GatePolicy`, `AndroidUrlOpener`

## Background

`GatePolicy.urlSchemes` accepted any scheme. An app that added `file` let the user open `file:` links that the host wrote, and `AndroidUrlOpener` passed them to `startActivity`. In apps that target API 24 or later, Android throws `FileUriExposedException` for an intent whose data is a `file:` URI leaving the app, and `AndroidUrlOpener` converts only `ActivityNotFoundException` and `SecurityException` into results ([0043](0043-url-open-results.md)), so the exception crashed the app after the user confirmed the link (found in a security review on 2026-10-21). Whether it is thrown depends on the scheme, which the host chooses, not on which apps are installed.

## Decision

1. `GatePolicy` refuses `file` in `urlSchemes`, in any case, with `IllegalArgumentException`. Section 5.2 of [gate-policy.md](../design/gate-policy.md) says so.
2. `AndroidUrlOpener` is unchanged: the gate decides which URLs are opened, and vtfx-gate-android only performs what the gate allowed.
3. `content` stays allowed if an app adds it: starting an activity for a `content:` URL does not throw by default, and the app that takes it decides whether it can read it.
4. `GatePolicy` is immutable, as its documentation says, also to Java: `urlSchemes` and `GatePolicy.DEFAULT_URL_SCHEMES` are unmodifiable sets (`Collections.unmodifiableSet`). Before, both returned a `LinkedHashSet` that Java code could add to (and Kotlin code by a cast), which changed a policy after it was built, or the default of every later policy in the process (found in the same review).
5. `GatePolicy` refuses a scheme outside the grammar that `isAllowedUrl` accepts (an ASCII letter followed by ASCII letters, digits, `+`, `-`, or `.`), with `IllegalArgumentException`. Such a scheme, like `https:` or ` https`, matched no URL, so a mistake in the configuration made every link silently `NotAllowed`; only the empty scheme was refused before.

## Alternatives considered

- **Convert `FileUriExposedException` into `Refused` in `AndroidUrlOpener`**: no change to `GatePolicy`, but every `file:` link would be offered to the user and then fail, and the opener would take a decision that belongs to the gate. The failure is known when the policy is built, so failing then is earlier.
- **Refuse `content` too**: such links rarely work without a grant of access, but they do not crash the app, and refusing them would take away a scheme that some apps may use.
- **Leave it to the app**: the crash comes from host output, after an action of the user, in a configuration that looks harmless.
- **Return a new copy on each read**: also safe, but a copy per read for no gain over an unmodifiable set.

## Impact of reversing

- Remove the check from `GatePolicy`, its test in `AttackTest.Hyperlinks`, and the sentence in section 5.2 of gate-policy.md.
- Decide again what `AndroidUrlOpener` does with `FileUriExposedException`; catching nothing brings back the crash.
- Item 4: store the sets without the unmodifiable wrapper; nothing else depends on it.
- Item 5: refuse only the empty scheme again.
