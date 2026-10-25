# App keys before the IME, the default size, and the inner padding of Terminal

- Status: Approved (2026-10-15, approved by a human in a review of the review queue)
- Date: 2026-10-14
- Related: Requirements 5.4, 5.5, and 6.5 (settings for appearance), sections 7.2 and 10 of [compose-input.md](../design/compose-input.md), [0084](0084-session-and-threads.md), [0016](0016-public-api.md), [0019](0019-extensibility.md), [ime-contract.md](../design/ime-contract.md) section 6.3

## Background

Three gaps in embedding `Terminal`:

- App shortcuts (Ctrl+Shift+C and V) could not be caught with a physical keyboard and Gboard. The KDoc of `Terminal` recommended `Modifier.onPreviewKeyEvent` on an ancestor ([0019](0019-extensibility.md)), which sees only the keys the IME passes on.
- `TerminalStyle` had no inner padding, so the grid touched the edges of `Terminal`, and `Modifier.padding` around it showed the colors behind it rather than the terminal's background.
- `Terminal` had no size of its own. Without a size in its `modifier`, it was laid out 0 × 0 where the constraints allowed (for example, in a `Column` without `weight`), and silently resized the session to 1 × 1, which made the host redraw for one cell and reflowed the scrollback to one column.

## Decision

### App keys before the IME

`Terminal` takes `onPreImeKeyEvent: ((KeyEvent) -> Boolean)?`, which `TerminalInputView.dispatchKeyEventPreIme` calls with each hardware key event before the IME; returning true consumes the event ([compose-input.md](../design/compose-input.md) section 7.2). This replaces the key interception of [0019](0019-extensibility.md).

- **Why `dispatchKeyEventPreIme` of the view**: Android dispatches a hardware key in three stages: the views' `dispatchKeyEventPreIme`, the IME, and the views' `dispatchKeyEvent`. Compose runs `onPreviewKeyEvent` in the last, and Gboard consumes the keys of a physical keyboard in the second and resends them through `InputConnection`. Compose's own pre-IME modifier (`onPreInterceptKeyBeforeSoftKeyboard`) is experimental and runs only when a Compose node has the focus, while here the `View` for the IME connection has it (`AndroidComposeView` passes the pre-IME dispatch to its focused child view).
- **Name**: `onPreImeKeyEvent` says when it is called, like Android's `dispatchKeyEventPreIme` and `onKeyPreIme`, and that it sees every key, not only shortcuts (`onShortcut` would suggest that vtfx decides what a shortcut is).
- **Compose's `KeyEvent`**: the type that Compose apps already match with `key`, `isCtrlPressed`, and `type`; `nativeKeyEvent` gives the Android event, such as its repeat count.

### Default size

`Terminal` prefers 80 × 24 cells (xterm's default, and `TerminalSession`'s), the way `Text` prefers the size of its text, clamped to the incoming constraints ([compose-input.md](../design/compose-input.md) section 10.1). The human chose this. Laid out with no width or height, `Terminal` logs a warning and does not resize the session.

- **Not the session's current size**: preferring the session's size instead would make a terminal in loose constraints keep whatever size it last had, and the preferred size would change with every resize.

### Inner padding

`TerminalStyle.padding: PaddingValues`, zero by default, so that existing layouts and the goldens do not change ([compose-input.md](../design/compose-input.md) section 10.2).

- `PaddingValues` is Compose foundation's type, which apps already use with `Modifier.padding`; like `Color` and `TextUnit` of Compose UI elsewhere in `TerminalStyle`, it comes from a dependency that `vtfx-compose` declares as `implementation`, which a Compose app has anyway.
- The padding is drawn in the grid's default background color, so that it looks part of the terminal.
- The grid, the view for the IME, and the rows for accessibility are inset by `Modifier.padding`, so that their coordinates stay the grid's, and the gestures, the menu anchor, and the `CursorAnchorInfo` need no change.
- A tap in the padding focuses the terminal, as a tap on the grid does.
- Each change of the style makes the paints and the cell size again, padding included. Changing the padding is rare, so it is not split out.

## Alternatives considered

- **Keep `onPreviewKeyEvent` and only correct the documentation**: The app would have to override `Activity.dispatchKeyEvent`, which cannot tell which of several terminals a key is for ([0019](0019-extensibility.md)).
- **Pass Android's `KeyEvent`**: Apps would need Android's meta-state flags instead of the Compose extensions they use elsewhere.
- **Default size: fill the constraints' maximum**: A terminal in a `Column` would push the views after it out of the screen, and in a scrolling container it would have no maximum to fill.
- **Padding drawn by the renderer, with the grid offset inside one `Canvas`**: The gestures, the menu anchor, and the IME's positions would all have to subtract the offset.
- **Default size: throw on zero constraints**: A layout passes through 0 × 0 during animations and in tests, and a crash there would be worse than a warning.

## Impact of reversing

Removing `onPreImeKeyEvent` removes the parameter of `Terminal` (an API change; [0016](0016-public-api.md)) and the override in `TerminalInputView`.

To drop the default size, remove `preferredGridSize` from `Terminal`; a `Terminal` without a size then takes the minimum of its constraints again. To resize to one cell again instead of keeping the size, remove the early return in the `onSizeChanged` of the grid.

To remove the padding, remove `TerminalStyle.padding` (an API change) and the `padding` modifiers, the background, and the tap detector in `Terminal`.

In each case, update section 7.2 or 10 of [compose-input.md](../design/compose-input.md).
