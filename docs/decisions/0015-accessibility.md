# Accessibility (TalkBack)

- Status: Approved (2026-10-13, approved by a human in the final review. Rows are marked as sensitive data); on API 29 to 33, that only accessibility tools see the rows superseded by [0039](0039-minsdk-29.md)
- Date: 2026-10-12
- Related: Requirement 6.3, section 11 of [compose-input.md](../design/compose-input.md), 2.7 of [research/xterm-js-ime.md](../research/xterm-js-ime.md), [0039](0039-minsdk-29.md), `Terminal.kt` (`RowSemantics`, `TerminalRowSemanticsNode`), `TerminalInputView`

## Background

Requirement 6.3 asks that terminal content can be read with TalkBack, and that the IME and accessibility implementations do not constrain each other. At first, the text of the whole screen was the `text` of one node, so TalkBack only read the whole screen at once and could not move line by line. Also, `TerminalInputView`, which connects the IME, was exposed as a focusable view covering the whole grid, and explore by touch put focus on it.

Host output can contain secrets ("unintended leakage of terminal content" in requirement 6.1), and the accessibility tree is readable by any app that obtained accessibility permissions.

## Decision

The result is described in section 11 of [compose-input.md](../design/compose-input.md).

- **A node per row**, with the position and size of the row, so that screen readers move row by row and read the touched row with explore by touch.
- **Compute only when queried**, so that the rendering load does not increase when there is no accessibility service.
- **No click action on rows**: Compose reports the bounds of clickable nodes enlarged to the minimum touch target (48dp), and rows are shorter than that, so the bounds overlapped and the focus frame and the touched row were misaligned. Explore by touch sends a double tap on a node without a click action as a tap at the focus position, which focuses the terminal and shows the keyboard like a normal tap.
- **Do not expose the view for the IME**: the IME connects through input focus, independent of the accessibility tree. The editing buffer the IME sees and the content the screen reader reads are different things, so they do not constrain each other (there is no problem like the shared textarea of xterm.js).
- **Mark rows as sensitive data** (decided by a human): only services that declare `isAccessibilityTool` (TalkBack, Switch Access, and so on) see the rows, so apps that obtained accessibility permissions cannot read the content. As a cost, reading apps that do not declare `isAccessibilityTool` cannot read the terminal content.
- **Leave Content Capture and screen sharing to the app**: Compose's Content Capture does not look at `isSensitiveData`, and the means of stopping it (`ContentCaptureManager.isEnabled`, an experimental API) affects the whole process. `Modifier.sensitiveContent`, which hides content from screen sharing and recording, also gets in the way when the terminal should be shared and shown (explaining operations and so on). So the library sets neither, and the app decides.
- **No automatic reading of new output (live regions)**: Apps that rewrite the whole screen (htop and so on) would make reading never stop. If needed, consider it as a setting the app can choose.

## Alternatives considered

- **Make the whole screen one node, and leave moving by line to movement at character granularity**: Moving by line with Compose's `text` node requires passing the result of text layout, which does not correspond to the terminal grid.
- **Put a click action on rows and avoid the enlargement of bounds**: The enlargement happens inside Compose's accessibility implementation, and there is no public means of stopping it from the node's side.

## Impact of reversing

Change `RowSemantics` and `TerminalRowSemanticsNode` in `Terminal.kt`, `TerminalSemanticsTest`, and section 11 of [compose-input.md](../design/compose-input.md). To remove the sensitive mark, delete `isSensitiveData` in `applySemantics` and revise `TerminalSemanticsTest.rowsAreShownOnlyToAccessibilityTools`.
