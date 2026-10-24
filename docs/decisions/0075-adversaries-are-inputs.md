# The adversaries are the inputs, and changes to the code are mistakes

- Status: Approved (2026-10-24, approved by a human)
- Date: 2026-10-24
- Related: Requirement 6.1, sections 1 and 5 of [architecture.md](../design/architecture.md), sections 2 and 4.2 of [gate-policy.md](../design/gate-policy.md), section 7 of [boundaries.md](../design/boundaries.md), [0042](0042-user-clipboard-through-gate.md), [0044](0044-boundaries-in-gradle-and-classes.md)

## Background

Requirement 6.1 names three untrusted inputs: the host's output, pasted content, and the IME. From phase 4, it also asks that the check of the module boundaries hold against changes written to get around it, and [0044](0044-boundaries-in-gradle-and-classes.md) made it so for what the check covers: which modules hold the references that perform effects (the host connection, the clipboard, opening other apps, fonts from files, reflection, processes, the network).

Read more widely, the same words make the code itself an adversary. Several methods of the gate believe what the caller claims, and a change to the caller can break a property of requirement 6.1 without referring to anything that the check restricts:

- `HostWriter.sendText` believes that the text is what the user typed, on one line; whether committed text is a paste is decided in `ImeEditor`.
- `TerminalGate.copyToClipboard`, `readClipboardForPaste`, and `openUrl` believe that the user asked for them.
- What the IME sees is decided by the `InputConnection` adapter in `vtfx-compose`, which holds the whole session.

These are properties of how values match the world outside the program (whether bytes are what the user typed, whether the user chose a menu item), which no value proves. Making them hold against a change written to break them takes proofs of the user's actions made only by reviewed code from the platform's callbacks, reviewed menus that replace the platform's, module splits so that the code that holds the screen reaches neither the IME's editing model nor the writer, rules that keep each module from the methods it does not need, a public API that asks apps for those proofs, and the review of `vtfx-session`, through which everything passes. Even then, what the user saw, which link a tap was on, and what code does with a proof it kept for later stay with review. The cost grows faster than the guarantee, and most of it guards the code against itself.

## Decision

1. **The adversaries are the untrusted inputs** of requirement 6.1: the host's output, pasted content, and the IME. The code of vtfx is not an adversary: a change to it that breaks a property is a mistake, to be caught by the gate's checks, by types, by tests, and by review and audit afterward.
2. **The check of the module boundaries keeps holding against changes written to get around it**, for what it covers: where the capabilities are ([0044](0044-boundaries-in-gradle-and-classes.md), unchanged). This is cheap, and decides what code outside review can do at all.
3. **How code outside review calls the gate is guarded against mistakes, not against changes written to get around it.** For each method that takes a caller's claim:
   - the gate checks what it can from the values, also when the caller already does, and fails fast on what must not happen, rather than repair it;
   - types narrow what a caller can pass or see, where that costs little;
   - the KDoc states the caller's contract, and tests pin both sides.
4. **No measure that needs a trusted path or a proof of the user's action is taken** for these properties: no tokens of user activation, no reviewed replacement of the platform's menus, no split of modules only to keep reviewed code from unreviewed code. Such a measure is reconsidered only for a property where a mistake is likely and its effect severe, by a new decision.

The first measures under item 3:

- `HostWriter.sendText` refuses a line break, and `ImeEditor` sends Enter where a line break is committed rather than when it sends text, so that a mistake in deciding what is a paste fails instead of running lines one by one despite bracketed paste mode.
- The user's copy drops the characters that an OSC 52 write may not carry.
- The text on the clipboard that the user pastes stays in the gate, as a `ClipboardText` that only `vtfx-gate` reads, and goes to `HostWriter.sendPaste` as one paste.
- The keys of scroll gestures are `TerminalKey`s, which carry no text.

## Alternatives considered

- **Keep requirement 6.1 as it reads, and bring every module into tiers of trust**, reviewing the shape of the authority that reviewed code hands to the rest. It needs everything listed in the background, changes the public API, and still leaves the properties of how values match the world with review.
- **Review the modules that call the gate whole** (`vtfx-session`, `vtfx-compose`). The scope of review grows several times, with code that changes often, and the guarantee is that of review.
- **Drop the check against changes written to get around it altogether**, back to the guardrails of phases 1 to 3. The check of where the capabilities are already works and costs little; dropping it would give up the one guarantee that holds by construction.

## Impact of reversing

Requirement 6.1 would again read as making the code an adversary for every property, and the properties held by callers' contracts would need the measures of item 4. The measures under item 3 would stay useful: each checks in the gate or narrows by a type what a caller claimed.
