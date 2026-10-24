# Size changes, reflow, lifecycle

- Status: Approved (2026-10-13, approved by a human in the final review); the place of the resize batching (the session) superseded by [0026](0026-session-module.md), which moved it to vtfx-compose, and in item 3, reporting later calls only when the size changes by [0038](0038-kitty-graphics.md), with which they also report a change of the size of a cell in pixels
- Date: 2026-10-12
- Related: Requirement 5.5, section 8 (whether to reflow on resize, the policy for resize notifications during the IME animation), sections 3 and 4 of [session-and-threads.md](../design/session-and-threads.md) (the resulting design), `Reflow`, `Emulator.resize`, `TerminalSession.resize`, `TerminalDisplay.resize`

## Background

On mobile, the terminal size changes often with screen rotation, multi-window, foldables, and showing the keyboard. Requirement 5.5 asks that resize notifications not fire repeatedly during the IME animation, that size changes be handled correctly, that the session be kept across configuration changes, and that whether to reflow be decided.

## Decision

1. **Reflow the main screen and the scrollback when the width changes**, laying out the cells of each logical line (rows joined by autowrap) again at the new width and moving the cursor with the character it was on. When the height changes, rows move between the screen and the scrollback so that the cursor stays visible. The alternate screen is not reflowed, only cut or padded, because the apps on it redraw at the new size. The rules are in section 3.3 of [session-and-threads.md](../design/session-and-threads.md).
2. **Batch size changes by time**: the terminal size is changed, and the host notified, only after the display size has stayed the same for 150 ms, so that the animations of showing the keyboard or rotating cause one reflow and one SIGWINCH. The first size is applied at once. Drawing during the animation keeps the grid of the old size. The batching is done by the display (`TerminalDisplay` in vtfx-compose), and `TerminalSession.resize` applies a size at once ([0026](0026-session-module.md); section 3.2).
3. **The first `TerminalSession.resize` of a session reports `onResize` even when the size equals the one the session was created with**, and later calls only when it changes (2026-10-14, decided by a human). The app can wait for the first `onResize` before starting the host. It is per session, not per `Terminal` (section 3.1).
4. **The app owns the session**, so the content and the modes survive the composable being recreated. The IME input history does not: the session loses focus when the input `View` leaves the screen, which resets it (5.1 of [ime-contract.md](../design/ime-contract.md)). Rotation, multi-window, and foldables are all treated as combinations of "the display size changes" and "the Activity is recreated", with no handling specific to each (section 4).
5. **The echo of long input that merely advanced to the next row by autowrap does not reset the IME input history** (5.1 of [ime-contract.md](../design/ime-contract.md)).

## Alternatives considered

- **Do not reflow (same as xterm)**: The right side of long lines would be lost on every rotation. Rotation is frequent on mobile, so this is not taken.
- **Detect the end of the animation with the IME insets animation (`WindowInsetsAnimation`)**: It does not work for size changes other than the IME. Batching by time works the same for rotation and dragging in multi-window, and it is simple.

## Impact of reversing

To stop reflowing, return `Emulator.resize` to truncation (`ScreenBuffer.resize` remains). The batching delay is changed with `RESIZE_DELAY_MILLIS` in `TerminalDisplay`.

To report only changes again, drop the `resized` flag in `TerminalSession.resize`; apps that wait for the first `onResize` would then wait forever when the display fits the session's size.
