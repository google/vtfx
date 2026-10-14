# Extensibility policy (correspondence with xterm.js addons)

- Status: Approved (2026-10-13, approved by a human in the final review. The same day, the limit on matching effort was removed and made the app's responsibility. On 2026-10-14, matches over concealed text stopped being links, and key interception moved to a pre-IME callback because `onPreviewKeyEvent` misses the keys an IME takes)
- Date: 2026-10-12
- Related: Requirements 4.1, 4.2, 6.1, and 6.5 (customizability, minimal surface), [0006](0006-gate-defaults.md), [0007](0007-compose-session.md), [0012](0012-touch-and-selection.md), [0016](0016-public-api.md)

## Background

xterm.js has a mechanism for changing the terminal's behavior with addons (`ITerminalAddon`). VS Code's built-in terminal and others implement automatic links, search, shell integration, saving and restoring sessions, and so on with addons and xterm.js's public API (`registerLinkProvider`, `parser.registerOscHandler`, `registerMarker`, `registerDecoration`, `buffer`, and so on). How vtfx handles these use cases was decided in a discussion with a human.

The addon mechanism itself is thin: it just receives a `Terminal` and uses the public API. Whether vtfx can accommodate the features that addons implement in xterm.js depends on which basic APIs it exposes.

## Decision

### Mechanism

- No mechanism for registering addons (a mutable registry with a lifecycle of registration, activation, and disposal). It is too flexible, and makes it hard to keep the scope of the public API (requirement 6.5) and the security boundary ([architecture.md](../design/architecture.md)). In Compose, passing values declaratively as arguments of `Terminal` or at construction of `TerminalSession` plays the same role.
- No mechanism for inserting app code into the interpretation of host output (a synchronous handler like xterm.js's `registerOscHandler`). The problems are that app code would run on the `feed` thread while holding the lock, and that it could take over the default handling of OSC 52 and OSC 8 and bypass the gate's policies.
- Features with no room for the app to customize are built in, and options are not added until requested. Public APIs are provided only for features involving the app's UI or app-specific meaning, and those APIs are designed carefully one by one and recorded in decisions.

### Handling of each use case

| Use case | How xterm.js does it | How vtfx handles it |
|---|---|---|
| OSC 52 clipboard | ClipboardAddon | Done (`ClipboardAccess`, `GatePolicy`) |
| Following the display size | FitAddon | Done (`Terminal`) |
| Unicode widths and graphemes | Unicode11, UnicodeGraphemes | Done ([0011](0011-unicode.md)) |
| Connection with the host | AttachAddon | Done (`HostConnection`, `feed`) |
| Rendering method | WebGL, Canvas | Not needed ([0014](0014-performance.md)) |
| Automatic URL links | WebLinksAddon, link provider | Built in. In addition, the app can give text patterns and rules for converting them to URLs (below) |
| Link hover | link decorations, hover | Built in. Only for mouse and stylus hover, the pointer shape and decoration change |
| Key interception by the app | `attachCustomKeyEventHandler` | Done. `Terminal(onPreImeKeyEvent = ...)` intercepts per terminal, before the IME (below) |
| Search and highlighting | SearchAddon, decoration | Matching, highlighting, and scrolling to a match are built in, but the search field UI belongs to the app, so an API is needed. Where the state lives and how match positions are represented are decided in a separate decision |
| Confirmation before pasting | The app's implementation | Like hyperlinks, consider a shape that notifies the app and lets the app confirm. Clipboard suggestions of the IME arrive through `commitText`, so not every path can be covered |
| Progress (OSC 9;4) | ProgressAddon | If needed, exposed as state like `title` |
| Shell integration (OSC 633, 133, 7) | OSC handlers, markers, decorations | Not supported |
| Saving and restoring sessions | SerializeAddon | Not supported. For multiple tabs, it is enough for the app to have a `TerminalSession` per tab ([0007](0007-compose-session.md)). When the process ends, the connection to the host is also cut, and if the connection is kept in a service, the session lives there too |
| Link actions decided by the app (opening paths and so on), a selection API, items of the long-press menu | link provider, `getSelection`, and so on | Not made until a use appears |
| Typeahead (VS Code's typeahead) | An addon that depends on internal APIs | Deferred because of its size |
| Images, ligatures | ImageAddon, LigaturesAddon | Out of scope, as in requirement 4.2 |

### Link patterns

The app can add links by giving pairs of a regular expression and a URL template (e.g. `crbug/123` to `https://crbug.com/123`). The design principles are as follows.

- The conversion is given as a template, not a function. vtfx percent-encodes the values it inserts into the template, so the matched text (which the host can decide) cannot change the structure of the URL (the host or the path). However, percent-encoding leaves `.`, so if a value is `.` or `..`, it becomes a dot-segment of the path, and opening the URL removes that segment (and the previous one for `..`) (the WHATWG URL specification also treats `%2E` as a dot-segment, so encoding does not prevent it). The host can neither change the host being opened nor add segments, so a human judged this acceptable (2026-10-12, after a code review).
- The app's regular expressions run against text the host decides. vtfx sets no limit on the effort of matching, and it is the app's responsibility to give patterns whose effort is proportional to the length of the text ("Matching effort" below). Matching is limited to lines whose content changed.
- Links passed to the app are of a type that distinguishes OSC 8 links (whose display and destination can disagree), detected URLs (the display is the destination), and links converted by patterns. Opening any of them goes through `TerminalSession.openUrl`, and the gate checks the scheme and so on ([0006](0006-gate-defaults.md)).
- If the same cell has an OSC 8 link, OSC 8 takes precedence. Overlaps between patterns are decided first, from the text alone without looking at OSC 8, and then matches touching OSC 8 are removed. Even if a match of an earlier pattern is removed because of OSC 8, matches of later patterns that overlap it are not restored. This avoids part of one displayed URL becoming a link to a different destination through a later pattern. It errs on the side of fewer links, so it does not enable spoofing. This order was decided by a human (2026-10-13, after a code review).
- A match that covers a concealed cell (SGR 8) is not a link either, in the same way as a match touching OSC 8: it still hides the later patterns' matches that it overlaps. The renderer does not draw concealed text, so such a link would go to a URL the user did not see (`https://good.example` shown, `https://good.example.evil.example` opened). The link is dropped rather than cut at the concealed cell, erring on the side of fewer links. Text the host draws in the background's color cannot be told from the cells, so the KDoc of `Terminal` and `TerminalLink` and [usage.md](../usage.md) ask apps to show the URL and confirm for every link, not only OSC 8 hyperlinks (2026-10-14, decided by a human after a security review).

The API is as follows (usage is in "Links" in [usage.md](../usage.md)).

- `LinkPattern(regex, urlTemplate)` (`vtfx-core`). `{0}` in the template is the whole match, and `{n}` is the nth group; other braces and nonexistent groups are rejected at construction. Built-in URL detection is `LinkPattern.Urls`, which finds `http` and `https` URLs with a linear scan without backtracking. Equality is determined by the regex, options, and template, so passing a freshly made list to the argument of `Terminal` every time does not redo detection.
- `Terminal(linkPatterns = listOf(LinkPattern.Urls), onLinkClick = ...)`. The default is URLs only, and an empty list stops detection. If `onLinkClick` is not passed, no matching is done either. `onHyperlinkClick: (Hyperlink) -> Unit` was replaced with `onLinkClick: (TerminalLink) -> Unit` (compatibility is not kept since this is 0.x; [0016](0016-public-api.md)). 2026-10-14: it became `onLinkClick: (TerminalLinkClick) -> Unit`, which adds the kind of pointer and the modifier keys to the link, so that an app can open links only on Ctrl+click ([0031](0031-desktop-pointer-and-terminal-state.md)).
- `TerminalLink` is sealed, with two cases: OSC 8's `Explicit(hyperlink)`, and `Matched(text, url, pattern)` for a pattern match. Built-in URLs can be distinguished by `pattern == LinkPattern.Urls`, in which case `url` is the same as `text` (the matched text, which is not always what the user sees; see above).
- **Matching effort**: vtfx sets no limit on the effort of matching the app's patterns. With patterns with nested or overlapping repetition (`(a|aa)+b` and so on), the host can make matching take exponentially long with a few dozen characters, stalling the main thread. Giving patterns with little backtracking is the app's responsibility, as written in the KDoc of `LinkPattern` and in [usage.md](../usage.md) (2026-10-13, decided by a human).
  - History: Originally, there was a limit that counted `charAt` calls on the matched text and stopped at 64 times the number of characters, and handling that stopped when matching threw `StackOverflowError`. Both mechanisms assumed HotSpot's `java.util.regex` (which reads with `charAt` and recurses on repetitions of alternatives), and were checked only in JVM tests. ART's `java.util.regex` passes the text to ICU's native engine, so it does not call `charAt`, and it keeps the backtracking state on the heap, so it does not recurse either. Therefore the limit did not work on Android (found in a code review). Android's `java.util.regex` exposes no means of stopping matching midway.
  - Alternatives considered: Matching with an engine that guarantees linear time (RE2/J) would add an external dependency, lose back-references and lookahead, and change the `LinkPattern` API. Matching on another thread cannot stop matching, so it would keep using CPU, and matching would pile up while output continues. Neither was taken; the ineffective mechanisms were removed to match the contract with the actual behavior.
  - Recursion depth on long logical lines is not a problem on ART. Matching `go/((?:\w|-)+)` against a logical line filling a 300 × 100 screen threw no exception and finished in 137 ms (emulator, Android 17).
- Detection is done on the displayed lines at every draw, reusing the previous result keyed by the content of the logical line (`LineSnapshot.contentEquals`). On the development machine's JVM, with a 200-column × 60-row screen (URLs and one pattern), it took 67 µs when the content did not change and about 0.8 ms when everything changed. It has not been measured on a real device. Overlaps between patterns are removed by scanning the matches kept so far once per pattern, so the effort is proportional to the number of matches (when every pair was compared, filling a 2048 × 100 screen with `#1 ` from the host took about 7 seconds).
- Logical lines cut off at the top and bottom edges of the display are matched together with the rows outside the display, and only the part inside the display is underlined. Matching only the display made part of a logical line look like the start of a word (the second half of `xhttps://`) or cut a match midway (`crbug/1|234` while showing scrollback), producing links different from those of the whole logical line (found in a code review). The snapshot holds up to one screen of rows outside the display above and below. For logical lines continuing longer than that, matches reaching the cut are not made links, because the text beyond the cut could change the match.
- Underlines are drawn outside the line drawing cache (`RenderNode`), overlaid on the line like the selection. A match also depends on the rows before and after joined by autowrap, so it cannot be included in the per-line cache key.

### Key interception

With multiple tabs, the Activity's `dispatchKeyEvent` cannot tell which terminal a key is for, so a per-terminal means is needed.

The means is the `onPreImeKeyEvent` parameter of `Terminal`, which sees each hardware key before the IME ([0032](0032-keys-and-layout.md); usage in "Intercepting keys" in [usage.md](../usage.md)).

This section first chose Compose's `Modifier.onPreviewKeyEvent` on an ancestor of `Terminal`, with no new API. That was checked only with Robolectric, where no IME takes keys, and it does not hold with an IME (corrected 2026-10-14: a physical keyboard with Gboard does not reach it). Android dispatches a hardware key to the views' `dispatchKeyEventPreIme` first, then to the IME, and only the keys the IME does not consume to the views' `dispatchKeyEvent`, where Compose runs `onPreviewKeyEvent`. Gboard consumes the keys of a physical keyboard and resends them through `InputConnection`, so an ancestor never sees them (also seen on the emulator with keys injected by `adb`; 3.1 of [device-checks.md](../device-checks.md)). `onPreviewKeyEvent` still sees the keys the IME passes on (`TerminalKeyInterceptionTest`), but the KDoc of `Terminal` and [usage.md](../usage.md) no longer recommend it.

## Alternatives considered

- **The same addon mechanism as xterm.js**: As above, it makes it hard to keep the public surface and the security boundary. Even in xterm.js, some of VS Code's addons depend on internal APIs, and demand not met by the design of the public API leaks outside the mechanism.
- **Notify unknown OSCs as events, only for numbers the app allows**: It avoids the problems of synchronous handlers, but it would not support shell integration, which was the main use, so it is not made now. Progress (OSC 9;4) is built in as typed state if needed.
- **Take the link conversion as a function**: More flexible, but encoding the matched text becomes the app's responsibility.

## Impact of reversing

- Supporting shell integration needs tracking lines (across eviction from scrollback and reflow). This is the problem [0012](0012-touch-and-selection.md) avoided for selection; start by considering giving `Line` a prompt marker.
- Supporting saving and restoring sessions needs an API that reads the buffer, including scrollback. It shares its foundation with the search API.
