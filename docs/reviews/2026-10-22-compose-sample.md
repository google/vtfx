# 2026-10-22 Bugs reproduced in a code review

- Review target: `b649be2a7d94a8b56c2921c235bdcef9c606f018`
- Reviewer: coding agent
- Points of view: 1 (security boundary and resource limits), 2 (crashes, losing input or display updates), 3 (discrepancies with [compose-input.md](../design/compose-input.md), [ime-contract.md](../design/ime-contract.md), [fonts.md](../design/fonts.md), and [decisions/](../decisions/README.md)), 4 (paths across module boundaries)

Reproduced 11 bugs with 11 test cases across `vtfx-compose` and `vtfx-sample`. This review did not change product code.

## Status

| ID | Severity | Bug | State | Fix commit / verification |
|---|---|---|---|---|
| R1 | High | An empty `LocaleList` in `TerminalStyle.locale` crashes `TextPaints` with `IllegalArgumentException` | Fixed | `0160b6e34eb1be0eb47e934fbd9c9ae8c6271fc2`: an empty list leaves the paints' text locales at the system's, like null. Failed before: `TerminalStyleTest.terminalWithAnEmptyLocaleDraws`, `TerminalStyleTest.emptyLocaleLeavesTheSystemsLocaleLikeNull` |
| R2 | Medium | Clicking an OSC 8 hyperlink that wraps before a wide character truncates the reported link text | Open | — |
| R3 | Medium | Word selection includes a preceding fullwidth space (`U+3000`) or concealed wide character | Open | — |
| R4 | Medium | Touch gestures in the inner padding treat drags and long presses as taps and do not clear the selection on tap | Open | — |
| R5 | Medium | A focused block cursor on a box-drawing or block-element character draws the font glyph instead of `BoxDrawing` | Open | — |
| R6 | Medium | Detaching `TerminalDisplay` while a background invalidation is pending permanently stops future display updates if reattached | Open | — |
| R7 | Medium | Detaching and reattaching `TerminalInputView` to a window permanently unbinds its session | Open | — |
| R8 | Medium | A mouse click reported to the host does not drop the selection | Open | — |
| R9 | Medium | Exiting a replay in `EchoHost` leaves mouse tracking, focus reporting, bracketed paste, and application cursor keys enabled | Open | — |
| R10 | Low | `FontStore` in `vtfx-sample` allows importing more than `TerminalFont.Sanitized.MAX_FAMILIES` fonts, crashing `MainActivity` on import and startup | Open | — |
| R11 | Medium | `SampleViewModel.fontRequest` is never cleared, re-opening the system file picker when `MainActivity` is recreated | Open | — |

The meaning of the states and how to update them are in 3.3 of [README.md](README.md).

## Running the reproduction tests

The reproduction tests are in [repro-2026-10-22-compose-sample/](repro-2026-10-22-compose-sample/). Run them from the root of the repository.

```sh
./gradlew --console=plain --no-configuration-cache \
  -I docs/reviews/repro-2026-10-22-compose-sample/review.init.gradle \
  :vtfx-compose:reviewRepro
```

At the target commit, the result is **11 tests completed, 11 failed**.
Requires the JDK and the Android SDK (`ANDROID_HOME`); no emulator is needed.

## R1: An empty `LocaleList` in `TerminalStyle.locale` crashes `TextPaints` with `IllegalArgumentException`

- Related: [TerminalRenderer.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalRenderer.kt) (`TextPaints.paint`), [TerminalStyle.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalStyle.kt) (`TerminalStyle.locale`)
- Contract: [TerminalStyle.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalStyle.kt): `TerminalStyle.locale` takes a Compose `LocaleList?`. `LocaleList.Empty` (or `LocaleList(emptyList())`) is a valid instance of `LocaleList` representing no explicit locales.
- Reproduction test: `emptyLocaleListInTerminalStyleDoesNotCrash`

Steps:

1. Compose `Terminal` with `style = TerminalStyle(locale = LocaleList.Empty)`.

Expected result: `Terminal` renders without crashing, leaving the paint's default text locales when the `LocaleList` has no locales.

Actual result: `java.lang.IllegalArgumentException: locales cannot be null or empty` thrown by `android.graphics.Paint.setTextLocales`.

View of the cause: `TextPaints.paint` converts a non-null `style.locale` with `android.os.LocaleList.forLanguageTags(locale.joinToString(",") { it.toLanguageTag() })` and passes the result to `Paint.setTextLocales`. When `locale` is empty, `joinToString(",")` returns `""`, and `android.os.LocaleList.forLanguageTags("")` returns `android.os.LocaleList.getEmptyLocaleList()`, which `Paint.setTextLocales` rejects with `IllegalArgumentException`.

Direction of the fix: Check that the `LocaleList` (and the converted `android.os.LocaleList`) is not empty before calling `Paint.setTextLocales`.

## R2: Clicking an OSC 8 hyperlink that wraps before a wide character truncates the reported link text

- Related: [Terminal.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/Terminal.kt) (`hyperlinkText`), [Emulator.kt](../../vtfx-core/src/main/kotlin/com/google/vtfx/core/Emulator.kt) (`printMapped`), [LineText.kt](../../vtfx-core/src/main/kotlin/com/google/vtfx/core/LineText.kt) (`endsWithWidePadding`)
- Contract: section 6.1 of [compose-input.md](../design/compose-input.md): for an OSC 8 hyperlink, "`onLinkClick` receives a `TerminalLink.Explicit` with `hyperlink`, whose `text` is that of the cells around the clicked one with the same hyperlink, joined across the rows that autowrap joins."
- Reproduction test: `clickingAnOsc8HyperlinkWrappedBeforeAWideCharacterReportsFullText`

Steps:

1. Resize the session to 5 columns and 4 rows.
2. Feed `\u001b]8;;https://example.com/\u0007abcdあ\u001b]8;;\u0007`, so that `"abcd"` fills columns 0..3 of row 0 and the wide character `"あ"` wraps to columns 0..1 of row 1, leaving a wide-padding cell at column 4 of row 0.
3. Tap on row 0 (or row 1) of the hyperlink.

Expected result: `onLinkClick` receives a `TerminalLink.Explicit` with `url = "https://example.com/"` and `text = "abcdあ"`.

Actual result: `onLinkClick` receives `text = "abcd"` when row 0 is tapped, and `text = "あ"` when row 1 is tapped (`expected:<[abcdあ]> but was:<[abcd]>`).

View of the cause: When a wide character cannot fit in the last column (`columns - 1`), `Emulator.printMapped` erases column `columns - 1` (clearing its `Hyperlink` to `null`), sets `wrapped = true`, and writes the wide character at column 0 of the next row. `LineText.kt` and `Links.kt` recognize this padding cell via `line.endsWithWidePadding(nextLine)`, but `hyperlinkText` in `Terminal.kt` does not: when walking backward from `(row, 0)` it checks `hasLink(row - 1, columns - 1)` (which is `false`), and when walking forward along `row - 1` it stops at column `columns - 1` instead of skipping the wide-padding cell and continuing to `row`.

Direction of the fix: Skip the trailing wide-padding cell (`endsWithWidePadding`) in both the backward and forward scans of `hyperlinkText` (and check whether `hyperlinkText` should also include `snapshot.linesAbove` and `snapshot.linesBelow` when an OSC 8 hyperlink continues past the top or bottom edge of the display, as `LinkFinder` does for matched links).

## R3: Word selection includes a preceding fullwidth space (`U+3000`) or concealed wide character

- Related: [TerminalSelection.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalSelection.kt) (`word`, `isBlank`, `text`)
- Contract: section 4 of [compose-input.md](../design/compose-input.md) ("A word is the run of non-blank characters around a cell; a blank cell selects that cell alone") and [decisions/0083](../decisions/0083-gate.md) ("Word selection takes a concealed cell as blank too, so that double-clicking next to concealed text does not join its blank cells to the word").
- Reproduction test: `wordSelectionDoesNotIncludePrecedingBlankOrConcealedWideCharacter`

Steps:

1. Feed `"　foo \u001b[8m秘密\u001b[28mbar"` (where `"　"` is `U+3000` IDEOGRAPHIC SPACE at columns 0..1, `"foo"` is at columns 2..4, concealed `"秘密"` is at columns 6..9, and `"bar"` is at columns 10..12).
2. Compute `TerminalSelection.word(snapshot, Cell(2, 0))` and `TerminalSelection.word(snapshot, Cell(10, 0))`.

Expected result: `firstWord` selects columns 2..4 with text `"foo"`, and `secondWord` selects columns 10..12 with text `"bar"`.

Actual result: `firstWord` starts at `Cell(1, 0)` and `firstWord.text(snapshot)` is `"　foo"` (`expected:<[]foo> but was:<[　]foo>`); `secondWord` starts at `Cell(7, 0)` and `secondWord.text(snapshot)` is `"  bar"`.

View of the cause: `isBlank(line, column)` begins with `if (line.widthAt(column) == 0) return false`, assuming that the right half (`width == 0`) of any wide character is never blank. When a wide character is blank (`U+3000`, where `Char.isWhitespace` / `String.isBlank` is `true`) or concealed with SGR 8 (`visibleTextAt` is `null`), `isBlank` returns `true` for its left half (`column`) but `false` for its right half (`column + 1`). Walking left from `"foo"` or `"bar"` therefore includes the wide tail at `column + 1`, which `TerminalSelection.text` and `expand` then widen to include the lead cell. Also, long-pressing on the right half (`column + 1`) of a blank or concealed wide character treats it as non-blank instead of selecting that wide cell alone.

Direction of the fix: In `isBlank(line, column)`, resolve a wide tail (`widthAt(column) == 0 && column > 0`) to its lead cell (`column - 1`) before checking `visibleTextAt(lead).isNullOrBlank()`, and ensure selecting a single blank wide cell covers both halves of the cell.

## R4: Touch gestures in the inner padding treat drags and long presses as taps and do not clear the selection on tap

- Related: [Terminal.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/Terminal.kt) (`paddingTaps`, `onTap`), [TerminalGestures.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalGestures.kt) (`terminalTouchInput`)
- Contract: section 10.2 of [compose-input.md](../design/compose-input.md) ("A tap in the padding focuses the terminal and shows the keyboard, as a tap on the grid does; long presses and drags in the padding do nothing") and section 4 of [compose-input.md](../design/compose-input.md) ("A tap on the grid, sending input to the host, scrolling, or output or a resize from the host drops the selection").
- Reproduction test: `paddingIgnoresDragsAndClearsSelectionOnTap`

Steps:

1. Compose `Terminal` with `style = TerminalStyle(padding = PaddingValues(30.dp))` and feed `"hello world"`.
2. Long-press on the grid to select `"hello"` and show the floating selection toolbar.
3. Drag horizontally inside the top padding (`(5f, 5f)` to `(100f, 5f)` and lift).
4. Tap inside the top padding (`(5f, 5f)`).

Expected result: After step 3 (drag in the padding), nothing happens: the selection and its toolbar remain shown. After step 4 (tap in the padding), the selection is cleared (`display.selection == null`), the toolbar is hidden, and the terminal is focused.

Actual result: In step 3, the drag in the padding is treated as a tap and hides the selection toolbar (`expected:<Shown> but was:<Hidden>`). In step 4, tapping in the padding hides the toolbar and shows the keyboard, but leaves `display.selection` non-null.

View of the cause: `paddingTaps` uses `awaitEachGesture { val down = awaitFirstDown(requireUnconsumed = false); if (!inGrid(down.position) && waitForUpOrCancellation() != null) onTap() }`. Because no pointer handler consumes movements in the padding or times out on a long press, `waitForUpOrCancellation()` returns non-null when the finger lifts after any drag that stays within the component bounds or after a long press in the padding. And `onTap` in `Terminal.kt` calls `toolbar.hide()` and `inputView?.showKeyboard()` without setting `display.selection = null`.

Direction of the fix: Clear `display.selection = null` in `onTap`, and ignore touches in `paddingTaps` that exceed touch slop or the long-press timeout.

## R5: A focused block cursor on a box-drawing or block-element character draws the font glyph instead of `BoxDrawing`

- Related: [TerminalRenderer.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalRenderer.kt) (`drawCursor`, `drawText`), [BoxDrawing.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/BoxDrawing.kt) (`isDrawn`, `drawBox`)
- Contract: section 7.3 of [emulation.md](../design/emulation.md) and [BoxDrawing.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/BoxDrawing.kt): box drawing characters (`U+2500..U+257F`) and block elements (`U+2580..U+259F`) are drawn as geometry filling the cell (or both cells when wide under `AmbiguousWidth.Wide`), not with font glyphs.
- Reproduction test: `focusedBlockCursorOnFullBlockDrawsBoxDrawingInsteadOfFontGlyph`

Steps:

1. Compose `Terminal` with `ambiguousWidth = AmbiguousWidth.Wide` and focus it so the cursor is a filled block (`CursorShape.Block`).
2. Feed `"\u2588\u001b[1;1H"` to place `U+2588` FULL BLOCK at `(0, 0)` and move the cursor onto `(0, 0)`.

Expected result: The block cursor fills the 2-cell wide character with `cursorColor` and redraws `U+2588` over both cells using `BoxDrawing.drawBox` in `cursorTextColor`, filling the entire 2-cell box with `cursorTextColor` (`0` pixels of `cursorColor` remaining).

Actual result: `drawCursor` paints `cursorColor` over the `BoxDrawing` shape drawn by `drawText`, and then calls `drawRun(text, ..., colorOverride = cursorTextColor)`, which draws the font's 1-cell glyph for `U+2588` instead of `BoxDrawing.drawBox`, leaving `cursorColor` visible across the second cell and at the glyph margins (`expected:<0> but was:<96>`).

View of the cause: `TerminalRenderer.drawText` checks `BoxDrawing.isDrawn` and calls `BoxDrawing.drawBox`, but `TerminalRenderer.drawCursor` unconditionally calls `drawRun` when redrawing the character under a focused `CursorShape.Block`.

Direction of the fix: In `drawCursor`, when the cell's text is a single code point for which `BoxDrawing.isDrawn` is true, draw it with `BoxDrawing.drawBox` using `cursorTextColor` over `Size(width * metrics.width, metrics.height)` instead of `drawRun`.

## R6: Detaching `TerminalDisplay` while a background invalidation is pending permanently stops future display updates if reattached

- Related: [TerminalDisplay.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalDisplay.kt) (`detach`, `invalidate`, `onOutput`)
- Contract: [TerminalDisplay.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalDisplay.kt): `detach()` "stops receiving the session's notifications, and drops what waits for the main thread."
- Reproduction test: `detachingWhileBackgroundInvalidationIsPendingDoesNotWedgeFutureUpdates`

Steps:

1. Attach a `TerminalDisplay` to a `TerminalSession`.
2. Call `session.feed("first".toByteArray())` from a background thread, so that `invalidate()` sets `invalidationPending` to `true` and posts its callback to `mainHandler`.
3. Before the main thread runs the posted callback, call `display.detach()`, then `display.attach()`.
4. Call `session.feed("second".toByteArray())` and run the main looper to idle.

Expected result: `display.observeVersion()` increments when `"second"` is fed.

Actual result: `display.observeVersion()` stays at `0` (`Values should be different. Actual: 0`); all subsequent `invalidate()` calls are dropped.

View of the cause: `detach()` calls `mainHandler.removeCallbacksAndMessages(null)` and resets `linesToFollow = -1L` and `restartPending.set(false)`, but does not reset `invalidationPending.set(false)` or `synchronizationTimeoutPending.set(false)`. When `removeCallbacksAndMessages(null)` removes the queued invalidation runnable before it executes `invalidationPending.set(false)`, `invalidationPending` stays `true` forever.

Direction of the fix: Reset `invalidationPending.set(false)` and `synchronizationTimeoutPending.set(false)` in `detach()` after `mainHandler.removeCallbacksAndMessages(null)`.

## R7: Detaching and reattaching `TerminalInputView` to a window permanently unbinds its session

- Related: [TerminalInputView.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalInputView.kt) (`onAttachedToWindow`, `onDetachedFromWindow`), [Terminal.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/Terminal.kt) (`AndroidView`)
- Contract: section 12 and section 5.1 of [ime-contract.md](../design/ime-contract.md): `TerminalInputView` is bound to `display` for the lifetime of the `AndroidView` in the composition (which unbinds it in `onRelease = { view -> view.bind(null) }`), and tracks whether it is attached and window-focused for session focus reporting.
- Reproduction test: `reattachingTerminalInputViewToWindowKeepsSessionBound`

Steps:

1. Bind a `TerminalInputView` to a `TerminalDisplay` and attach it to an Activity's view hierarchy.
2. Remove the `TerminalInputView` from its parent and add it back (detaching and reattaching it to the window without re-running `AndroidView.update`, as when reparenting or moving a `movableContentOf` subtree), then focus it.
3. Call `view.onCreateInputConnection(EditorInfo())`.

Expected result: `onCreateInputConnection` returns a non-null `InputConnection` for the bound session, and `updateSessionFocus()` reports focus to the session.

Actual result: `onCreateInputConnection` returns `null`, and the view ignores all key events and IME connections because `display` is `null`.

View of the cause: `onDetachedFromWindow()` calls `bind(null)` instead of updating `windowFocused = false; updateSessionFocus()`. While `AndroidView` already calls `view.bind(null)` in `onRelease` when leaving the composition, `onDetachedFromWindow()` also runs on transient window detaches where `AndroidView.update` will not run again upon reattachment, leaving `display` permanently `null` even though `onAttachedToWindow()` tries to call `updateSessionFocus()`.

Direction of the fix: In `onDetachedFromWindow()`, set `windowFocused = false` and call `updateSessionFocus()` so that detaching still reports focus out (`\u001b[O`) while keeping `display` bound until `onRelease` (or `bind(null)`) is called.

## R8: A mouse click reported to the host does not drop the selection

- Related: [TerminalMouse.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalMouse.kt) (`MouseHandler.press`, `MouseHandler.release`), [Terminal.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/Terminal.kt) (`onTap`)
- Contract: section 3 and section 4 of [compose-input.md](../design/compose-input.md) ("When it is dropped. Whenever the display changes: on output from the host, on scrolling, on the user's input, and on resizing. A tap and a mouse click without a drag drop it too").
- Reproduction test: `mouseClickReportedToHostDropsSelection`

Steps:

1. Compose `Terminal`, enable mouse tracking (`\u001b[?1000h`), and select text on the display (for example with `display.selectAll()` or Shift+drag).
2. Click the primary mouse button on the grid without holding Shift, which reports the press and release to the host.

Expected result: The mouse click drops the selection (`display.selection == null`), just as a touch tap in reporting mode (`TerminalGestures.kt`) or a mouse click outside reporting mode does.

Actual result: `display.selection` remains non-null (`expected null, but was:<TerminalSelection(...)>`).

View of the cause: In `MouseHandler.press`, when `isReporting(modifiers)` is true, it sends the mouse report and returns before reaching `select()`, which is where a non-reporting primary click clears `display.selection = null`. On release, `MouseHandler.release` calls `onClick()` (`onTap` in `Terminal.kt`), which hides the toolbar and shows the keyboard without clearing `display.selection`, and `TerminalSession.sendMouse` does not call `notifyUserInput()`.

Direction of the fix: Clear `display.selection = null` when a primary mouse click (or `onTap`) occurs while reporting to the host, matching `TerminalGestures.kt`.

## R9: Exiting a replay in `EchoHost` leaves mouse tracking, focus reporting, bracketed paste, and application cursor keys enabled

- Related: [EchoHost.kt](../../vtfx-sample/src/main/kotlin/com/google/vtfx/sample/EchoHost.kt) (`receive`)
- Contract: [EchoHost.kt](../../vtfx-sample/src/main/kotlin/com/google/vtfx/sample/EchoHost.kt): "Any key leaves the replay and returns to the shell, restoring the main screen, the scroll region, the attributes, the cursor, and the charsets that the recording may have changed."
- Reproduction test: `exitingReplayResetsMouseTrackingBracketedPasteAndFocusReporting`

Steps:

1. In `EchoHost` connected to a `TerminalSession`, run `replay vim\r` (where `recordings/vim.out` enables `?1049h`, `?1h`, `?2004h`, `?1004h`, `?1002h`, and `?1006h`).
2. Send `"q"` to leave the replay and return to the shell prompt.

Expected result: The terminal returns to normal shell state with `mouseTracking == MouseTracking.None`, `mouseEncoding == MouseEncoding.Default`, `focusEvents == false`, `bracketedPaste == false`, and `applicationCursorKeys == false`.

Actual result: `mouseTracking` remains `MouseTracking.ButtonEvent` (`expected:<None> but was:<ButtonEvent>`), `mouseEncoding` remains `MouseEncoding.Sgr`, `focusEvents` remains `true`, `bracketedPaste` remains `true`, and `applicationCursorKeys` remains `true`. After `replay vim`, touch taps, touch drags, and mouse wheel gestures in the shell are sent as mouse reports to `EchoHost` instead of scrolling or selecting text.

View of the cause: When exiting a replay, `EchoHost.receive` sends `\u001b[r\u001b[?1049l\u001b[0m\u001b[?25h\u001b(B\r\n`, which resets the scroll region, alternate screen, SGR attributes, cursor visibility, and G0 charset, but does not reset the private modes that recordings such as `vim.out` enable (`?1l`, `?6l`, `?7h`, `?1000l`, `?1002l`, `?1003l`, `?1004l`, `?1006l`, `?2004l`, `?2026l`, `\u001b>`).

Direction of the fix: Reset the terminal modes (for example with DECSTR `\u001b[!p` and resetting mouse, focus, and bracketed paste modes) when leaving a replay in `EchoHost.receive`.

## R10: `FontStore` in `vtfx-sample` allows importing more than `TerminalFont.Sanitized.MAX_FAMILIES` fonts, crashing `MainActivity` on import and startup

- Related: [FontStore.kt](../../vtfx-sample/src/main/kotlin/com/google/vtfx/sample/FontStore.kt) (`import`, `load`), [MainActivity.kt](../../vtfx-sample/src/main/kotlin/com/google/vtfx/sample/MainActivity.kt) (`onCreate`), [TerminalStyle.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalStyle.kt) (`TerminalFont.Sanitized.MAX_FAMILIES`)
- Contract: section 1 of [fonts.md](../design/fonts.md) and [TerminalStyle.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalStyle.kt): `TerminalFont.Sanitized` accepts at most `TerminalFont.Sanitized.MAX_FAMILIES` (64) families and throws `IllegalArgumentException` if given more.
- Reproduction test: `importingBeyondMaxFamiliesDoesNotCrashTerminalFontSanitized`

Steps:

1. Populate `FontStore` with `TerminalFont.Sanitized.MAX_FAMILIES` (64) fonts and call `store.import(...)` with one more valid font.
2. Pass the loaded fonts to `TerminalFont.Sanitized(loaded.map { SanitizedFamily(it) })` as `MainActivity.onCreate` does.

Expected result: `FontStore` either refuses to import beyond `TerminalFont.Sanitized.MAX_FAMILIES` (`ImportResult.Failed`) or caps the loaded fonts at `MAX_FAMILIES`, so `MainActivity` does not crash.

Actual result: `FontStore.import` saves 65 font files and returns 65 `SanitizedFont`s, causing `TerminalFont.Sanitized` to throw `java.lang.IllegalArgumentException: 65 families out of 1..64` on import and on every subsequent launch of `vtfx-sample`.

View of the cause: Neither `FontStore.import` nor `FontStore.load` checks `TerminalFont.Sanitized.MAX_FAMILIES`.

Direction of the fix: Reject imports in `FontStore.import` when the store already holds `TerminalFont.Sanitized.MAX_FAMILIES` fonts, and cap `FontStore.load` at `TerminalFont.Sanitized.MAX_FAMILIES`.

## R11: `SampleViewModel.fontRequest` is never cleared, re-opening the system file picker when `MainActivity` is recreated

- Related: [SampleViewModel.kt](../../vtfx-sample/src/main/kotlin/com/google/vtfx/sample/SampleViewModel.kt) (`fontRequest`, `fontCommand`, `importFont`), [MainActivity.kt](../../vtfx-sample/src/main/kotlin/com/google/vtfx/sample/MainActivity.kt) (`onCreate`, `LaunchedEffect(fontRequest)`)
- Contract: [SampleViewModel.kt](../../vtfx-sample/src/main/kotlin/com/google/vtfx/sample/SampleViewModel.kt): `font import` sets `fontRequest` to ask `MainActivity` to open the system document picker once for that command.
- Reproduction test: `fontRequestIsClearedAfterImportFont`

Steps:

1. Send `"font import\r"` to `SampleViewModel.session`, which sets `viewModel.fontRequest` to a non-null `FontRequest`.
2. Call `viewModel.importFont(request, uri)` as `MainActivity`'s document picker callback does, and let the font executor and main thread finish.

Expected result: `viewModel.fontRequest` is cleared to `null` once handled, so recreating `MainActivity` (such as on device rotation) does not launch the document picker again.

Actual result: `viewModel.fontRequest` remains non-null (`expected null, but was:<com.google.vtfx.sample.SampleViewModel$FontRequest@...>`), so any subsequent Activity recreation re-runs `LaunchedEffect(fontRequest)` with the stale request and opens the system document picker again.

View of the cause: `SampleViewModel.fontRequest` has a `private set` that is only ever assigned in `fontCommand` (`fontRequest = FontRequest(face)`); neither `importFont` nor any callback when the picker is launched or cancelled resets `fontRequest` to `null`.

Direction of the fix: Clear `fontRequest = null` when `MainActivity` consumes the request to launch the document picker (or when the picker returns a result or cancellation, storing the pending face in `rememberSaveable` or `SampleViewModel` so that completing the picker across a configuration change still imports the chosen file without re-launching the picker).

## Unverified concerns

- `TerminalInputView.onCreateInputConnection` in [TerminalInputView.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalInputView.kt) and [CursorAnchorReporter.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/CursorAnchorReporter.kt): the `geometry` lambda passed to `CursorAnchorReporter` checks only `metrics?.let { ... }` without checking `width > 0 && height > 0` (even though `CursorAnchorReporter`'s KDoc states that `geometry` returns `null` if not laid out), does not add `display.scrollOffset` to `session.cursorPosition()`'s row, and `TerminalInputView` does not call `reporter?.onEditorChanged()` when laid out. Checked by inspection; Robolectric's `ShadowInputMethodManager` does not record the `CursorAnchorInfo` passed to `InputMethodManager.updateCursorAnchorInfo`.

## Verification results at review time

- `scripts/check.sh`: passed
- Reproduction tests: `./gradlew --console=plain --no-configuration-cache -I docs/reviews/repro-2026-10-22-compose-sample/review.init.gradle :vtfx-compose:reviewRepro`: 11 tests completed, 11 failed
- Verification not run: emulator e2e (`scripts/e2e_ime.py`) and long fuzzing
