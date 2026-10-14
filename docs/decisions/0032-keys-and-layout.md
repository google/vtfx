# App keys before the IME, the default size, and the inner padding of Terminal

- Status: Approved (2026-10-15, approved by a human in a review of the review queue)
- Date: 2026-10-14
- Related: Requirements 5.4, 5.5, and 6.5 (settings for appearance), [0013](0013-resize-and-lifecycle.md), [0016](0016-public-api.md), [0019](0019-extensibility.md), [ime-contract.md](../design/ime-contract.md) section 6.3

## Background

Three gaps in embedding `Terminal`:

- App shortcuts (Ctrl+Shift+C and V) could not be caught with a physical keyboard and Gboard. The KDoc of `Terminal` recommended `Modifier.onPreviewKeyEvent` on an ancestor ([0019](0019-extensibility.md)), which sees only the keys the IME passes on.
- `TerminalStyle` had no inner padding, so the grid touched the edges of `Terminal`, and `Modifier.padding` around it showed the colors behind it rather than the terminal's background.
- `Terminal` had no size of its own. Without a size in its `modifier`, it was laid out 0 × 0 where the constraints allowed (for example, in a `Column` without `weight`), and silently resized the session to 1 × 1.

## Decision

### App keys before the IME

`Terminal` takes `onPreImeKeyEvent: ((KeyEvent) -> Boolean)?` (Compose's `KeyEvent`), null by default. `TerminalInputView.dispatchKeyEventPreIme` calls it with each key event that the window dispatches to the focused view before the IME: presses, repeated presses, and releases, of hardware keys, only while the terminal has the focus. Returning true consumes the event; the window then sends it neither to the IME nor to `dispatchKeyEvent`, so it does not reach the session. The KDoc asks the app to consume a key's release with its press, and to return false for the keys it does not use, including system keys such as Back (the IME uses Back to hide itself).

- **Why `dispatchKeyEventPreIme` of the view**: Android dispatches a hardware key in three stages: the views' `dispatchKeyEventPreIme`, the IME, and the views' `dispatchKeyEvent`. Compose runs `onPreviewKeyEvent` in the last, and Gboard consumes the keys of a physical keyboard in the second and resends them through `InputConnection`. Compose's own pre-IME modifier (`onPreInterceptKeyBeforeSoftKeyboard`) is experimental and runs only when a Compose node has the focus, while here the `View` for the IME connection has it (`AndroidComposeView` passes the pre-IME dispatch to its focused child view).
- **Name**: `onPreImeKeyEvent` says when it is called, like Android's `dispatchKeyEventPreIme` and `onKeyPreIme`, and that it sees every key, not only shortcuts (`onShortcut` would suggest that vtfx decides what a shortcut is).
- **Compose's `KeyEvent`**: the type that Compose apps already match with `key`, `isCtrlPressed`, and `type`; `nativeKeyEvent` gives the Android event, such as its repeat count.
- **Checked on the emulator** (Pixel 9 Pro AVD, API 36 Google Play image, the preinstalled Gboard in English): with keys injected by `adb shell input keyboard keycombination`, the sample app's `Ctrl+Shift+K` reached `onPreImeKeyEvent` and not the host, and `Ctrl+C` reached the host as `03`. With the earlier sample using `onPreviewKeyEvent`, the same `Ctrl+Shift+K` reached the host as `0b`. A real physical keyboard, and Gboard's Japanese, are left to the device checks (3.1 of [device-checks.md](../device-checks.md)). Robolectric has no IME, so `TerminalKeyInterceptionTest` dispatches as the window does: before the IME, then, unless consumed, after it.

### Default size

`Terminal` prefers 80 × 24 cells (xterm's default, and `TerminalSession`'s), the way `Text` prefers the size of its text: the size is clamped to the incoming constraints, so fixed constraints (`size`, `fillMaxSize`, `weight`) decide it, loose ones give 80 × 24 cells or their maximum, and unbounded ones (inside a scrolling container) give 80 × 24 cells. The human chose this (2026-10-14).

- **A grid with no width or height**: `Terminal` logs a warning with `android.util.Log` (tag `vtfx`) and does not resize the session, which keeps its size. Before, it resized the session to 1 × 1, which made the host redraw for one cell and reflowed the scrollback to one column. A size that is not zero but holds less than one cell still gives one column or row, as before.
- **Not the session's current size**: preferring the session's size instead would make a terminal in loose constraints keep whatever size it last had, and the preferred size would change with every resize.

### Inner padding

`TerminalStyle.padding: PaddingValues`, zero by default, so that existing layouts and the goldens do not change.

- `PaddingValues` is Compose foundation's type, which apps already use with `Modifier.padding`; like `Color` and `TextUnit` of Compose UI elsewhere in `TerminalStyle`, it comes from a dependency that `vtfx-compose` declares as `implementation`, which a Compose app has anyway.

- The padding is drawn in the grid's default background color, swapped with the foreground in reverse video (DECSCNM), as the renderer swaps the grid's.
- The grid's `Canvas`, the `TerminalInputView` for the IME, and the rows for accessibility are all inset by `Modifier.padding`. Their coordinates stay the grid's, so the gestures, the long-press menu's anchor (from the grid's position in the root), and the `CursorAnchorInfo` (from the view's position on the screen) need no change. The columns and rows are counted from the inset size, and the default size adds the padding.
- `Modifier.padding` follows the layout direction, so `start` is on the right in right-to-left layouts. The default size and the tap detector below round the padding to pixels as `Modifier.padding` does.
- A tap in the padding focuses the terminal and shows the keyboard, as a tap on the grid does; a detector on the outer box ignores presses inside the grid, which handles its own, and presses of a mouse's other buttons. Long presses and drags in the padding do nothing.
- Each change of the style makes the paints and the cell size again (they are keyed by the whole style), padding included. Changing the padding is rare, so it is not split out.

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
