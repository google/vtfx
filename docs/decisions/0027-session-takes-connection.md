# TerminalSession takes the connection to the host directly

- Status: Approved (2026-10-15, approved by a human in a review of the review queue. The approach was decided by a human on 2026-10-14); item 3 superseded by [0044](0044-boundaries-in-gradle-and-classes.md)
- Date: 2026-10-14
- Related: [0007](0007-compose-session.md) (shape of the public API), [0016](0016-public-api.md) (scope of the public API), [0026](0026-session-module.md) (`vtfx-session`), section 2.1 of [session-and-threads.md](../design/session-and-threads.md), section 5 of [architecture.md](../design/architecture.md), a review of the public API (2026-10-14), `config/boundaries.toml`

## Background

[0007](0007-compose-session.md) chose the shape in which the app creates a `HostWriter` from its connection to the host (`HostConnection`) and passes it to `TerminalSession`. At the time, `TerminalSession` was in vtfx-compose, and the reasons were to keep vtfx-compose from holding the raw connection and to keep the boundary that only vtfx-gate can refer to `HostConnection`.

[0026](0026-session-module.md) moved `TerminalSession` to the JVM module vtfx-session. The only public declaration of `HostWriter` was its constructor, and to apps it was merely a wrapper for passing to `TerminalSession`. The public API review pointed out that this makes apps create an extra type. A human decided to move the creation of `HostWriter` into `TerminalSession`.

## Decision

1. `TerminalSession` takes `connection: HostConnection` as its first argument and creates the `HostWriter` right away during construction. The session does not keep the connection; it keeps only the `HostWriter`.
2. `HostWriter` becomes `@InternalVtfxApi` and is removed from the public API. It is still only vtfx-gate that assembles and checks the bytes for the host.
3. In `config/boundaries.toml`, add only `TerminalSession.kt` of `vtfx-session` to the paths allowed to refer to `HostConnection`, limited to one file so that the check can confirm the connection is not passed on from there. vtfx-compose still cannot refer to it. Superseded by [0044](0044-boundaries-in-gradle-and-classes.md), under which any module may name `HostConnection` as a type and only vtfx-gate may use its members (section 5 of [architecture.md](../design/architecture.md)).
4. `TerminalSession.kt` is not added to `security_review` in `config/protected-paths.toml` (2026-10-15, decided by a human). It is a file that changes often, and it only hands the connection to `HostWriter`.

## Alternatives considered

- **Keep the shape of 0007**: Apps would keep creating `HostWriter`, a type that can do nothing. The boundary check would be kept, but its value lies in hiding the connection from vtfx-compose, which is not compromised by vtfx-session receiving the connection.
- **Allow `HostConnection` in the whole vtfx-session module**: The check could no longer prevent future code from using the connection directly.

## Impact of reversing

- Change the first argument of `TerminalSession` back to `HostWriter`, and remove `@InternalVtfxApi` from `HostWriter`. `config/boundaries.toml` needs no change: since [0044](0044-boundaries-in-gradle-and-classes.md), vtfx-session may name `HostConnection` but not use its members either way.
- Revert the examples in the sample app, the tests, `usage.md`, `architecture.md`, and [session-and-threads.md](../design/session-and-threads.md).
