# Limits on OSC 52

- Status: Approved (2026-10-23, approved by a human in a review of the review queue)
- Date: 2026-10-22
- Related: Requirement 6.1 (resource exhaustion, unauthorized reading of the clipboard), section 4.1 of [gate-policy.md](../design/gate-policy.md), [0006](0006-gate-defaults.md), [0037](0037-api-shape-before-1.0.md), [0044](0044-boundaries-in-gradle-and-classes.md), `TerminalGate`, `GatePolicy`

## Background

When an app allows OSC 52 reads (`GatePolicy(allowClipboardRead = true)`), the gate answered every read with the whole clipboard in base64. A read is 9 bytes of host output, so a host could make the app queue replies far larger than its output: a security review on 2026-10-21 measured 139,811,300 bytes of replies to 900 bytes of output with 1 MiB on the clipboard. `HostConnection` queues writes without blocking, so the replies pile up in the app's memory, and each read also calls the system clipboard (a Binder call, and on Android 12 and later a notice that the app read it). The reply to any other query is bounded by a constant per query; only the clipboard reply was bounded by nothing the gate controlled.

## Decision

1. `TerminalGate.handle` takes the events of one write of host output together, as `TerminalSession` already collected them for each `feed`. Of the OSC 52 reads among them, only the first that the policy allows is answered with the clipboard; the others are replied to as empty, without reading the clipboard.
2. A clipboard text longer than `GatePolicy.maxClipboardReadLength` UTF-16 code units (by default 64 Ki, `DEFAULT_MAX_CLIPBOARD_READ_LENGTH`, the same as the limit on writes) is replied to as empty.
3. Both are replied to as empty, as an unreadable clipboard already is, rather than left unanswered, so that a program waiting for the reply does not wait for a timeout, and the reply stays a few bytes per read.
4. Of the OSC 52 writes among the events of one write of host output, only the last that the policy allows is performed. A host could otherwise change the system clipboard thousands of times from one write, each a Binder call and, on Android 13 and later, a preview that the system shows; the earlier writes would be replaced at once anyway. Like titles, only the latest value counts.
5. A write whose text has a control other than HT, LF, and CR (C0, DEL, C1), or a character with the Bidi_Control property (U+061C, U+200E, U+200F, U+202A to U+202E, U+2066 to U+2069), is refused as a whole. vtfx's own paste removes controls, but the user may paste into another app, where ESC or C1 could form sequences; and the preview that Android 13 and later shows, which [0006](0006-gate-defaults.md) relies on so that the user sees what the host put on the clipboard, shows bidi controls by reordering the text instead. Other format and default-ignorable characters stay allowed, since emoji sequences need U+200D and variation selectors.

Items 1 to 3 bound the replies: those to the reads of one write are at most one clipboard, of at most 256 KiB with the default limit (64 Ki code units, at most 3 bytes each in UTF-8, in base64), and a few bytes for each other read. Section 4.1 of [gate-policy.md](../design/gate-policy.md) describes the result.

## Alternatives considered

- **Only a limit on the length**: a host could still send many reads in one write, each replied to with up to the limit, so the replies would still grow far faster than the output.
- **At most one read answered with the clipboard per period of time**: bounds the rate whatever the host does, also when it sends each read in its own packet, but the gate and the session would have to read a clock. `config/boundaries.toml` allows neither `kotlin.time` nor `java.lang.System` in those modules, and relaxing it needs a human. A host that sends many small writes still gets at most one clipboard per write, each costing it a packet.
- **Remove the refused characters instead of refusing the write** (item 5): the text would then differ from what the host meant without anyone noticing; a host that copies plain text loses nothing.
- **Refuse every format and default-ignorable character, as URLs do** (item 5): would refuse common emoji.
- **Leave the other reads unanswered**: sends nothing, but programs such as vim and tmux wait for a reply until a timeout.
- **A fixed limit on the length, not in `GatePolicy`**: no new public API, but apps that allow reads cannot fit the limit to what their users copy, unlike the limit on writes.

## Impact of reversing

- Make `TerminalGate.handle` take one event again, and update `TerminalSession`, the tests, the esctest harness, and `GateFuzzer`.
- Remove `maxClipboardReadLength` and `DEFAULT_MAX_CLIPBOARD_READ_LENGTH` from `GatePolicy` and their entries in `api/`.
- Item 4: perform every write again.
- Item 5: write any text again.
- Revert section 4.1 of gate-policy.md and the tests in `AttackTest.Clipboard` and `AttackTest.ResourceExhaustion`.
