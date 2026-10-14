# YYYY-MM-DD Bugs reproduced in a code review

<!-- The procedure is in README.md. Replace the parts in angle brackets and delete this comment. -->

- Review target: `<full SHA>` (or `<BASE SHA>..<HEAD SHA>`)
- Reviewer: <kind of agent>
- Points of view: <which of 2.2 in README.md were emphasized>

Reproduced <N> bugs with <M> test cases. This review did not change product code.

## Status

| ID | Severity | Bug | State | Fix commit / verification |
|---|---|---|---|---|
| R1 | High | <one-sentence summary> | Open | — |

The meaning of the states and how to update them are in 3.3 of [README.md](README.md).

## Running the reproduction tests

The reproduction tests are in [repro-YYYY-MM-DD/](repro-YYYY-MM-DD/). Run them from the root of the repository.

```sh
./gradlew --console=plain --no-configuration-cache \
  -I docs/reviews/repro-YYYY-MM-DD/review.init.gradle \
  :<module>:reviewRepro
```

At the target commit, the result is **<M> tests completed, <M> failed**.
<required environment (Android SDK, whether an emulator is needed, and so on)>

## R1: <title of the bug>

- Related: <links to the relevant code>
- Contract: <the basis of the expected value: requirements, design documents, decisions, spec tests, xterm's behavior>
- Reproduction test: `<name of the test>`

Steps:

1. <step>

Expected result: <the expected value based on the contract>

Actual result: <the observed result. For an exception, its type and message>

View of the cause: <the view at the time of review>

Direction of the fix: <surrounding cases to check. Do not prescribe the implementation>

## Unverified concerns

<!-- Suspicions that could not be reproduced. If none, write "None". -->

- <suspicion>: <what was checked, and why it could not be reproduced>

## Verification results at review time

- `scripts/check.sh`: <result>
- Reproduction tests: <result>
- Verification not run: <emulator e2e and so on>
