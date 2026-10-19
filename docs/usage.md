# Embedding guide

This shows how to embed vtfx in an Android app. How to use it without a UI from JVM programs and app tests is in section 8. The KDoc is the canonical source for the contract of each API (threads, when things are called, what null means). A working example is in `vtfx-sample` (`SampleViewModel` and `MainActivity`).

## 1. Dependencies

Apps depend on `vtfx-compose`. `vtfx-compose` transitively exposes the other modules (`vtfx-core`, `vtfx-gate`, `vtfx-gate-android`, `vtfx-session`) for the types the public API uses and for the Android implementations. JVM programs without a UI depend on `vtfx-session` (section 8).

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

- `connection` is the app's connection to the host (`HostConnection`). `TerminalSession` passes it to vtfx-gate right away, and all bytes vtfx sends to the host (keys, IME input, pastes, replies to queries, mouse) are assembled by vtfx-gate and passed to `HostConnection.write` ([decisions/0027](decisions/0027-session-takes-connection.md)).
- `HostConnection.write` is called one call at a time in order, from the thread that called the operation for user input (the main thread for input from `Terminal`), and from the thread that called `feed` for replies to host queries. vtfx holds no locks other than the one that serializes the calls. It must not block. If sending can block, queue it and send on another thread. On Android, user input arrives on the main thread, and replies to queries are sent within `feed`, so blocking also stops the thread reading host output. It must not throw. The app handles failures such as a dropped connection. Do not call vtfx from within `write`. Even for a host that replies locally right away, calling `feed` within `write` causes `IllegalStateException`, so pass the output in a separate task (`Handler.post` and so on) ([decisions/0024](decisions/0024-thread-conventions.md)). When implementing `ClipboardAccess` and `UrlOpener` yourself, also make them work when called from any thread.
- If `clipboard` and `urlOpener` are omitted, the clipboard and opening URLs are denied.
- `onResize` has two overloads. Override the one with pixels to pass the size of the text area in pixels to the host as well: programs that show images (the kitty graphics protocol) choose the size of an image from it. The pixels are 0 until a `Terminal` has given the size of its cells. The overload without pixels is enough when the host does not take pixels.
- The kitty graphics protocol is on by default, with 32 MiB for the decoded pixels of the images (`TerminalLimits.maxImageBytes`). Images are sent in the host output, so only direct data works, which is what programs send over SSH. Set `maxImageBytes` to 0 to turn the protocol off ([decisions/0038](decisions/0038-kitty-graphics.md)).
- The policy for requests with external effects can be changed with `GatePolicy`. The default is the safe side (OSC 52 reads are denied, writes are allowed with a limit, and the URLs that can be opened are http and https; [decisions/0006](decisions/0006-gate-defaults.md)). To relax it, the app specifies it explicitly.
- Match the width of East Asian Ambiguous (`ambiguousWidth`) with the host's setting (vim's `ambiwidth` and so on) ([decisions/0011](decisions/0011-unicode.md)).
- The constructor arguments (`ambiguousWidth`, `limits`, `policy`, `clipboard`, `urlOpener`, `listener`) cannot be changed later. When the user changes the width, the number of scrollback lines, or permission for OSC 52 in a settings screen, create a new session. The screen and scrollback are not carried over, so applying it from the next connection is natural.
- The session lives longer than the `Activity`, so do not refer to the `Activity` or `View`s from `listener`. Copy notifications into the `ViewModel`'s state, and have the UI read it (e.g. `SampleViewModel` in `vtfx-sample`).

## 3. Passing host output

```kotlin
// Can be called from any thread. Calls are serialized.
session.feed(bytes, 0, length)
```

`TerminalSession` is thread-safe, and any operation may be called from any thread. `TerminalSessionListener` is called on the thread of the triggering call, without holding the session's lock. Bells and titles come on the thread that called `feed`, and `onResize` on the main thread, where `Terminal` changes the session's size. To update the UI, the app forwards to the main thread ([decisions/0026](decisions/0026-session-module.md)). Notifications of the size, title, and modifiers report the latest value, so even when changed from multiple threads, the last value delivered is the current value. In that case, it may be delivered on a different thread from the one that made the change.

## 4. Displaying

```kotlin
Terminal(
    viewModel.session,
    Modifier.fillMaxSize(),
    style = TerminalStyle(fontSize = 14.sp),
    onLinkClick = { click -> confirmOpening(click.link.url) },
)
```

- The number of columns and rows is determined by the size of the Composable, and `onResize` is called when it changes. Give `Terminal` a size with its `modifier` (`fillMaxSize()`, `weight(1f)`, and so on). Where the constraints leave the size free, it takes 80 × 24 cells, or as much of that as the constraints allow, as `Text` sizes to its content. Laid out with no width or height, it logs a warning (tag `vtfx`) and leaves the session's size as it was ([decisions/0032](decisions/0032-keys-and-layout.md)). When the size changes continuously, as during the IME animation, it is called only once, after it has settled for 150 ms ([decisions/0013](decisions/0013-resize-and-lifecycle.md)).
- Until displayed, the size is the `columns` and `rows` of `TerminalSession` (80 × 24 by default). When `Terminal` is first laid out, it resizes the session at once, and `onResize` is called with the size that fits even if it is the same as the session's; after that, only when the size changes. So the host can either start with the session's size and follow `onResize`, or wait for the first `onResize` and start with that size, so that it does not start at a size it is resized from right away.
- A tap shows the keyboard. A vertical swipe scrolls back through scrollback. A long press selects and shows a menu with Copy, Paste, and Select all ([decisions/0012](decisions/0012-touch-and-selection.md)).
- With a mouse (Chromebooks, DeX, tablets with a mouse): the wheel scrolls; a drag selects, a double click selects a word, a triple click a line, and Shift+click extends the selection; a right click shows the same menu, or calls `onContextClick` if the app passes it (for its own menu, with the position of the click). When the host asks for mouse reports (vim's `mouse=a`, tmux, htop), clicks, the wheel, and motion go to the host instead; holding Shift gives the mouse back to the terminal, as in xterm, and so does scrolling back: while the display shows the scrollback, the mouse and touches are the terminal's, until the display is back at the bottom ([decisions/0031](decisions/0031-desktop-pointer-and-terminal-state.md)).
- To move focus to the terminal from the app, add `Modifier.focusRequester(requester)` to the `modifier` of `Terminal` and call `requester.requestFocus()`. Input from a physical keyboard then reaches the terminal. There is no public means of showing the software keyboard yet (a tap shows it).
- The appearance is changed with `TerminalStyle` (colors, font, the locale for Han glyphs, cursor shape, and `padding`). `padding` is space between the edges of `Terminal` and the grid, drawn in the terminal's background color; `start` and `end` follow the layout direction, and the columns and rows fit inside it. Use it rather than `Modifier.padding`, which would leave the space in the colors behind `Terminal`. `TerminalColors.cursorText` sets the color of the text under a block cursor; by default it is the background color.
- Underline styles (SGR 4:1 to 4:5: single, double, curly, dotted, dashed), underline colors (SGR 58), and overline (SGR 53) are drawn. A program that reads the screen without the UI gets the underline color from `LineSnapshot.underlineColorAt` ([decisions/0030](decisions/0030-underline-color-and-decorations.md)).

### Selection and copying

For the app's own Copy command or shortcut (a menu item, Ctrl+Shift+C), pass a `TerminalState` to `Terminal` ([decisions/0031](decisions/0031-desktop-pointer-and-terminal-state.md)).

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

### Fonts

`TerminalStyle.font` takes system fonts (`TerminalFont.System("monospace")` and so on), fonts bundled in the app's `res/font` (`TerminalFont.Resources`), and fonts that users choose, sanitized (`TerminalFont.Sanitized`). Use a monospace font (the cell size is measured from the width of `M`).

```kotlin
TerminalStyle(font = TerminalFont.Resources(R.font.hackgen_regular, bold = R.font.hackgen_bold))
```

Fonts are parsed by native code inside the app's process, so a malicious font file can take over the app (and the keys and connections it holds). So a font file that a user chose is never drawn as it is: `vtfx-font`, which `vtfx-compose` exposes, reads it in Kotlin and writes a new font in a simple form that it checks, and only that reaches native code ([decisions/0040](decisions/0040-sanitized-user-fonts.md)). Files, URIs, and downloaded fonts are not accepted otherwise ([decisions/0020](decisions/0020-fonts.md)).

```kotlin
// When the user imports a font, on a background thread: it can take seconds for a CJK font.
val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
val faces = FontFile.inspect(bytes, FontLimits())  // one per face of a collection, with names
val font = SanitizedFont.sanitize(bytes, faces.first { it.isSupported }.index, FontLimits())
File(filesDir, "font.ttf").writeBytes(font.toByteArray())  // save the sanitized font, not the file

// Later, loading a saved font checks it again but does not convert it.
val saved = SanitizedFont.fromCanonical(File(filesDir, "font.ttf").readBytes(), FontLimits())
TerminalStyle(font = TerminalFont.Sanitized(listOf(SanitizedFamily(latin), SanitizedFamily(saved))))
```

- TrueType and OpenType fonts with TrueType or CFF outlines, and collections of them (TTC, OTC), are read; WOFF, CFF2, and bitmap fonts are not. `InvalidFontException.kind` tells why a font was rejected.
- What a terminal does not need is dropped: hinting, ligatures and other OpenType layout, color glyphs, and the font's names. A variable font becomes its default instance. CFF outlines are converted to TrueType ones, within 1/1000 of the em. Variation sequences (such as the forms of kanji in names) are kept.
- For CJK, choose the face of the region (for example the JP face of Noto Sans CJK), since the regional forms of the other faces come from OpenType layout, which is dropped.
- `TerminalFont.Sanitized` takes families in order, such as a Latin font and then a CJK font; characters in none of them come from the system's monospace font. `SanitizedFont.widthClass` tells whether a font is monospaced, or monospaced with double-width CJK characters.
- `SanitizedFont.fromCanonical` checks the bytes again, so the place where the app keeps them does not need to be trusted.

### Links

Link clicks are passed to `onLinkClick` as `TerminalLinkClick`: the `TerminalLink` (`click.link`), and how the user clicked it (below). There are two kinds of links, both shown underlined ([decisions/0019](decisions/0019-extensibility.md)). For both, **show the destination (`link.url`) to the user and get confirmation before** calling `session.openUrl(link.url)` (requirement 6.1).

- `TerminalLink.Explicit`: an OSC 8 hyperlink. The host decides both the link text (`link.text`) and the destination, so they do not necessarily match. Showing the text and the destination side by side makes discrepancies easier to notice.
- `TerminalLink.Matched`: text that matched `linkPatterns`. The default `LinkPattern.Urls` is `http` and `https` URLs written in the text, and `url` is the matched text. A match over text hidden with SGR 8 (conceal) is not a link, but the matched text can still differ from what the user sees: the host can draw part of it in the background's color, and it may go on beyond the display.

`openUrl` does nothing and returns `false` for URLs `GatePolicy` does not allow. Whether a URL can be opened can be known beforehand with `session.canOpenUrl(url)`, so there is no need to show a confirmation dialog for URLs that cannot be opened. `GatePolicy.urlSchemes` applies equally to OSC 8 links and pattern links. Which text becomes a link is decided by `linkPatterns`, so adding a scheme to `urlSchemes` does not make URLs with that scheme detected in the text. If `onLinkClick` is not passed, links cannot be tapped, and `linkPatterns` are not matched.

`click.pointerType` is the kind of pointer (`PointerType.Touch` for a tap, `PointerType.Mouse` for a mouse click), and `click.keyboardModifiers` the modifier keys held, so that an app can, for example, open links only on Ctrl+click with a mouse, as desktop terminals do. When no pointer clicked, they are `PointerType.Unknown` and no modifiers.

```kotlin
onLinkClick = { click ->
    val mouse = click.pointerType == PointerType.Mouse
    if (!mouse || click.keyboardModifiers.isCtrlPressed) confirmOpening(click.link.url)
}
```

Apps can add their own links with a regular expression and a URL template. `{0}` in the template is replaced with the whole match, and `{n}` with the nth group.

```kotlin
val crbug = LinkPattern(Regex("""\bcrbug/(\d+)"""), "https://crbug.com/{1}")
Terminal(viewModel.session, linkPatterns = listOf(LinkPattern.Urls, crbug), onLinkClick = ...)
```

- Values inserted into the template are percent-encoded. The host decides the matched text, so even if it contains `/` or `@`, the structure of the URL (the host or the path) does not change. To include path separators, write a group for each part (e.g. `https://github.com/{1}/{2}`). Values `.` and `..` are inserted as is, so opening the URL removes path segments (the host does not change).
- Regular expressions run on the main thread against text the host decides, every time the display changes. **vtfx sets no limit on the effort of matching** (Android's regular expressions cannot be stopped midway). Do not use nested or overlapping repetition (`(a|aa)+b`, `(\w+)*x`, and so on); the host can make matching exponentially long with a few dozen characters, freezing the screen. Use patterns with little backtracking, like `\bcrbug/(\d+)`, and prefer character classes to alternation (`[\w-]+` rather than `(?:\w|-)+`) ([decisions/0019](decisions/0019-extensibility.md)).
- If the same cell has an OSC 8 link, it takes precedence. If patterns overlap, the earlier one in the list takes precedence. Overlaps are decided first, from the text alone, so even if a match of an earlier pattern is removed because of OSC 8, matches of later patterns that overlap it do not become links.

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

`Modifier.onPreviewKeyEvent` on an ancestor of `Terminal` does not work for this: it sees only the keys the IME passed on, and IMEs such as Gboard take the keys of a physical keyboard and send them through the IME connection. Keys from a software keyboard and keys sent with `session.sendKey` do not go through `onPreImeKeyEvent` either ([decisions/0032](decisions/0032-keys-and-layout.md)).

## 5. Extra key bar

A bar for keys not on the software keyboard (Esc, Tab, arrows, Ctrl, and so on) is not included in the library. The app builds it with the following APIs (e.g. `KeyBar` in `vtfx-sample`). The key types (`KeyStroke`, `TerminalKey`, `KeyModifiers`) are in the `com.google.vtfx.core` package.

- `session.sendKey(KeyStroke.Special(TerminalKey.Escape))`: sends a key.
- `session.stickyModifiers = KeyModifiers.of(ctrl = true)`: applies modifiers to the next typed key or character. They are cleared after one use. Arming them sends the composing text first, without them. Changes, including clearing, are notified with `TerminalSessionListener.onStickyModifiersChanged`, so follow the key bar's display with it (e.g. `SampleViewModel` in `vtfx-sample`).
- `session.sendText(text)`, `session.paste(text)`: input of text and pastes. A paste is bracketed if the host has enabled bracketed paste, and control characters are removed.

### Direct input

Some IMEs hold English words as composing text until a space and do not send them to the host. This is a problem for apps that act on each key, like vim's normal mode, so `session.directInput = true` asks the IME for character input without predictive text. Many IMEs do not offer Japanese or Korean in this case, so let the user switch it (e.g. "Direct" on the extra key bar of `vtfx-sample`; [decisions/0017](decisions/0017-ime-scope.md)). Gboard's English sends one character at a time even with the default.

## 6. How far terminal content is shown

Host output can contain secrets (displayed API keys, keys, and so on). vtfx handles terminal content as follows ([decisions/0015](decisions/0015-accessibility.md)).

- **Accessibility services**: Each displayed row is exposed for screen readers. However, rows are marked as sensitive data, so they are visible only to services that declare `isAccessibilityTool` (TalkBack and so on), and not to other accessibility services. The mark works on Android 14 (API 34) and later. On API 29 to 33, the platform cannot tell a tool from another service, and the rows are visible to every accessibility service ([decisions/0039](decisions/0039-minsdk-29.md)). Apps that show secrets can take this into account, for example by setting their own minSdk to 34.
- **Content Capture** (the mechanism by which OS intelligence features receive the text on the screen): vtfx does not stop it. Compose's Content Capture does not look at the sensitive mark, and the means of stopping it affects the whole process, so the app decides. Apps that handle secrets can set `ContentCaptureManager.isEnabled = false` (`androidx.compose.ui.contentcapture`; `@ExperimentalComposeUiApi` in Compose UI 1.12).
- **Screen sharing and screen recording**: vtfx does not hide content. Apps that want to hide it add `Modifier.sensitiveContent()` to the `modifier` of `Terminal` (Android 15 and later; the terminal part is hidden while sharing).

## 7. Others

- `session.title`: the window title set by the host. It may also go back to a title the host had saved (XTWINOPS 22, 23), such as when vim exits. It is an untrusted string. Changes are notified with `TerminalSessionListener.onTitleChanged`, on the thread that called `feed`. Of the titles set in one `feed`, only the last one is notified.
- `TerminalSessionListener.onBell`: the bell. Many BELs contained in one `feed` are batched into one notification on the thread that called `feed`.
- `session.snapshot()`: a copy of the screen. Can be read on any thread.

## 8. Using without a UI

`TerminalSession` is in the JVM library `vtfx-session` and works without Android and Compose ([decisions/0026](decisions/0026-session-module.md)). JVM programs can emulate a terminal, and apps can use the same session as production in their own unit tests (JUnit without Robolectric).

```kotlin
val sent = ByteArrayOutputStream()
val session = TerminalSession(connection = { sent.write(it) })
session.feed("$ ".toByteArray())
assertEquals("$", session.snapshot().line(0).text().trim())
session.sendText("ls\r")
assertEquals("ls\r", sent.toString())
```

- There are no timers and no thread assumptions, so tests can be written synchronously. Listeners are also called within the call.
- There is no display, so change the size with `resize`. `onResize` is called on the first `resize`, even if the size is unchanged, and then when it changes.
- Lines that scrolled off the screen can be read with `session.snapshot(scrollOffset)`. The number of lines that can be scrolled back is `session.scrollbackSize` (0 on the alternate screen).
- Color queries (OSC 10, 11, 12, 4) are not answered. Once `Terminal` displays the session, they are answered with its colors.
- Send keys and text with `sendKey`, `sendText`, and `paste`.

## 9. Related documents

- The contract of the interaction with the IME: [design/ime-contract.md](design/ime-contract.md)
- The range of supported escape sequences: [decisions/0009](decisions/0009-emulation-scope.md); TERM and terminfo: [decisions/0010](decisions/0010-term-terminfo.md)
- Resource limits: [decisions/0002](decisions/0002-resource-limits.md)
