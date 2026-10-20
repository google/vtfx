# Encoding keys in the gate

- Status: Approved (2026-10-13, approved by a human in the final review)
- Date: 2026-10-12 (revised 2026-10-13)
- Related: 3.1–3.3 of [architecture.md](../design/architecture.md), section 2 of [gate-policy.md](../design/gate-policy.md), `config/boundaries.toml`, `HostWriter.sendKey`

## Background

The gate is the only module that can create bytes to send to the host (3.3 of architecture.md). For text, pastes, replies to queries, and mouse reports, the gate receives structured values and turns them into bytes inside the gate.

Only keys were different: `KeyEncoder` in `vtfx-input` created the bytes, and `HostWriter.sendKey` received them and only checked **whether they had the shape of a key** (a C0 control character, one printable character, CSI, SS3, an ESC prefix). This was because `KeyStroke` was in `vtfx-input`, and in `config/boundaries.toml`, `vtfx-gate` can depend only on `vtfx-core`.

This shape had the following weaknesses.

- The shape check lets through sequences with the same shape as keys that no actual key produces (`ESC [ 200 ~`, `ESC [ I`, and so on).
- Therefore the bytes that could be sent were not determined inside the gate alone, and it relied on `vtfx-compose`, which is not subject to review, passing only the output of `KeyEncoder`.

Because this involved changing the boundary or responsibilities, a human chose among the options below.

## Decision

The result is described in section 2 of [gate-policy.md](../design/gate-policy.md).

- Put `KeyStroke`, `TerminalKey`, and `KeyModifiers` in `vtfx-core` (`com.google.vtfx.core`). They are value types without behavior, defined by core as terminal vocabulary, like `TerminalModes` and `MouseEncoding`.
- Move `KeyEncoder` inside `vtfx-gate` (`internal`). `HostWriter.sendKey` takes a `KeyStroke` and `TerminalModes` and encodes inside the gate. This is the same shape as mouse reports (which take a `MouseReport` and mode values).
- The shape check (`KeySequences`) is no longer needed and is removed.
- `config/boundaries.toml` is not changed.

As a result, the bytes `sendKey` sends are limited to what xterm encodes for some combination of key, modifiers, and modes. The encoder's input is only the key, the modifiers, and the terminal modes (enums and booleans), and there is no path by which host strings get mixed in.

The IME spec test in `vtfx-input` (`ImeSpecTest`) cannot depend on the gate, so the test maps the keys appearing in traces to their bytes in the default modes. This is the same treatment as the test's mapping of text and DELs into the form the gate sends. The key encoding itself is verified by the gate's `KeyEncoderTest`.

## Alternatives considered

- **Bytes and a shape check (the design before this decision)**: As in the background, the gate alone does not determine the bytes that could be sent.
- **Make `vtfx-gate` depend on `vtfx-input` and take `KeyStroke`**: This would relax the restriction in boundaries.toml. The gate needs only three types, yet it would depend on the whole module, including the IME editing model.
- **Create a new module holding only the types**: The responsibility of core would not change, but a module and dependency edges would be added for three types.

## Impact of reversing

To return to bytes and a shape check, move the types and `KeyEncoder` back to `vtfx-input`, make the argument of `HostWriter.sendKey` bytes, and restore the shape check. The package of `KeyStroke` and the others changes, so the imports of apps using the public API (`TerminalSession.sendKey`) change too.
