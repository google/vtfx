# Changelog

This file lists the notable changes of each release of vtfx, newest first.
Versions follow Semantic Versioning; before 1.0, any release may break compatibility ([topic 2 of docs/decisions/0087](docs/decisions/0087-public-api.md)).

## 0.7.0 - Unreleased

### Changed

- `vtfx-compose`: vtfx now decides which clicks open links, the same in every app: a tap on a link, or a click of the mouse's primary button on it with Ctrl held (whatever else is held), calls `onLinkClick`, without focusing the terminal or showing the keyboard. Ctrl+click opens the link also while the host asks for the mouse, and the host then gets neither the press nor its release. A mouse click without Ctrl acts as a click off links, and the pointer shows a hand over a link only while Ctrl is held. `TerminalLinkClick` no longer has `pointerType` and `keyboardModifiers`, and `onLinkClick` returns `Unit`; callbacks that return `Boolean` no longer compile.
- `vtfx-compose`: A pinch now zooms into the display, from 1 to 4 times, without resizing the terminal, so the host is told nothing. One finger moves the zoomed display, and the user's input moves it to the cursor. A pinch on a touchpad (Android 14 and later) and Ctrl+wheel zoom about the pointer; while the host asks for the mouse, Ctrl+wheel still goes to the host unless Shift is held. `TerminalState.zoom` and `TerminalState.resetZoom()` read and reset the zoom, which `TerminalState` keeps with the view.
- `vtfx-compose`: A swipe of a finger or a two-finger swipe on a touchpad now goes on as a fling after the fingers are lifted, scrolling as the swipe did. It stops at the end of the scrollback, at a touch, at the user's input, and when the app or the search scrolls.
- `vtfx-compose`: The platform's text loupe now shows over the row under the finger while a long press selects and while a selection handle is dragged.
- `vtfx-compose`: `TerminalState.scrollBy`, `scrollByPages`, `scrollToTop`, and `scrollToBottom` scroll the display from the app, and `isScrolledBack` tells whether it shows the scrollback. On the alternate screen they do nothing.
- `vtfx-compose`: `TerminalStyle` now throws `IllegalArgumentException` for a `fontSize` that is not finite, positive, and in sp. Before, a size of 0 made cells of no size, and the host was told as many columns and rows as the session allows.
- `vtfx-session`: `TerminalSession.clearScrollback()` clears the scrollback from the app, as ED 3 from the host does, without telling the host.

### Fixed

- `vtfx-gate`: Ctrl+` now sends NUL, and Ctrl+{, Ctrl+|, Ctrl+}, and Ctrl+~ send ESC, FS, GS, and RS, as in xterm. Before, they sent the plain character, so Ctrl+` as a tmux prefix typed a backtick.
- `vtfx-input`: `deleteSurroundingText` and `deleteSurroundingTextInCodePoints` now delete before the composing text, as `BaseInputConnection` does, instead of deleting the end of the composing text. An IME that deletes a space before the word it composes now has the space deleted on the host.
- `vtfx-compose`: The IME is now told the new selection after a hardware key that the IME did not consume and after `TerminalSession.sendKey`, so its next edits no longer go to the old cursor position.
- `vtfx-compose`: An IME that connects while the edit buffer holds a selection, as after switching IMEs, is now given that selection instead of a collapsed cursor, so its next character no longer replaces text it did not know was selected.
- `vtfx-core`: When the terminal gets shorter while more rows than the new height are below the cursor, as when the keyboard shows, the screen now starts at the cursor's row and the rows below it are dropped. Before, the cursor landed on another line, which the next output overwrote.
- `vtfx-core`: With autowrap (DECAWM) off, a combining character or regional indicator printed after a character in the last column now joins that character, instead of the one in the column before it.
- `vtfx-core`: A kitty graphics image transmitted without an id or number, which the host can never place or delete, is no longer stored, so it no longer evicts older images that the host can still place.
- `vtfx-compose`: One scroll event now sends at most as many wheel reports or arrow keys as the screen has rows. Before, a device that reported a huge delta made the app send one per notch or line and hang.
- `vtfx-compose`: Turning the mouse wheel by a huge delta no longer overflows and scrolls the other way.
- `vtfx-compose`: A swipe or fling that sends arrow keys or scrolls the scrollback now stops when the host switches between the main and alternate screens, instead of sending arrow keys to a program that just started or scrolling the scrollback after it exited.
- `vtfx-compose`: A `Terminal` recreated after a rotation now shows the bottom when its new size reflows the lines, as promised, instead of some lines of the reflowed scrollback.
- `vtfx-compose`: Copying and the rows exposed to accessibility now drop only trailing spaces, keeping characters such as U+3000 (ideographic space) and U+00A0 that the host wrote at the end of a line.
- `vtfx-compose`: The built-in glyphs (box drawing, block elements, braille, Powerline, and the others drawn by the terminal) and the underlines of links found by patterns are now drawn faint in faint text (SGR 2), like the text next to them.
- `vtfx-compose`: The underline of an OSC 8 hyperlink is now drawn again over a focused block cursor, like the cell's other decorations.
- `vtfx-compose`: The selection menu now hides while a long press makes a new selection, and while the display zooms, moves, or scrolls, instead of staying over the row and pointing at other text.

### Security

- `vtfx-core`: Kitty graphics commands with little or no data no longer allocate far more memory than their size (a 64 KiB buffer for each transmission, and a decompressor for each compressed command), so a host that floods short commands can no longer make the terminal allocate hundreds of times its output.

## 0.6.0 - 2026-11-02

### Changed

- `vtfx-core`: With `AmbiguousWidth.Wide`, private use characters now take one cell instead of two, as `wcwidth()` of glibc counts them in every locale, so shell prompts and tmux status lines with Powerline separators or Nerd Font icons stay in line. vim with `ambiwidth=double` still counts them as two cells unless `setcellwidths()` says otherwise.
- `vtfx-compose`: The terminal now draws these characters itself, as shapes that join across cells, in place of the glyphs of any font, the app's or user's included: braille patterns (U+2800–U+28FF), the separators of Powerline and Powerline Extra (U+E0B0–U+E0BF), the symbols for legacy computing (U+1FB00–U+1FBAF), the progress bar and spinner glyphs of Fira Code (U+EE00–U+EE0B), and the git branch glyphs of flog-symbols (U+F5D0–U+F60D). Before, Android's system fonts had no glyphs for them, so they showed as boxes for missing glyphs.

### Fixed

- `vtfx-compose`: The glyph of a private use character that is wider than its cell, such as an icon of a Nerd Font that is not "Mono", is now scaled down to fit the cell, or the cell and the next one when that one is blank. Before, it covered the next character, and the terminal's bounds cut it in the last column.

## 0.5.0 - 2026-10-29

### Changed

- `vtfx-compose`: `onLinkClick` of `Terminal` now returns whether it took the click. A click that it does not take (`false`) acts as a click off links, which focuses the terminal and shows the keyboard, so that an app can, for example, open links only on Ctrl+click with a mouse and leave other clicks on links to the terminal. Callbacks that return `Unit` no longer compile; return `true` to keep taking every click.

### Fixed

- `vtfx-compose`: A two-finger swipe on a touchpad (Android 14 and later) now scrolls as a swipe of a finger does: it scrolls the scrollback, sends arrow keys on the alternate screen, and is reported as the wheel when the host asks for the mouse. Before, it did nothing.

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
