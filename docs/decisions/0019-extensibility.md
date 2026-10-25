# Extensibility policy (correspondence with xterm.js addons)

- Status: Approved (2026-10-13, approved by a human in the final review; the limit on matching effort and the links over concealed text were decided by a human afterwards); key interception with `onPreviewKeyEvent` superseded by [0032](0032-keys-and-layout.md), the parameter of `onLinkClick` by [0031](0031-desktop-pointer-and-terminal-state.md), and `TerminalLink` as a sealed type by [0037](0037-api-shape-before-1.0.md)
- Date: 2026-10-12
- Related: Requirements 4.1, 4.2, 6.1, and 6.5 (customizability, minimal surface), section 6 of [compose-input.md](../design/compose-input.md), "Links" in [usage.md](../usage.md), [0083](0083-gate.md), [0007](0007-compose-session.md), [0012](0012-touch-and-selection.md), [0016](0016-public-api.md)

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
| OSC 52 clipboard | ClipboardAddon | Built in (`ClipboardAccess`, `GatePolicy`) |
| Following the display size | FitAddon | Built in (`Terminal`) |
| Unicode widths and graphemes | Unicode11, UnicodeGraphemes | Built in ([0079](0079-emulation-scope.md)) |
| Connection with the host | AttachAddon | Built in (`HostConnection`, `feed`) |
| Rendering method | WebGL, Canvas | Not needed ([0014](0014-performance.md)) |
| Automatic URL links | WebLinksAddon, link provider | Built in. In addition, the app can give text patterns and rules for converting them to URLs (below) |
| Link hover | link decorations, hover | Built in, for the mouse: the pointer icon changes over a link |
| Key interception by the app | `attachCustomKeyEventHandler` | A per-terminal means, decided in [0032](0032-keys-and-layout.md) (below) |
| Search and highlighting | SearchAddon, decoration | Matching, highlighting, and scrolling to a match are built in, but the search field UI belongs to the app, so an API is needed. Where the state lives and how match positions are represented are decided in a separate decision |
| Confirmation before pasting | The app's implementation | Like hyperlinks, consider a shape that notifies the app and lets the app confirm. Clipboard suggestions of the IME arrive through `commitText`, so not every path can be covered. Those of several lines are sent as pastes, bracketed if the host enabled bracketed paste ([0082](0082-ime-editing-model.md)) |
| Progress (OSC 9;4) | ProgressAddon | If needed, exposed as state like `title` |
| Shell integration (OSC 633, 133, 7) | OSC handlers, markers, decorations | Not supported |
| Saving and restoring sessions | SerializeAddon | Not supported. For multiple tabs, it is enough for the app to have a `TerminalSession` per tab ([0007](0007-compose-session.md)). When the process ends, the connection to the host is also cut, and if the connection is kept in a service, the session lives there too |
| Link actions decided by the app (opening paths and so on), a selection API, items of the long-press menu | link provider, `getSelection`, and so on | Not made until a use appears |
| Typeahead (VS Code's typeahead) | An addon that depends on internal APIs | Deferred because of its size |
| Images, ligatures | ImageAddon, LigaturesAddon | Ligatures are out of scope, as in requirement 4.2. Images: the kitty graphics protocol is built in ([0080](0080-kitty-graphics.md)) |

### Link patterns

The app can add links by giving pairs of a regular expression and a URL template (e.g. `crbug/123` to `https://crbug.com/123`). The API and the rules are in section 6 of [compose-input.md](../design/compose-input.md). The design principles are as follows.

- **The conversion is a template, not a function.** vtfx percent-encodes the values it inserts into the template, so the matched text (which the host can decide) cannot change the structure of the URL (the host or the path). A value of `.` or `..` still becomes a dot-segment of the path, which opening the URL removes (the WHATWG URL specification also treats `%2E` as a dot-segment, so encoding does not prevent it). The host can neither change the host being opened nor add segments, so a human judged this acceptable.
- **Links say where they come from.** The type passed to the app distinguishes OSC 8 links (whose display and destination can disagree), detected URLs (the display is the destination), and links converted by patterns. Opening any of them goes through `TerminalSession.openUrl`, and the gate checks the scheme and so on ([0083](0083-gate.md)).
- **Precedence errs on the side of fewer links** (the order was decided by a human). If the same cell has an OSC 8 link, OSC 8 takes precedence. Overlaps between patterns are decided first, from the text alone, and then matches touching OSC 8 are removed, without restoring the later patterns' matches that they overlapped. This avoids part of one displayed URL becoming a link to a different destination through a later pattern, and does not enable spoofing.
- **A match over concealed text (SGR 8) is not a link** (decided by a human after a security review), in the same way as a match touching OSC 8. The renderer does not draw concealed text, so such a link would go to a URL the user did not see (`https://good.example` shown, `https://good.example.evil.example` opened). The link is dropped rather than cut at the concealed cell. Text the host draws in the background's color cannot be told from the cells, so the KDoc of `Terminal` and `TerminalLink` and [usage.md](../usage.md) ask apps to show the URL and confirm for every link, not only OSC 8 hyperlinks.
- **No limit on the effort of matching** (decided by a human). The app's regular expressions run against text the host decides, on the main thread, and with nested or overlapping repetition (`(a|aa)+b` and so on) the host can make matching take exponentially long with a few dozen characters. Android's `java.util.regex` passes the text to ICU's native engine and exposes no means of stopping matching midway, so a limit that vtfx set would not work on Android. Giving patterns whose effort is proportional to the length of the text is the app's responsibility, as written in the KDoc of `LinkPattern` and in [usage.md](../usage.md). Matching is limited to lines whose content changed.
- **Logical lines are matched whole.** Lines cut off at the edges of the display are matched together with their rows outside the display. Matching only the display made part of a logical line look like the start of a word (the second half of `xhttps://`) or cut a match midway (`crbug/1|234` while showing scrollback), producing links different from those of the whole logical line.

### Key interception

With multiple tabs, the Activity's `dispatchKeyEvent` cannot tell which terminal a key is for, so a per-terminal means is needed. This decision first chose `Modifier.onPreviewKeyEvent` on an ancestor of `Terminal`, with no new API; it does not see the keys that an IME takes, and [0032](0032-keys-and-layout.md) replaced it with the `onPreImeKeyEvent` parameter of `Terminal`.

## Alternatives considered

- **The same addon mechanism as xterm.js**: As above, it makes it hard to keep the public surface and the security boundary. Even in xterm.js, some of VS Code's addons depend on internal APIs, and demand not met by the design of the public API leaks outside the mechanism.
- **Notify unknown OSCs as events, only for numbers the app allows**: It avoids the problems of synchronous handlers, but it would not support shell integration, which was the main use, so it is not made now. Progress (OSC 9;4) is built in as typed state if needed.
- **Take the link conversion as a function**: More flexible, but encoding the matched text becomes the app's responsibility.
- **Bound the effort of matching**: Counting the reads of the text, or catching `StackOverflowError`, assumes HotSpot's `java.util.regex` and does not work on ART. Matching with an engine that guarantees linear time (RE2/J) would add an external dependency, lose back-references and lookahead, and change the `LinkPattern` API. Matching on another thread cannot stop matching, so it would keep using CPU, and matching would pile up while output continues.

## Impact of reversing

- Supporting shell integration needs tracking lines (across eviction from scrollback and reflow). This is the problem [0012](0012-touch-and-selection.md) avoided for selection; start by considering giving `Line` a prompt marker.
- Supporting saving and restoring sessions needs an API that reads the buffer, including scrollback. It shares its foundation with the search API.
- Changing the link rules changes `LinkFinder` and `LinkPattern` in `vtfx-core` and section 6 of [compose-input.md](../design/compose-input.md).
