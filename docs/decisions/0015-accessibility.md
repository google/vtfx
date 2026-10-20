# Accessibility (TalkBack)

- Status: Approved (2026-10-13, approved by a human in the final review. Rows are marked as sensitive data)
- Date: 2026-10-12
- Related: Requirement 6.3, 2.7 of [research/xterm-js-ime.md](../research/xterm-js-ime.md), `Terminal.kt` (`RowSemantics`, `TerminalRowSemanticsNode`), `TerminalInputView`

## Background

Requirement 6.3 asks that terminal content can be read with TalkBack, and that the IME and accessibility implementations do not constrain each other. In M4, the text of the whole screen was the `text` of one node, so TalkBack only read the whole screen at once and could not move line by line. Also, `TerminalInputView`, which connects the IME, was exposed as a focusable view covering the whole grid, and explore by touch put focus on it.

## Decision

- **A node per row**: Each displayed row is exposed as one node with the position and size of that row (`text` is the row's string without trailing whitespace). Screen readers move row by row with swipes, and read the touched row with explore by touch. When scrolled back into scrollback, the displayed rows are exposed.
- **Compute only when queried**: The row strings are made only when an accessibility service (or a test) queries semantics, once per display change, and shared by the row nodes. When there is no service, the rendering load does not increase.
- **No click action on rows**: Compose reports the bounds of clickable nodes enlarged to the minimum touch target (48dp). Rows are shorter than that, so the bounds overlapped, and the focus frame and the touched row were misaligned (confirmed on the emulator). A double tap on a node without a click action is sent by Android's explore by touch as a tap at the focus position, so like a normal tap it focuses the terminal and shows the keyboard.
- **Do not expose the view for the IME**: `TerminalInputView` is `IMPORTANT_FOR_ACCESSIBILITY_NO`. The connection with the IME is through input focus, independent of the accessibility tree. The editing buffer the IME sees (the input history) and the screen content the screen reader reads are different things, so they do not constrain each other (there is no problem like the shared textarea of xterm.js).
- **Mark rows as sensitive data** (2026-10-13, decided by a human): The row nodes get Compose's `isSensitiveData`. On Android 14 and later, nodes are shown only to services that declare `isAccessibilityTool` (TalkBack, Switch Access, and so on), and not to other accessibility services. Compose 1.12 not only sets `AccessibilityNodeInfo.setAccessibilityDataSensitive`, but also returns no node for requests from non-tools and does not add it to its parent's children. Host output can contain secrets ("unintended leakage of terminal content" in requirement 6.1), so apps that obtained accessibility permissions are not allowed to read the content. As a cost, reading apps that do not declare `isAccessibilityTool` cannot read the terminal content.
- **Leave Content Capture and screen sharing to the app**: Compose's Content Capture does not look at `isSensitiveData` (confirmed in the class files of Compose UI 1.12). The means of stopping it (`ContentCaptureManager.isEnabled`, an experimental API) affects the whole process, so the library does not change it and the app decides. `Modifier.sensitiveContent`, which hides content from screen sharing and recording, also gets in the way when the terminal should be shared and shown (explaining operations and so on), so the app decides. Both are written in section 6 of [usage.md](../usage.md). On the emulator (Android 17), Content Capture targets only the Google apps on an allowlist, and no session was created for the sample app.
- **No automatic reading of new output (live regions)**: Apps that rewrite the whole screen (htop and so on) would make reading never stop. If needed, consider it as a setting the app can choose.

## Alternatives considered

- **Make the whole screen one node, and leave moving by line to movement at character granularity**: Moving by line with Compose's `text` node requires passing the result of text layout, which does not correspond to the terminal grid.
- **Put a click action on rows and avoid the enlargement of bounds**: The enlargement happens inside Compose's accessibility implementation, and there is no public means of stopping it from the node's side.

## Unverified items

- Reading and operation with TalkBack on a real device (on the emulator, input from `adb` does not go through explore by touch, so only the node bounds and the tree structure were checked with `uiautomator`). Put in the review queue.

## Impact of reversing

Change `TerminalRowSemanticsNode` in `Terminal.kt` and `TerminalSemanticsTest`. To remove the sensitive mark, delete `isSensitiveData` in `applySemantics` and revise `TerminalSemanticsTest.rowsAreShownOnlyToAccessibilityTools`.
