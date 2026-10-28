# Changelog

This file lists the notable changes of each release of vtfx, newest first.
Versions follow Semantic Versioning; before 1.0, any release may break compatibility ([topic 2 of docs/decisions/0087](docs/decisions/0087-public-api.md)).

## 0.4.0 - 2026-10-28

### Changed

- `vtfx-compose`: `TerminalState` now keeps the view of a terminal (the lines scrolled to, the search query, and the current match) while the `Terminal` is out of the composition, as with tabs or Navigation, and the terminal shows the same lines and match when it comes back with the same session, and the bottom otherwise. `rememberTerminalState()` saves the view with the instance state, so that it also survives configuration changes. The selection is not kept.
- `vtfx-compose`: The colors that the host sets with OSC 4, 10, 11, and 12 (the palette, the default foreground and background, and the cursor color) are now drawn in place of the style's colors, in the cells and the padding, until the host resets them with OSC 104 and 110 to 112. Before, the settings were ignored. Colors are read in the `rgb:` and `#` forms; X11 color names are ignored. DECSTR and RIS reset only the palette, as in xterm.
- `vtfx-gate`: Replies to color queries (OSC 4, 10, 11, and 12) report the colors that the host set, and the display's colors only for the others.
- `vtfx-compose`: A selection made by touch now shows a handle at each end, which can be dragged to move that end by cells.

### Fixed

- `vtfx-compose`: The terminal now asks the IME for no suggestions (`TYPE_TEXT_FLAG_NO_SUGGESTIONS`) also outside direct input, so Gboard no longer autocorrects a word that has already reached the host, as when typing "unicode" and Enter ran "Unicode". Japanese conversion keeps its candidates.

## 0.3.0 - 2026-10-27

### Changed

- `vtfx-core`: Faster interpretation of host output on Android's runtime (plain-text runs, scrollback line compaction, UTF-8 decoding, SGR, and CSI and C0 parsing).
- `vtfx-compose`: The selection now stays on its text across host output and scrolling into or through the scrollback, instead of being dropped whenever the display changes. It is dropped when the selected text itself changes, when lines are renumbered (resizing, clearing the scrollback, switching screens), when all of its lines leave the scrollback (cutting off only the leading lines that left otherwise), on user input, or on a tap or click.
- `vtfx-compose`: Dragging after a double click or a long press now extends the selection by words, dragging after a triple click extends it by lines, and Shift+click (and dragging after it) extends by the unit that created the selection, instead of always extending cell by cell.

### Fixed

- `vtfx-core`: ED (0, 1, 2), EL (0, 1, 2), DECSED (0, 1, 2), and DECSEL (0, 1, 2) now cancel a pending wrap, so a character printed right after erasing at the right margin is written into the last column instead of wrapping to the next row.
- `vtfx-core`: With autowrap (DECAWM) off, printing or repeating (`REP` in insert mode) a wide character at the last column now drops it instead of moving left and overwriting the preceding column.
- `vtfx-compose`: A mouse drag or a drag after a long press no longer stops selecting after pointer moves within the same cell.
- `vtfx-input`: Pressing a key that types no characters (such as an arrow key on a physical keyboard) when nothing has been typed since the last reset no longer restarts the IME, so Gboard's toolbar for physical keyboards no longer flickers on each press.

## 0.2.0 - 2026-10-26

### Changed

- `vtfx-core`: The window titles saved with XTWINOPS 22 are now limited only to 10 entries. Before, their total length was also limited to `TerminalLimits.maxStringLength`, so an app that set that limit below 10240 lost titles that programs such as vim restore at exit.

### Fixed

- `vtfx-core`: A non-ASCII character in a malformed control sequence or in a DCS introducer now ends the sequence. Before, CJK text after such a sequence was swallowed up to the next ASCII letter, or all output up to the string terminator.
- `vtfx-core`: Overwriting, erasing, deleting, or inserting at one half of a wide character now erases the other half fully, with the background of the operation, instead of leaving its hyperlink, underline color, and old style.
- `vtfx-core`: Blank cells erased with a background other than the line end's (by ECH, EL 1, ED 1, ICH, or DCH, for example) keep that background when the line moves into the scrollback.
- `vtfx-core`: Search no longer loses matches in the scrollback when the screen holds many matches, and finds them again when the screen changes, with `TerminalSearchResult.isMatchCountCapped` staying correct.
- `vtfx-core`: Search finds the right matches when the first rows of a wrapped line leave the scrollback, instead of missing matches that overlapped the dropped rows' matches.
- `vtfx-core`: Replacing a kitty graphics image by its id when the images are at the memory or count limit no longer evicts an unrelated older image.
- `vtfx-core`: Placing a kitty graphics image taller than the scrolling region now scrolls by all of its rows, so the image no longer reaches below the bottom and the cursor ends on its last row.
- `vtfx-core`: SU, SD, IL, and DL that scroll the cursor's row now reset the IME's input as a scrolling line feed does, so the IME no longer keeps the text of a line that is gone.
- `vtfx-core`: On a screen one column wide, a wide character, which cannot fit, is now dropped without first wrapping and pushing a line into the scrollback.
- `vtfx-compose`: A display that is detached and attached again while output from another thread is waiting no longer stops redrawing; the timeout of synchronized output (mode 2026) had the same problem.
- `vtfx-compose`: Clicking an OSC 8 hyperlink that autowrap split before a wide character now reports the whole link text instead of only the clicked row's part.
- `vtfx-compose`: Word selection by double click or long press now treats both halves of a blank wide character (U+3000 IDEOGRAPHIC SPACE, or concealed text) as blank, so it no longer takes that character into the word next to it.
- `vtfx-compose`: The focused block cursor over a box-drawing character or block element now draws it as geometry filling its cells, as the grid does, instead of with the font's glyph.
- `vtfx-compose`: The terminal's input view stays bound to its session when it leaves the window and comes back, as when Compose moves it within the window, instead of losing the session and ignoring the IME and keys.
- `vtfx-compose`: A primary-button mouse click that the host receives now drops the selection, as a tap does.
- `vtfx-compose`: Only taps in the inner padding focus the terminal, and they drop the selection; a drag or long press in the padding no longer hides the selection menu or shows the keyboard.
- `vtfx-compose`: `TerminalFont.Sanitized` keeps a copy of its list of families, so changing the list passed to it afterwards has no effect.
- `vtfx-font`: CFF fonts whose `FontMatrix` values together do not scale by 1/`unitsPerEm` (for example, no `FontMatrix` with a `unitsPerEm` other than 1000) are now rejected as unsupported instead of being drawn at the wrong size.
- `vtfx-input`: Setting `TerminalSession.directInput` now always restarts the IME, so it takes effect also on a fresh session and right after Enter or Esc.
- `vtfx-session`: `TerminalSession.feed` now throws `IllegalArgumentException` for an out-of-bounds range whose offset plus length overflows `Int`, instead of silently ignoring the call.
- `vtfx-session`: On a focus loss, input that the IME had not finished sending reaches the host before the focus-out report (mode 1004), not after it.
- `vtfx-session`: A mouse press that is not reported to the host, because the host did not ask for it or the encoding cannot express its position, no longer commits the composing text and sends it to the host.
- `vtfx-session`: When a mouse press, a focus loss, or host output that moves the cursor to another line sends the composing text to the host, displays now treat it as user input (they stop drawing the composing text, drop the selection, and leave the scrollback) instead of waiting for the next edit. Switching `directInput` alone no longer counts as user input.
- `vtfx-session`: `TerminalSessionListener` is told of changes to the sticky modifiers and the title only on the thread that made the change, and only when something changed.

### Security

- `vtfx-gate`: Copying the user's selection to the clipboard now drops control characters other than tab, line feed, and carriage return, and bidirectional formatting characters, which OSC 52 writes from the host already may not contain.
- `vtfx-gate`: Line breaks now reach the host only as Enter or inside a paste: the gate keeps the clipboard text of the user's paste to itself and sends it as one paste, and refuses typed text that contains a line break. The bytes sent for typing and pasting do not change.

## 0.1.0 - 2026-10-23

The first release.
