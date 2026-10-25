# Security review is focused, not limited

- Status: Approved (2026-10-24, approved by a human)
- Date: 2026-10-24
- Related: Requirements 2 and 6.1, sections 1 and 2 of [architecture.md](../design/architecture.md), section 2 of [fonts.md](../design/fonts.md), [0041](0041-security-review-by-module.md), [0088](0088-build-boundaries-and-tests.md), [0075](0075-adversaries-are-inputs.md), `config/protected-paths.toml`

## Background

Requirements 2 and 6.1 said that most of the code does not need security review, and the module tables of architecture.md and fonts.md marked every module outside `config/protected-paths.toml` "Not needed". The README said that security review could focus on `vtfx-gate` and `vtfx-gate-android`.

[0075](0075-adversaries-are-inputs.md) made changes to the code mistakes rather than adversaries, and left several properties of requirement 6.1 to the contracts of code outside review: whether text is what the user typed, whether the user asked for a copy, a paste, or a URL, and what the IME and accessibility services see. A mistake in that code can break those properties, and resource limits and what the screen shows live in `vtfx-core` and `vtfx-compose` themselves. Saying that such code needs no security review is not true, and invites skipping the security side of its review.

## Decision

1. **No code of vtfx is exempt from review, for security as for anything else.**
2. **"Under security review" keeps its meaning**: the paths under `security_review` in `config/protected-paths.toml`, which are the gate modules, which hold the capabilities, and the build configuration, which decides what the checks cover. A human reviews them again, closely, after the review of each change, and [review-queue.md](../review-queue.md) tracks it. The list and the term do not change.
3. **The design keeps the effect of a mistake outside those paths small**, rather than claiming that the code does not matter:
   - the boundary check keeps the capabilities out of that code, also against changes written to get around it ([0088](0088-build-boundaries-and-tests.md));
   - each gate checks what it receives rather than trust it: replies to the host are built from constants and numbers, every clipboard and URL request goes through the gate's policy, and only fonts that a separate verifier accepts in the canonical form reach native code. A mistake in `vtfx-core` can show the wrong thing but cannot send the host's strings back to it or use the clipboard against the policy, and a mistake in `vtfx-font` can make glyphs look wrong or reject a font but cannot let unverified bytes reach native code;
   - the properties that rest on callers' contracts are guarded by checks in the gate, types, KDoc, and tests ([0075](0075-adversaries-are-inputs.md)); resource limits and what is shown are kept by tests and fuzzing.
4. **The module tables say whether a module is under security review ("Yes" or "No")**, not whether review is needed.

## Alternatives considered

- **Put every module under security review**: every change would wait for the closer review, and the review would be spread thin; [0075](0075-adversaries-are-inputs.md) rejected reviewing `vtfx-session` and `vtfx-compose` whole for the same reason.
- **Keep the wording**: it says something untrue since [0075](0075-adversaries-are-inputs.md).
- **Rename `security_review` and the term** (for example "focused review"): the key, `scripts/report_protected_changes.py`, its tests, and dozens of references would change, with no change in what is reviewed.

## Impact of reversing

Restore the wording of requirements 2 and 6.1, "Needed" and "Not needed" in the tables of architecture.md and fonts.md, and the README's first item of the security boundary.
