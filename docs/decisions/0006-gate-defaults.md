# Defaults of the gate's replies and policies

- Status: Approved (2026-10-13, approved by a human in the final review; on 2026-10-20 a human changed the default of OSC 52 writes to denied and approved the gate's own check of the selection); in item 7, checking the authority of `http` and `https` URLs only superseded by [0058](0058-url-policy.md), which checks `ftp`, `ws`, and `wss` too
- Date: 2026-10-12
- Related: Requirements 6.1 and 6.5 (safe defaults), 3.3 of [architecture.md](../design/architecture.md), [gate-policy.md](../design/gate-policy.md), [0039](0039-minsdk-29.md), `vtfx-gate`

## Background

Of the attack types in requirement 6.1, replies to the host, the clipboard, opening URLs, and pastes are handled by `vtfx-gate`. The default behavior of each needed to be decided.

## Decision

The resulting rules are described in [gate-policy.md](../design/gate-policy.md).

1. **Replies** are built only from constants and numbers taken from the terminal state, and no path is created by which strings sent by the host are included in them (`TerminalQuery` holds no host strings). DA1 reports a VT220 with ANSI color and DA2 reports what Windows Terminal reports. Reports of the window title and icon name (XTWINOPS 20, 21), a classic command injection path, are not answered and will not be in the future; neither are checksums of the screen contents (DECRQCRA), a means of guessing them, nor XTGETTCAP, whose replies would include terminfo strings. Section 3 of gate-policy.md.
2. **OSC 52 writes are denied by default.** Allowing them relied on the preview of the clipboard that the OS shows on each copy, through which the user would notice a rewrite, but that preview exists only from Android 13 (API 33), and [0039](0039-minsdk-29.md) set the minimum to API 29: on API 29 to 32 a host could silently replace the clipboard (pastejacking), against the safe defaults of requirement 6.5. When an app allows writes, they are limited to 64 Ki UTF-16 units, and a longer write is dropped as a whole, to keep the load from huge data in check. Section 4.1 of gate-policy.md.
3. **OSC 52 reads are denied by default**: the clipboard may contain passwords and the like copied in other apps. When allowed, the content is returned in base64, so it is never interpreted as control sequences.
4. **The gate parses the OSC 52 selection itself**, with its own list of xterm's targets, rather than trusting the list in `vtfx-core`, which is not under security review, and refuses requests whose selection does not parse. The reply to a read spells the selection again from the gate's constants, so no text from the host is part of it. Section 4.1 of gate-policy.md.
5. **Written content is not marked sensitive** (`ClipDescription.EXTRA_IS_SENSITIVE`), decided by a human. On Android 13 and later, with the mark the preview masks the content, so the user can tell that the host rewrote the clipboard but cannot see what it was rewritten to; showing the content lets the user notice the rewrite. A secret copied with OSC 52 (from a password manager on the host and so on) is then shown in the preview for a few seconds, but OSC 52 has no means of telling whether the content is secret, the terminal cannot mask only part of it, and most copies are not secret. The user's own copy goes through the same `ClipboardAccess` and is likewise not marked. Section 4.3 of gate-policy.md.
6. **URLs are opened only on an explicit action by the user**, after the UI has shown the actual URL; there is no path that opens one from host output alone. Only `http` and `https` are allowed by default. Section 5 of gate-policy.md.
7. **URLs whose displayed form may differ from where they go are not opened**: those with whitespace, control characters, format characters, surrogates, or other default-ignorable code points, and `http` and `https` URLs with userinfo, a `\`, or a character that is not ASCII in the authority. The list of default-ignorable code points is generated from the UCD files in the repository rather than taken from the runtime, so that it does not depend on the runtime's Unicode version. Refusing every character that is not ASCII in the authority, rather than only confusable ones, keeps the check independent of a confusables table and of the runtime's Unicode version; a user who needs an internationalized domain can open its punycode form. Section 5.2 of gate-policy.md.
8. **Pastes**: all control characters (C0, DEL, C1) other than HT and newlines are removed, and newlines become CR, even when not in bracketed paste mode. Since no ESC remains, the content cannot include the end of bracketed paste to escape. Section 2.1 of gate-policy.md.

The refusal of default-ignorable code points and of userinfo and `\` in item 7 was decided by a human after a security review; the list of default-ignorable code points is generated from the UCD file at a human's request. The refusal of characters that are not ASCII in the authority was decided by the agent after this decision was approved, and was approved in the security review of `vtfx-gate`.

## Alternatives considered

- **Remove only the end sequence from pastes**: Escapes and malfunctions through the C1 CSI (U+009B) or other sequences containing ESC would remain. Removing all control characters is simpler and more reliable.
- **Allow OSC 52 writes by default, with a limit**: copying with tmux's OSC 52 works without configuration, but on API 29 to 32 the user cannot notice a rewrite of the clipboard.
- **Allow writes by default only on API 33 and later**: the default of `GatePolicy`, a JVM class, would depend on the platform it runs on, and the same app would behave differently by device.
- **Mark written content sensitive**: secrets would not be shown in the preview, but the user could not see what a host rewrote the clipboard to.

## Impact of reversing

Change the defaults of `GatePolicy`, the constants of `Replies`, and the checks of `TerminalGate`, and update [gate-policy.md](../design/gate-policy.md). Update the expected values in `AttackTest` and `HostWriterTest`.
