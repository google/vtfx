# Selection handles for touch

- Status: Approved
- Date: 2026-10-28
- Related: Requirement 5.4, sections 2, 4, and 14 of [compose-input.md](../design/compose-input.md), section 19 of [device-checks.md](../device-checks.md), [0085](0085-compose-interaction.md)

## Background

A long press selects a word, and moving the finger before lifting it extends the selection by words (topic 17 of [0085](0085-compose-interaction.md)). Once the finger is lifted, touch had no means of changing the selection: a word too many or too few could be fixed only by selecting again, and a selection could not start or end inside a word. Android's text fields and Compose's selectable text show a teardrop handle at each end of a selection made by touch, which the user drags to move that end. Compose's handles (`SelectionHandle` in `androidx.compose.foundation.text.selection`) are internal to Compose and work only with Compose's text layouts. The framework's handles are drawables of the theme (`android:textSelectHandleLeft` and `textSelectHandleRight`), tinted with the activity theme's `colorControlActivated`.

## 1. When handles are shown

A selection shows its handles when a touch made it or last changed it: when the finger of a long press is lifted, when a handle is dragged, and after Select all of a menu that a long press opened. A selection made with the mouse, Select all of a menu that a right click opened, and `TerminalState.selectAll()` show none. Whenever the selection is set in another way, the handles go; so they go with the selection, also when the display drops it.

**Why.** Android's text fields and Compose show handles only for a selection made by touch. A mouse has its own means of extending a selection (a drag, Shift+click), and handles would cover the text under the pointer. The app's call has no touch to follow; the app shows its own UI for it.

**Alternatives considered.**
- **Handles for every selection**: Also for a mouse, which does not need them, and for the app's calls.
- **Handles also while the finger of the long press is down**: The finger extends the selection then; the handles would appear under it.

## 2. Look

The handles are drawn by vtfx as Compose's text fields draw theirs: a 25 dp circle with the square corner of its top quadrant on the side of the text, pointing at the start or the end of the selection, in the color `LocalTextSelectionColors.current.handleColor`. A handle that would leave the window at its side hangs on the other side of its tip.

**Why.** Compose's handles cannot be reused (Background), so vtfx draws the same shape. The color is the one that the app's text fields take from a Material theme, so that the terminal's handles match the app's other handles. The handles hang over the app's UI around the terminal, so they follow the app's theme rather than `TerminalColors`, and no public API is added. A terminal usually fills the width of the window, and selections often start at the first column, where the start handle would be drawn outside the window; `TextView` turns a handle at the window's edge the same way.

**Alternatives considered.**
- **The framework's drawables**: The look of the device's text fields, but tinted with the activity theme's `colorControlActivated`, which Compose apps often leave unset, so the handles would not match the app's Compose text fields; Compose does not use them either.
- **A color in `TerminalColors`**: Another required parameter for something outside the grid, and an app would have to repeat its theme's color.

## 3. Popups

Each handle is drawn in its own `Popup`, which does not take the focus, positioned at its end's corner of the grid. A handle is not shown while its end is off the display, or outside the part of the grid that the terminal's ancestors leave visible.

**Why.** A handle hangs below the row it points at, and so beyond the grid on the last row; the terminal clips what it draws to its bounds. Compose's handles are popups for the same reason. A popup that took the focus would take the IME's input connection from the terminal. A handle outside the visible part of the terminal would float over the app's UI.

**Alternatives considered.**
- **Drawing the handles in the terminal's layout**: No windows, but the handle of the last row would be cut by the terminal's bounds, and its touches would not reach the terminal beyond them.

## 4. Moving an end

Dragging a handle moves its end by cells, to the cell at the edge nearest to the handle's tip, on the row above the tip, keeping the distance between the finger and the tip of the press. The end does not pass the other end: at least one cell stays selected, and the handles keep their sides. A wide character is taken whole at the moved end. Each move to another cell gives the haptic feedback `TextHandleMove`, as Compose's handles do. The selection then extends by cells (topic 17 of [0085](0085-compose-interaction.md)).

**Why.** The handles are for adjusting a selection that a long press made by words, so they go by the unit that words cannot give. Topic 17 of [0085](0085-compose-interaction.md) keeps the unit of a drag unchanged during the drag, without switching between units. Stopping at the other end, as `TextView` does, keeps each handle at its end through a drag. A selection never covers half of a wide character, so the handle does not stop in the middle of a wide character.

**Alternatives considered.**
- **By words, switching to cells when the handle turns back, as `TextView` and Compose do**: State to keep for each drag, and a word of a terminal (a path, a command line) is often longer than what the user wants.
- **Swapping the handles when one passes the other, as Compose does**: The dragged handle would change its shape and side under the finger.

## 5. No scrolling at the edges

A handle dragged beyond the top or the bottom of the display stops at its first or last row; the display does not scroll. To move an end into lines that the display does not show, the user scrolls the display with a swipe, which keeps the selection, and drags the handle again.

**Why.** Scrolling while a handle is held at an edge needs a timer for each drag, and a speed to choose. Swiping already scrolls, and the handle that moves stays on the display.

**Alternatives considered.**
- **Scrolling the display while a handle is at an edge, as `TextView` in a scrolling view does**: More direct for long selections. It can be added later if the checks on devices show the need.

## 6. The menu

Dragging a handle hides the menu, and lifting it shows the menu again at the row of the dragged end. The menu of a selection with handles is placed so as not to cover the handles below the row.

**Why.** `TextView` does so: the menu would cover the text near the handle during the drag, and the user who adjusted the selection wants to copy it next. A menu over the handles would leave nothing to drag; on a Pixel 9, the menu of a long press on the first row went below the row, over the handles.

## Impact of reversing

Remove `SelectionHandles.kt`, `TerminalSelection.withEndAt`, `TerminalDisplay.showsHandles` and `showHandles`, and the `withHandles` parameter of `showMenu` in `Terminal.kt`, and revise sections 2, 4, 12, and 14 of [compose-input.md](../design/compose-input.md), section 19 of [device-checks.md](../device-checks.md), and [usage.md](../usage.md).
