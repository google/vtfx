# Size changes, reflow, lifecycle

- Status: Approved (2026-10-13, approved by a human in the final review)
- Date: 2026-10-12
- Related: Requirement 5.5, section 8 (whether to reflow on resize, the policy for resize notifications during the IME animation), `Reflow`, `Emulator.resize`, `TerminalSession.resize`

## Background

On mobile, the terminal size changes often with screen rotation, multi-window, foldables, and showing the keyboard. Requirement 5.5 asks that resize notifications not fire repeatedly during the IME animation, that size changes be handled correctly, that the session be kept across configuration changes, and that whether to reflow be decided.

## Decision

### Reflow

- When the width changes, reflow the main screen and scrollback. The cells of rows joined by autowrap (logical lines) are laid out again at the new width, and fullwidth characters that do not fit go to the next row. The empty cell autowrap leaves at the end of a row is not a character, so it is not copied in the next reflow (it is copied only when the cursor is there, to keep the cursor position). A space the host wrote there remains as a character, and a cell the host erased cannot be distinguished from an empty one, so it is treated as empty. Link detection and copying a selection use the same judgment (`Line.endsWithWidePadding`) (added 2026-10-13 after a code review). Trailing whitespace of a logical line is discarded, except up to the cursor position. The cursor moves along with the character it was on. A cursor waiting to wrap (right after writing in the last column) is treated as the position where the next character will be written, and moves along right after the character just written. If that position is exactly the end of a new row, it stays waiting to wrap. The same applies to the saved cursor of the main screen while the alternate screen is shown.
- When the height changes, the screen shows the last row that ends with content or the cursor, and the rows above it go into scrollback. When the height decreases, upper rows move to scrollback so that the cursor stays visible, and when it increases, they come back from scrollback.
- The alternate screen is not reflowed. It is only truncated or padded with blanks, because apps on the alternate screen (vim, htop, and so on) learn the new size and redraw. While the alternate screen is shown, the saved cursor of the main screen (1049) is tracked through reflow.
- Fullwidth characters cannot be shown at width 1, so they are dropped in reflow.

### Batching resize notifications

- `Terminal` tells the session the number of columns and rows computed from the display size every time, but the session changes the terminal size and calls `TerminalSessionListener.onResize` only after the same size has lasted 150 ms. During the animations of showing the keyboard or rotating, the notification to the host (SIGWINCH) and the reflow happen once. Only the first size is applied immediately.
- The first `TerminalSession.resize` of a session calls `onResize` even when the size equals the one the session was created with; later calls only when it changes (2026-10-14, decided by a human). The app can wait for the first `onResize` before starting the host (for example, before requesting the PTY), instead of starting it with the constructor's size and resizing it right away. It is per session, not per `Terminal`: a `Terminal` recreated after a configuration change does not report the same size again.
- Drawing during the animation keeps the grid of the old size (what sticks out is not drawn).
- Where its constraints leave the size free, `Terminal` takes 80 × 24 cells, and laid out with no width or height it logs a warning and does not resize the session (added 2026-10-14; [0032](0032-keys-and-layout.md)).
- Judging by the state of the IME animation (`WindowInsetsAnimation`) is not adopted. The same mechanism works for rotation and dragging in multi-window, and it is simple.

### Lifecycle

- The app owns the session (0007), so even when the Composable goes away and is recreated, the content, the modes, and the IME input history are kept. The input View reconnects to the session when focus returns to the recreated Composable. The sample app holds the session in a `ViewModel`.
- Rotation, multi-window, and foldables are all treated as combinations of "the display size changes" and "the Activity is recreated", with no handling specific to each. They are tested with reflow and height changes (`TerminalTest`), batching notifications (`TerminalSessionTest`), and recreating the Composable (`TerminalSemanticsTest.sessionOutlivesTheComposable`).

### IME input history

- When the echo of long input merely advanced to the next row by autowrap, it is not considered "host output moved the cursor to another line", and the input history is not reset (5.1 of ime-contract.md).

## Alternatives considered

- **Do not reflow (same as xterm)**: The right side of long lines would be lost on every rotation. Rotation is frequent on mobile, so this is not taken.
- **Detect the end of the animation with the IME insets animation**: It does not work for size changes other than the IME.

## Impact of reversing

To stop reflowing, return `Emulator.resize` to the previous truncation (`ScreenBuffer.resize` remains). The notification delay is changed with `TerminalSession.RESIZE_DELAY_MILLIS`.

To report only changes again, drop the `resized` flag in `TerminalSession.resize`; apps that wait for the first `onResize` would then wait forever when the display fits the session's size.
