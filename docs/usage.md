# Embedding guide

This shows how to embed vtfx in an Android app. How to use it without a UI from JVM programs and app tests is in section 8. The KDoc is the canonical source for the contract of each API (threads, when things are called, what null means). A working example is in `vtfx-sample` (`SampleViewModel` and `MainActivity`).

## 1. Dependencies

Apps depend on `vtfx-compose`. `vtfx-compose` transitively exposes the other modules (`vtfx-core`, `vtfx-gate`, `vtfx-gate-android`, `vtfx-session`, `vtfx-font`, `vtfx-font-gate`) for the types the public API uses and for the Android implementations. JVM programs without a UI depend on `vtfx-session` (section 8).

Declarations marked `@InternalVtfxApi` are not public API ([decisions/0016](decisions/0016-public-api.md)). Apps must not opt in.

minSdk is 29 (section 7 of [architecture.md](design/architecture.md)).

The public API assumes use from Kotlin. Use from Java is out of scope (requirement 4.2).

## 2. Creating a session

`TerminalSession` ties together the terminal state and the user's input, and the app owns it. Put it in a `ViewModel` or the like to keep it across configuration changes. `TerminalSession` and `TerminalSessionListener` are in the `com.google.vtfx.session` package.

```kotlin
class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    val session = TerminalSession(
        // Sending to the host. vtfx passes everything it sends to the host through here.
        connection = { bytes -> channel.send(bytes) },
        clipboard = AndroidClipboard(application),
        urlOpener = AndroidUrlOpener(application),
        listener = object : TerminalSessionListener {
            override fun onResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
                // Tell the host with SSH window-change or the PTY's TIOCSWINSZ, pixels included.
                channel.resize(columns, rows, widthPixels, heightPixels)
            }
        },
    )
}
```

- `connection` is the app's connection to the host (`HostConnection`). `TerminalSession` passes it to vtfx-gate right away, and all bytes vtfx sends to the host (keys, IME input, pastes, replies to queries, mouse) are assembled by vtfx-gate and passed to `HostConnection.write` (section 2.1 of [session-and-threads.md](design/session-and-threads.md), section 2 of [gate-policy.md](design/gate-policy.md)).
- `HostConnection.write` must not block, throw, or call back into vtfx; its KDoc says on which threads it is called and why. If sending can block, queue the bytes and send them on another thread. For a host that replies locally right away, pass its output to `feed` in a separate task (`Handler.post` and so on), since calling `feed` within `write` throws `IllegalStateException` (section 1.3 of [session-and-threads.md](design/session-and-threads.md)). When implementing `ClipboardAccess` and `UrlOpener` yourself, follow their KDoc: both are called on any thread, and `UrlOpener.open` returns the failures that the platform documents as a `UrlOpenerResult` instead of throwing (section 5.3 of [gate-policy.md](design/gate-policy.md)).
- If `clipboard` and `urlOpener` are omitted, the clipboard and opening URLs are denied.
- When starting the host's session, set `TERM=xterm-256color` and `COLORTERM=truecolor` (in SSH's pty-req and environment requests, or in the PTY's environment). vtfx supports truecolor SGR and the `xterm-256color` terminfo except for a few features, which section 6 of [emulation.md](design/emulation.md) lists, and ships no terminfo of its own.
- `onResize` has two overloads. Override the one with pixels to pass the size of the text area in pixels to the host as well: programs that show images (the kitty graphics protocol) choose the size of an image from it. The pixels are 0 until a `Terminal` has given the size of its cells. The overload without pixels is enough when the host does not take pixels.
- The kitty graphics protocol is on by default, with 32 MiB for the decoded pixels of the images (`TerminalLimits.maxImageBytes`). Images are sent in the host output, so only direct data works, which is what programs send over SSH. Set `maxImageBytes` to 0 to turn the protocol off (section 9 of [emulation.md](design/emulation.md)).
- The policy for requests with external effects can be changed with `GatePolicy`. The default is the safe side (OSC 52 reads and writes are denied, writes are limited in length when allowed, and the URLs that can be opened are http and https; sections 4 and 5 of [gate-policy.md](design/gate-policy.md)). To relax it, the app specifies it explicitly: for example, `GatePolicy(allowClipboardWrite = true)` lets remote copy in tmux and vim set the clipboard.
- Match the width of East Asian Ambiguous (`ambiguousWidth`) with the host's setting (vim's `ambiwidth` and so on) (section 7 of [emulation.md](design/emulation.md)).
- The constructor arguments (`ambiguousWidth`, `limits`, `policy`, `clipboard`, `urlOpener`, `listener`) cannot be changed later. When the user changes the width, the number of scrollback lines, or permission for OSC 52 in a settings screen, create a new session. The screen and scrollback are not carried over, so applying it from the next connection is natural.
- The session lives longer than the `Activity`, so do not refer to the `Activity` or `View`s from `listener`. Copy notifications into the `ViewModel`'s state, and have the UI read it (e.g. `SampleViewModel` in `vtfx-sample`).

## 3. Passing host output

```kotlin
// Can be called from any thread. Calls are serialized.
session.feed(bytes, 0, length)
```

`TerminalSession` is thread-safe, and any operation may be called from any thread. `TerminalSessionListener` is called on the thread of the triggering call, without holding the session's lock. Bells and titles come on the thread that called `feed`, and `onResize` on the main thread, where `Terminal` changes the session's size. To update the UI, the app forwards to the main thread (section 2.5 of [session-and-threads.md](design/session-and-threads.md)). Notifications of the size, title, and modifiers report the latest value, so even when changed from multiple threads, the last value delivered is the current value. In that case, it may be delivered on a different thread from the one that made the change.

## 4. Displaying

```kotlin
Terminal(
    viewModel.session,
    Modifier.fillMaxSize(),
    style = TerminalStyle(fontSize = 14.sp),
    onLinkClick = { click -> confirmOpening(click.link.url) },
)
```

- The number of columns and rows is determined by the size of the Composable, and `onResize` is called when it changes. Give `Terminal` a size with its `modifier` (`fillMaxSize()`, `weight(1f)`, and so on). Where the constraints leave the size free, it takes 80 × 24 cells, or as much of that as the constraints allow, as `Text` sizes to its content. Laid out with no width or height, it logs a warning (tag `vtfx`) and leaves the session's size as it was (section 10.1 of [compose-input.md](design/compose-input.md)). When the size changes continuously, as during the IME animation, it is called only once, after it has settled for 150 ms (section 3.2 of [session-and-threads.md](design/session-and-threads.md)).
- Until displayed, the size is the `columns` and `rows` of `TerminalSession` (80 × 24 by default). When `Terminal` is first laid out, it resizes the session at once, and `onResize` is called with the size that fits even if it is the same as the session's; after that, only when the size changes. So the host can either start with the session's size and follow `onResize`, or wait for the first `onResize` and start with that size, so that it does not start at a size it is resized from right away.
- A tap shows the keyboard. A vertical swipe scrolls back through scrollback. A long press selects and shows a menu with Copy, Paste, and Select all (sections 2 and 4 of [compose-input.md](design/compose-input.md)).
- With a mouse (Chromebooks, DeX, tablets with a mouse): the wheel scrolls; a drag selects, a double click selects a word, a triple click a line, and Shift+click extends the selection; a right click shows the same menu, or calls `onContextClick` if the app passes it (for its own menu, with the position of the click). When the host asks for mouse reports (vim's `mouse=a`, tmux, htop), clicks, the wheel, and motion go to the host instead; holding Shift gives the mouse back to the terminal, as in xterm, and so does scrolling back: while the display shows the scrollback, the mouse and touches are the terminal's, until the display is back at the bottom (section 3 of [compose-input.md](design/compose-input.md)).
- To move focus to the terminal from the app, add `Modifier.focusRequester(requester)` to the `modifier` of `Terminal` and call `requester.requestFocus()`. Input from a physical keyboard then reaches the terminal. There is no public means of showing the software keyboard yet (a tap shows it).
- The appearance is changed with `TerminalStyle` (colors, font, the locale for Han glyphs, cursor shape, and `padding`). `padding` is space between the edges of `Terminal` and the grid, drawn in the terminal's background color; `start` and `end` follow the layout direction, and the columns and rows fit inside it. Use it rather than `Modifier.padding`, which would leave the space in the colors behind `Terminal`. `TerminalColors.cursorText` sets the color of the text under a block cursor; by default it is the background color.
- Underline styles (SGR 4:1 to 4:5: single, double, curly, dotted, dashed), underline colors (SGR 58), and overline (SGR 53) are drawn. A program that reads the screen without the UI gets the underline color from `LineSnapshot.underlineColorAt` (section 8 of [emulation.md](design/emulation.md)).

### Selection and copying

For the app's own Copy command or shortcut (a menu item, Ctrl+Shift+C), pass a `TerminalState` to `Terminal` (section 5 of [compose-input.md](design/compose-input.md)).

```kotlin
val state = rememberTerminalState()
Terminal(viewModel.session, Modifier.fillMaxSize(), state = state)
// In the app's menu:
DropdownMenuItem(
    text = { Text("Copy") },
    enabled = state.hasSelection,
    onClick = { state.copySelection() },
)
```

- `hasSelection` is Compose state: what reads it is recomposed when the user selects or the selection goes away. The selection is dropped whenever the display changes (output, scrolling, typing, resizing).
- `copySelection()` copies to the session's `clipboard` and keeps the selection. It returns `false` if there is no selection or no clipboard.
- `selectAll()` selects the scrollback and the screen (only the screen on the alternate screen). Copying it copies at most the newest 10,000 lines, cut to 256 Ki characters, so that the system clipboard can take it.
- `clearSelection()` drops the selection.
- Use the state on the main thread, with one `Terminal` at a time. While it is not passed to a `Terminal` in the composition, it has no selection and its functions do nothing.

### Search

The app provides the search field; the terminal finds, highlights, and scrolls to the matches through the same `TerminalState` (section 13 of [compose-input.md](design/compose-input.md)).

```kotlin
var query by remember { mutableStateOf("") }
TextField(
    value = query,
    onValueChange = {
        query = it
        state.search(it, ignoreCase = true) // As the user types; empty text ends the search.
    },
)
val result = state.searchResult
if (result != null) {
    val count = if (result.isMatchCountCapped) "${result.matchCount}+" else "${result.matchCount}"
    Text("${result.currentMatchIndex?.plus(1) ?: 0} / $count")
}
IconButton(onClick = { state.findPrevious() }) { /* Up */ }
IconButton(onClick = { state.findNext() }) { /* Down */ }
```

- The search starts from the bottom and keeps the current match while the text still matches it. `findPrevious()` moves up and `findNext()` down, going round at the ends; each scrolls the terminal to the current match.
- The text is matched as it is (no regular expressions), within a line, through the rows that autowrap joins. Concealed text is not matched, and only the newest 1,000 matches are kept.
- The search follows the output and stays until `clearSearch()` or empty text, or until the `Terminal` leaves the composition. `searchResult` is Compose state.
- The highlight colors are `TerminalColors.searchMatch` and `searchCurrentMatch`.
- Keys such as Ctrl+Shift+F, Enter, and Shift+Enter are the app's: handle them with `onPreImeKeyEvent` and the search field.

### Fonts

`TerminalStyle.font` takes system fonts (`TerminalFont.System("monospace")` and so on), fonts bundled in the app's `res/font` (`TerminalFont.Resources`), and fonts that users choose, sanitized (`TerminalFont.Sanitized`). Use a monospace font (the cell size is measured from the width of `M`).

```kotlin
TerminalStyle(font = TerminalFont.Resources(R.font.hackgen_regular, bold = R.font.hackgen_bold))
```

Fonts are parsed by native code inside the app's process, so a malicious font file can take over the app (and the keys and connections it holds). So a font file that a user chose is never drawn as it is: `vtfx-font`, which `vtfx-compose` exposes, reads it in Kotlin and writes a new font in a simple form, which `vtfx-font-gate` checks, and only that reaches native code (sections 2 and 4 to 6 of [fonts.md](design/fonts.md)). Files, URIs, and downloaded fonts are not accepted otherwise (section 1 of [fonts.md](design/fonts.md)).

```kotlin
// When the user imports a font, on a background thread: it can take seconds for a CJK font.
val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
val faces = FontFile.inspect(bytes, FontLimits())  // one per face of a collection, with names
val font = FontFile.sanitize(bytes, faces.first { it.isSupported }.index, FontLimits())
File(filesDir, "font.ttf").writeBytes(font.toByteArray())  // save the sanitized font, not the file

// Later, loading a saved font checks it again but does not convert it.
val saved = SanitizedFont.fromCanonical(File(filesDir, "font.ttf").readBytes(), FontLimits())
TerminalStyle(font = TerminalFont.Sanitized(listOf(SanitizedFamily(latin), SanitizedFamily(saved))))
```

- TrueType and OpenType fonts with TrueType or CFF outlines, and collections of them (TTC, OTC), are read; WOFF, CFF2, and bitmap fonts are not. `InvalidFontException.kind` tells why a font was rejected.
- What a terminal does not need, such as hinting and OpenType layout, is dropped; the KDoc of `SanitizedFont` lists it. CFF outlines are converted to TrueType ones.
- For CJK, choose the face of the region (for example the JP face of Noto Sans CJK), since the regional forms of the other faces come from OpenType layout, which is dropped.
- `TerminalFont.Sanitized` takes families in order, such as a Latin font and then a CJK font; characters in none of them come from the system's monospace font. `SanitizedFont.widthClass` tells whether a font is monospaced, or monospaced with double-width CJK characters.
- `SanitizedFont.fromCanonical` checks the bytes again, so the place where the app keeps them does not need to be trusted.

### Links

Link clicks are passed to `onLinkClick` as `TerminalLinkClick`: the `TerminalLink` (`click.link`), and how the user clicked it (below). There are two kinds of links, both shown underlined (section 6.1 of [compose-input.md](design/compose-input.md)). For both, **show the destination (`link.url`) to the user and get confirmation before** calling `session.openUrl(link.url)` (requirement 6.1).

- `TerminalLink.Explicit`: an OSC 8 hyperlink. The host decides both the link text (`link.text`) and the destination, so they do not necessarily match. Showing the text and the destination side by side makes discrepancies easier to notice.
- `TerminalLink.Matched`: text that matched `linkPatterns` (by default `LinkPattern.Urls`, the `http` and `https` URLs written in the text). The matched text can still differ from what the user sees; the KDoc of `Terminal` says how.

`openUrl` returns an `OpenUrlResult`, a closed type that apps can match exhaustively; its KDoc says what each result means (section 5.3 of [gate-policy.md](design/gate-policy.md)). Tell the user when the URL was not opened, so that a confirmed link does not seem to do nothing.

```kotlin
val failure = when (session.openUrl(url)) {
    OpenUrlResult.Opened -> null
    OpenUrlResult.NotAllowed -> "This kind of link is not allowed."
    OpenUrlResult.NoApp -> "No app can open this link."
    OpenUrlResult.Refused -> "The system did not let the app for this link open."
}
```

Whether vtfx would try to open a URL (anything but `NotAllowed`) can be known beforehand with `session.canOpenUrl(url)`, so there is no need to show a confirmation dialog for URLs that vtfx does not allow. `GatePolicy.urlSchemes` applies equally to OSC 8 links and pattern links. Which text becomes a link is decided by `linkPatterns`, so adding a scheme to `urlSchemes` does not make URLs with that scheme detected in the text. If `onLinkClick` is not passed, links cannot be tapped, and `linkPatterns` are not matched.

`click.pointerType` is the kind of pointer (`PointerType.Touch` for a tap, `PointerType.Mouse` for a mouse click), and `click.keyboardModifiers` the modifier keys held, so that an app can, for example, open links only on Ctrl+click with a mouse, as desktop terminals do. When no pointer clicked, they are `PointerType.Unknown` and no modifiers.

```kotlin
onLinkClick = { click ->
    val mouse = click.pointerType == PointerType.Mouse
    if (!mouse || click.keyboardModifiers.isCtrlPressed) confirmOpening(click.link.url)
}
```

Apps can add their own links with a regular expression and a URL template, in which `{0}` is the whole match and `{n}` the nth group.

```kotlin
val crbug = LinkPattern(Regex("""\bcrbug/(\d+)"""), "https://crbug.com/{1}")
Terminal(viewModel.session, linkPatterns = listOf(LinkPattern.Urls, crbug), onLinkClick = ...)
```

- Values inserted into the template are percent-encoded, so that text from the host cannot change the structure of the URL. To include path separators, write a group for each part (e.g. `https://github.com/{1}/{2}`). The KDoc of `LinkPattern` has the details.
- **vtfx sets no limit on the effort of matching**: the regular expressions run on the main thread against text the host decides, every time the display changes, and the host can make a pattern with nested or overlapping repetition freeze the screen. Follow the KDoc of `LinkPattern` for patterns to use and to avoid (section 6.3 of [compose-input.md](design/compose-input.md)).
- Where links overlap, an OSC 8 link takes precedence over the patterns, and an earlier pattern over a later one; the KDoc of `Terminal` has the rules.

### Intercepting keys

For app shortcuts (Ctrl+Shift+C and Ctrl+Shift+V for copy and paste, switching tabs, and so on), pass `onPreImeKeyEvent` to `Terminal`. It is called with each hardware key event while the terminal has the focus, before the IME and the session see it: presses, repeated presses (`nativeKeyEvent.repeatCount > 0`), and releases. Return `true` to consume the event; it then reaches neither the IME nor the session. A key consumed on press should also be consumed on release. Return `false` for the keys the app does not use, including system keys such as Back. When multiple terminals are displayed, only the focused terminal's callback is called.

```kotlin
Terminal(
    viewModel.session,
    onPreImeKeyEvent = { event ->
        val paste = event.isCtrlPressed && event.isShiftPressed && event.key == Key.V
        if (paste && event.type == KeyEventType.KeyDown) viewModel.session.pasteFromClipboard()
        paste
    },
)
```

`Modifier.onPreviewKeyEvent` on an ancestor of `Terminal` does not work for this: it sees only the keys the IME passed on, and IMEs such as Gboard take the keys of a physical keyboard and send them through the IME connection. Keys from a software keyboard and keys sent with `session.sendKey` do not go through `onPreImeKeyEvent` either (section 7.2 of [compose-input.md](design/compose-input.md)).

## 5. Extra key bar

A bar for keys not on the software keyboard (Esc, Tab, arrows, Ctrl, and so on) is not included in the library. The app builds it with the following APIs (e.g. `KeyBar` in `vtfx-sample`). The key types (`KeyStroke`, `TerminalKey`, `KeyModifiers`) are in the `com.google.vtfx.core` package.

- `session.sendKey(KeyStroke.Special(TerminalKey.Escape))`: sends a key.
- `session.stickyModifiers = KeyModifiers.of(ctrl = true)`: applies modifiers to the next typed key or character. They are cleared after one use. Arming them sends the composing text first, without them. Changes, including clearing, are notified with `TerminalSessionListener.onStickyModifiersChanged`, so follow the key bar's display with it (e.g. `SampleViewModel` in `vtfx-sample`).
- `session.sendText(text)`, `session.paste(text)`: input of text and pastes. `sendText` types the text, with each line break as Enter. A paste is bracketed if the host has enabled bracketed paste, and control characters are removed from both. Send text from elsewhere, such as the clipboard, with `paste`; text of several lines that the IME commits, such as Gboard's clipboard suggestion, is pasted too.

### Direct input

Some IMEs hold English words as composing text until a space and do not send them to the host. This is a problem for apps that act on each key, like vim's normal mode, so `session.directInput = true` asks the IME for character input without predictive text. Many IMEs do not offer Japanese or Korean in this case, so let the user switch it (e.g. "Direct" on the extra key bar of `vtfx-sample`; section 13 of [ime-contract.md](design/ime-contract.md)). Gboard's English sends one character at a time even with the default.

## 6. How far terminal content is shown

Host output can contain secrets (displayed API keys, keys, and so on). vtfx handles terminal content as follows (section 11 of [compose-input.md](design/compose-input.md)).

- **Accessibility services**: Each displayed row is exposed for screen readers. However, rows are marked as sensitive data, so they are visible only to services that declare `isAccessibilityTool` (TalkBack and so on), and not to other accessibility services. The mark works on Android 14 (API 34) and later. On API 29 to 33, the platform cannot tell a tool from another service, and the rows are visible to every accessibility service ([decisions/0039](decisions/0039-minsdk-29.md)). Apps that show secrets can take this into account, for example by setting their own minSdk to 34.
- **Content Capture** (the mechanism by which OS intelligence features receive the text on the screen): vtfx does not stop it. Compose's Content Capture does not look at the sensitive mark, and the means of stopping it affects the whole process, so the app decides. Apps that handle secrets can set `ContentCaptureManager.isEnabled = false` (`androidx.compose.ui.contentcapture`; `@ExperimentalComposeUiApi` in Compose UI 1.12).
- **Screen sharing and screen recording**: vtfx does not hide content. Apps that want to hide it add `Modifier.sensitiveContent()` to the `modifier` of `Terminal` (Android 15 and later; the terminal part is hidden while sharing).

## 7. Others

- `session.title`: the window title set by the host. It may also go back to a title the host had saved (XTWINOPS 22, 23), such as when vim exits. It is an untrusted string, though at most 1024 UTF-16 units long and without control, line separator, and bidirectional formatting characters ([decisions/0063](decisions/0063-window-title-limits.md)). Changes are notified with `TerminalSessionListener.onTitleChanged`, on the thread that called `feed`. Of the titles set in one `feed`, only the last one is notified.
- `TerminalSessionListener.onBell`: the bell. Many BELs contained in one `feed` are batched into one notification on the thread that called `feed`. An app that passes bells or titles to the main thread should keep at most one of each waiting there, so that its queue does not grow with the host's output.
- `session.snapshot()`: a copy of the screen. Can be read on any thread.

## 8. Using without a UI

`TerminalSession` is in the JVM library `vtfx-session` and works without Android and Compose (section 2.1 of [session-and-threads.md](design/session-and-threads.md)). JVM programs can emulate a terminal, and apps can use the same session as production in their own unit tests (JUnit without Robolectric).

```kotlin
val sent = ByteArrayOutputStream()
val session = TerminalSession(connection = { sent.write(it) })
session.feed("$ ".toByteArray())
assertEquals("$", session.snapshot().line(0).text().trim())
session.sendText("ls\r")
assertEquals("ls\r", sent.toString())
```

- There are no timers and no thread assumptions, so tests can be written synchronously. Listeners are also called within the call.
- There is no display, so change the size with `resize(columns, rows)`. `onResize` is called on the first `resize`, even if the size is unchanged, and then when it changes. To answer the host's queries for pixel sizes and to size images without a size in cells, also give the size of a cell in pixels (`resize(columns, rows, cellWidth, cellHeight)`); see the KDoc of `resize`.
- Lines that scrolled off the screen can be read with `session.snapshot(scrollOffset)`. The number of lines that can be scrolled back is `session.scrollbackSize` (0 on the alternate screen).
- Color queries (OSC 10, 11, 12, 4) are not answered. Once `Terminal` displays the session, they are answered with its colors.
- Send keys and text with `sendKey`, `sendText`, and `paste`.

## 9. Related documents

- The contract of the interaction with the IME: [design/ime-contract.md](design/ime-contract.md)
- Terminal emulation (supported escape sequences, TERM and terminfo, character widths, images, resource limits): [design/emulation.md](design/emulation.md)
- Interaction in the Compose UI: [design/compose-input.md](design/compose-input.md)
- Threads, the session, resize, and lifecycle: [design/session-and-threads.md](design/session-and-threads.md)
- The policy of the gate: [design/gate-policy.md](design/gate-policy.md)
- Fonts: [design/fonts.md](design/fonts.md)
