# Policy of the gate

This document describes what `vtfx-gate` sends to the host and how it decides on requests with external effects: replies to queries, the clipboard, and opening URLs. It also describes what `vtfx-gate-android` does with the requests the gate allowed. The place of the two modules in the design is in sections 3.3 and 3.4 of [architecture.md](architecture.md); the attack types they answer are in requirement 6.1 of [REQUIREMENTS.md](../REQUIREMENTS.md), and the tests for them in section 12 of [testing.md](testing.md).

## 1. Principles

- Every byte sent to the host is built or checked by `HostWriter`, one method per kind of input (section 2).
- No text sent by the host becomes part of what is sent back. Replies are built from constants and numbers from the terminal state; the only text that a reply carries is the clipboard, encoded in base64 (sections 3 and 4).
- Requests with external effects are refused unless `GatePolicy` allows them, and the defaults are on the safe side (requirement 6.5). Requests that come from the host alone never open a URL (section 5).
- The gate decides; `vtfx-gate-android` only performs what the gate allowed, and has no decision logic (section 6).

The defaults and the reasons for them are in [decisions/0083](../decisions/0083-gate.md).

## 2. What is sent to the host

`HostWriter` is the only holder of the `HostConnection`. Each call is sent as a whole, without interleaving with other calls, and a call made from inside `HostConnection.write` on the same thread fails with `IllegalStateException`.

| Method | What it sends |
|---|---|
| `sendKey(stroke, modes)` | The bytes xterm sends for the key and modifiers under the terminal modes (application cursor keys and so on), or, while the host set flag 1 of the kitty keyboard protocol, the forms of that protocol, with flag 4 if set (section 2.2), encoded by `KeyEncoder` inside the gate. Its inputs are only the key, the modifiers, and the modes, which are enums, booleans, and the code points of a character key, so no text from the host can reach the output ([decisions/0083](../decisions/0083-gate.md)). Only Ctrl makes a control from a character: a character key whose code point is itself a control (C0, DEL, or C1) sends nothing, and an unpaired surrogate is sent as U+FFFD, as in text (section 2.1). |
| `sendText(text)` | Text that the user typed or the IME committed on one line, converted as in section 2.1. Text with a line break (CR or LF) is a programming error (`IllegalArgumentException`): Enter (`sendKey`) and pastes (`sendPaste`) are the only ways to end a line, so that a caller that takes pasted text for typed text fails, rather than run its lines one by one despite bracketed paste mode ([decisions/0075](../decisions/0075-adversaries-are-inputs.md)). |
| `sendDeletes(count)` | `count` DELs, which erase text already sent in IME editing. A count outside 1 to 65536 is a programming error. |
| `sendPaste(text, bracketed)` | Pasted text, converted as in section 2.1, and wrapped in `ESC [ 200 ~` and `ESC [ 201 ~` when `bracketed`. Text of several lines that the IME commits, such as a clipboard suggestion, is a paste too (section 4.2 of [ime-contract.md](ime-contract.md), [decisions/0082](../decisions/0082-ime-editing-model.md)). |
| `sendReply(query, colors)` | The reply to a query (section 3). |
| `sendMouse(report, tracking, encoding)` | A mouse report, only if the host asked for this kind of event with the tracking mode. Positions that the encoding cannot express are not sent. |
| `sendFocus(focused)` | `CSI I` or `CSI O`. |
| (internal) `write(bytes)` | Used inside the gate for the reply to an OSC 52 read (section 4.1). |

### 2.1 Text and pastes

`OutboundText` converts text and pastes the same way:

- Line breaks (CR LF, CR, LF) become CR, and HT is kept. Only pastes have line breaks, since `sendText` refuses them.
- Every other control character (C0, DEL, and C1) is removed. Since no ESC or C1 CSI (U+009B) remains, pasted content cannot end a bracketed paste (`ESC [ 201 ~`) early, or form any other control sequence.
- Unpaired surrogates become U+FFFD.

The same conversion is done whether or not the host enabled bracketed paste mode. `ImeEditor` in `vtfx-input` keeps a copy of the rule for removing controls, so that its input history matches what the host receives; section 4.2 of [ime-contract.md](ime-contract.md) describes the conversion from the side of the IME.

### 2.2 Keys in the kitty keyboard protocol

While the host set flag 1 of the kitty keyboard protocol, disambiguate escape codes (section 3 of [emulation.md](emulation.md)), `KeyEncoder` encodes keys as kitty does with that flag ([decisions/0083](../decisions/0083-gate.md)). `m` below is the modifier parameter, 1 + 1 for Shift, 2 for Alt, 4 for Ctrl, and 8 for Meta (the protocol's Super), the same as in xterm's sequences; `;m` is left out without modifiers.

With flag 4 too, report alternate keys, a character key sent as `CSI u` reports its alternate keys after the code: `CSI code : shifted : base ; m u`. The shifted key is the character that the key produced, given only when Shift is held and it differs from the code; the base key is `KeyStroke.Character.baseLayoutCodePoint`, the key at the same place on a US PC-101 keyboard, given only when it is known and differs from the code, and when it is given without a shifted key, the shifted key is left empty (`CSI code :: base ; m u`). Ctrl+Shift+1 on a US layout is `CSI 49 : 33 ; 6 u`, and Ctrl+С on a Russian layout `CSI 1089 :: 99 ; 5 u`. Flag 4 changes no other key, and without flag 1 it changes nothing.

| Key | Without modifiers | With modifiers |
|---|---|---|
| Esc | `CSI 27 u` | `CSI 27 ; m u` |
| Enter | CR, or CR LF with LNM, as without the flag | `CSI 13 ; m u` |
| Tab | HT | `CSI 9 ; m u` (Shift+Tab is `CSI 9 ; 2 u`, not `CSI Z`) |
| Backspace | DEL | `CSI 127 ; m u` |
| The arrows, Home, End, F1, F2, F4 | `CSI A` to `CSI D`, `CSI H`, `CSI F`, `CSI P`, `CSI Q`, `CSI S`, also with DECCKM | `CSI 1 ; m A` and so on |
| Insert, Delete, Page Up, Page Down, F3, F5 to F12 | `CSI n ~`, with F3 as `CSI 13 ~` | `CSI n ; m ~` |
| A character key | The character, as text | With Ctrl, Alt, or Meta: `CSI code ; m u`, where the code is the unshifted key; with Shift alone, the character |
| A digit of the numeric keypad (NumLock on), its decimal point, or an operator | Its character, as text | With Ctrl, Alt, or Meta: `CSI code ; m u`, with the protocol's numbers from 57399 (`0`) to 57415 (`=`); with Shift alone, the character |
| A navigation key of the keypad (a digit or the decimal point with NumLock off) | `CSI code u`, with the protocol's numbers from 57417 (Left) to 57427 (Begin) | `CSI code ; m u` |
| Keypad Enter | `CSI 57414 u` | `CSI 57414 ; m u` |

The code of a character key is `KeyStroke.Character.unshiftedCodePoint`, the character that the key produces without modifiers, as the protocol wants: `1` for Ctrl+Shift+1 and `a` for Ctrl+A with Caps Lock, with Shift in the modifiers when it was held. The lock keys are not reported. DECKPAM does not apply to the keypad, which follows NumLock instead. A character key of a control sends nothing, and an unpaired surrogate, as the character or the unshifted key, is U+FFFD, as without the flag.

## 3. Replies

`vtfx-core` emits `ReplyRequested` with a `TerminalQuery`, a type that holds no text from the host, and `Replies` builds the reply. The queries that are recognized at all are decided in [decisions/0079](../decisions/0079-emulation-scope.md).

| Query | Reply |
|---|---|
| DA1 (`CSI c`, DECID) | `CSI ? 62 ; 22 c` (VT220, ANSI color) |
| DA2 (`CSI > c`) | `CSI > 0 ; 10 ; 1 c` (as Windows Terminal reports) |
| XTVERSION (`CSI > q`) | `DCS > | vtfx(0.1) ST` |
| The flags of the kitty keyboard protocol (`CSI ? u`) | `CSI ? flags u`, with the flags set of those supported, 1 and 4 ([decisions/0083](../decisions/0083-gate.md)) |
| DSR 5 | `CSI 0 n` |
| DSR 6, DECXCPR | `CSI row ; column R`, `CSI ? row ; column R` |
| DECRQM | `CSI mode ; state $ y`, with `?` for DEC private modes |
| DECRQSS `m`, `r`, ` q` | `DCS 1 $ r ... ST` with the values of the setting from the terminal state; `DCS 0 $ r ST` for any other setting |
| XTWINOPS 18 | `CSI 8 ; rows ; columns t` |
| XTWINOPS 14, 16 | `CSI 4 ; height ; width t`, `CSI 6 ; height ; width t` in pixels, only once the display has given the size of a cell |
| OSC 10, 11, 12, 4 queries | `OSC n ; rgb:rrrr/gggg/bbbb ST` from the colors the display reports (`ReportedColors`); not answered without them |
| kitty graphics commands | `APC G` with the numbers of the command and a fixed status string ([decisions/0080](../decisions/0080-kitty-graphics.md)) |

Every number in a reply is checked against its range before the reply is built (positions up to `TerminalLimits.MAX_DIMENSION`, sizes in pixels up to that times `ProtocolLimits.MAX_CELL_SIZE`, palette indexes 0 to 255); a number out of range is a programming error.

The following queries are not answered (the model does not emit `ReplyRequested`):

- Reports of the window title and icon name (XTWINOPS 20, 21), a classic path for command injection.
- Checksums of the screen contents (DECRQCRA), a means of guessing the screen contents. The esctest harness computes them in the test code ([decisions/0079](../decisions/0079-emulation-scope.md)).
- XTGETTCAP, whose replies would carry terminfo strings ([decisions/0079](../decisions/0079-emulation-scope.md)).

## 4. Clipboard

The clipboard is reached through `ClipboardAccess`, which `vtfx-gate-android` implements and only `vtfx-gate` calls. Without a `ClipboardAccess`, every clipboard request is refused. `AndroidClipboard` in a context without the clipboard service (`Context.getSystemService` returns null) writes nothing and reads nothing, since `ClipboardAccess` must not throw.

### 4.1 OSC 52

| Request | Default | When allowed |
|---|---|---|
| Write | Denied (`GatePolicy.allowClipboardWrite = false`) | Text longer than `maxClipboardWriteLength` UTF-16 code units (by default 64 Ki, `DEFAULT_MAX_CLIPBOARD_WRITE_LENGTH`) is dropped as a whole, and so is text with a control other than HT, LF, and CR (C0, DEL, C1) or a bidi control (U+061C, U+200E, U+200F, U+202A to U+202E, U+2066 to U+2069). Of the writes in one write of host output, only the last allowed one is performed ([decisions/0081](../decisions/0081-resource-limits.md)). |
| Read | Denied (`GatePolicy.allowClipboardRead = false`) | The reply is `OSC 52 ; selection ; data ST`, where data is the clipboard text in base64, so it is never interpreted as control sequences. An empty or unreadable clipboard is replied to as empty. Of the reads in one write of host output (one `TerminalSession.feed`), only the first is answered with the clipboard; the others are replied to as empty without reading it. A read after an allowed write in the same write of host output is answered with the text of the latest allowed write before it, without reading the clipboard, as if every allowed write had been performed: the clipboard from before them, which another app may have put there, is not what the host would read after its writes. A text longer than `maxClipboardReadLength` UTF-16 code units (by default 64 Ki, `DEFAULT_MAX_CLIPBOARD_READ_LENGTH`) is replied to as empty. So the replies stay proportional to the host's output ([decisions/0081](../decisions/0081-resource-limits.md)). |

Apps that want remote copy in tmux and vim set `GatePolicy(allowClipboardWrite = true)`.

The gate parses the selection parameter with its own list of xterm's targets (`ClipboardSelection`: `c`, `p`, `q`, `s`, `0` to `7`, at most 16 characters), not with the list in `vtfx-core`, which is not under security review, and refuses both reads and writes whose selection does not parse. `vtfx-core` passes `s0` for an empty selection, as xterm does. The reply to a read spells the selection again from the gate's constants, so no text from the host is part of it.

### 4.2 The user's copy and paste

`TerminalGate.copyToClipboard(text)` and `readClipboardForPaste()` perform the user's copy and paste. They must be called only for explicit actions of the user, and the OSC 52 policy does not apply to them. `copyToClipboard` returns whether there was a clipboard to copy to; `readClipboardForPaste` returns null without a clipboard or text, and otherwise a `ClipboardText`, whose text only `vtfx-gate` reads: `HostWriter.sendPaste` takes it and sends it as one paste, so that the code that asked for the paste cannot by mistake send the text elsewhere, or line by line ([decisions/0075](../decisions/0075-adversaries-are-inputs.md)). `copyToClipboard` drops the characters that an OSC 52 write may not carry (section 4.1): controls other than HT, LF, and CR, and bidi controls. The copied text comes from the screen, which the host wrote, through code outside security review, so the gate checks it as it checks the host's writes, but drops the characters rather than refuse the copy, which the user asked for ([decisions/0075](../decisions/0075-adversaries-are-inputs.md)). There is no limit on the length, since a selection may be long. The pasted text is sent with `HostWriter.sendPaste` (section 2.1). `TerminalSession` hands the clipboard to the gate when it is constructed and does not call it itself ([decisions/0083](../decisions/0083-gate.md)).

### 4.3 What is written

Text is written as plain text, and is not marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`), whether it comes from OSC 52 or from the user's copy: on Android 13 and later, the preview of the clipboard that the system shows on each copy then shows the text, so the user can see what a host put there ([decisions/0083](../decisions/0083-gate.md)).

## 5. Opening URLs

### 5.1 When a URL is opened

There is no path that opens a URL from host output alone (OSC 8 and so on). `TerminalGate.openUrl` is called only on an explicit action by the user, after the UI has shown the actual URL; this is the caller's contract. The schemes in `GatePolicy.urlSchemes` apply to every link opened through vtfx: OSC 8 hyperlinks, URLs found in the text, and URLs built by the app's link patterns.

### 5.2 Which URLs are allowed

`TerminalGate.isAllowedUrl` allows a URL only if all of the following hold:

1. It is not empty and has at most 8192 UTF-16 code units.
2. It has no whitespace, control characters, format characters (bidirectional controls and zero-width characters), or surrogates (characters outside the BMP), because the displayed URL and the actual URL may differ.
3. It has no other default-ignorable code points: the whole BMP part of Unicode 16.0's `Default_Ignorable_Code_Point` (for example U+034F, U+115F, U+1160, U+17B4, U+17B5, U+180B to U+180F, U+3164, U+FE00 to U+FE0F, U+FFA0, and the unassigned U+2065 and U+FFF0 to U+FFF8). They draw as nothing (U+3164 HANGUL FILLER) or change the character before them (variation selectors). The list does not depend on the runtime's Unicode version: `scripts/generate_unicode_tables.py` generates `UnicodeDefaultIgnorables` in `vtfx-gate` (committed) from `third_party/unicode/ucd/DerivedCoreProperties.txt`, and `scripts/check.sh` fails if it is not up to date.
4. It has a scheme (an ASCII letter followed by ASCII letters, digits, `+`, `-`, or `.`, before the first `:`) that is in `GatePolicy.urlSchemes`, compared in lower case. The default is `http` and `https`, so `javascript:`, `file:`, `intent:`, `content:`, and others are not opened. `GatePolicy` refuses (`IllegalArgumentException`) a scheme in `urlSchemes` outside that grammar, such as `https:`, which would match no URL, and `file`: on Android, starting an activity for a `file:` URL throws `FileUriExposedException` in apps that target API 24 or later, so a link that the host wrote would crash the app when the user opened it ([decisions/0083](../decisions/0083-gate.md)). `urlSchemes` and `DEFAULT_URL_SCHEMES` are unmodifiable sets, so Java code, which can call `add` on any `Set`, cannot change a policy after it is built, or the default of later policies.
5. For `http`, `https`, `ftp`, `ws`, and `wss`, the special schemes of the WHATWG URL Standard whose authority browsers parse alike, `scheme:` is followed by exactly `//` and an authority with a host that is not empty, and the authority has no `@` (userinfo), no `\`, no `%`, and no character that is not ASCII:
   - Browsers skip any `/` and `\` after `scheme:` and take the host from what follows, but `android.net.Uri` and RFC 3986 do not: they find no host in `https:/evil.example` and `https:///evil.example`, read `https:evil.example` and `https:\\evil.example` as opaque, and read the host of `https://\evil.example` as `\evil.example`. Requiring exactly `//` keeps them in agreement. Browsers fail on an empty host (`https://`, `https:///path`, `https://:80`); Android does not.
   - Userinfo makes the URL start with a host it does not go to (`https://good.example@evil.example`), and a long userinfo can push the real host out of view.
   - Browsers treat `\` in the authority as `/` but other parsers do not, so they disagree on the host of `https://evil.example\@good.example`. Refusing `\` rather than ending the authority at it also refuses an `@` after it, whichever way a parser reads it.
   - Characters that are not ASCII let the host look like another one: letters of other scripts (`https://apple.com` with U+0430 CYRILLIC SMALL LETTER A for the `a`), and characters that look like `.` or `/` but are mapped to `.` by browsers or stay in the host (U+FF0E FULLWIDTH FULL STOP, U+FF0F FULLWIDTH SOLIDUS, U+2215 DIVISION SLASH). A host in punycode (`https://xn--pple-43d.com`) is ASCII and is opened; a user who needs an internationalized domain can open its punycode form.
   - Browsers percent-decode the host before they map and check it, so `%` would let ASCII text do the same: `https://good.example%2Eevil.example` goes to `good.example.evil.example`, and `https://%D0%B0pple.com` to a host with U+0430. A valid host has no use for `%`.
   - The authority starts after the `//` and ends at the first `/`, `?`, or `#`. The path, query, and fragment are not checked for these characters (`https://medium.com/@user` and `https://example.com/日本語` are allowed).
   - Other schemes are not checked for them (`mailto:a@b` stays openable when the app allows `mailto`).

The refusal of characters that are not ASCII and of `%` in the authority was added by agents after the human approval of [decisions/0083](../decisions/0083-gate.md), and was approved in the security reviews of `vtfx-gate`.

### 5.3 Results

`openUrl` returns an `OpenUrlResult` ([decisions/0083](../decisions/0083-gate.md)):

| Result | Meaning |
|---|---|
| `NotAllowed` | The gate did not call the `UrlOpener`: the URL is not allowed (section 5.2), or there is no `UrlOpener`. |
| `Opened` | An app was started for the URL. |
| `NoApp` | No app takes the URL. |
| `Refused` | The system refused to start the app that takes it, for example because that app requires a permission. |

`UrlOpener.open` returns a `UrlOpenerResult` (`Opened`, `NoApp`, or `Refused`), a subtype of `OpenUrlResult`, so an opener cannot return `NotAllowed`, and the gate returns what the opener returned unchanged. `OpenUrlResult` is closed: apps match it exhaustively. `canOpenUrl` returns whether `openUrl` would get past `NotAllowed`.

## 6. vtfx-gate-android

`vtfx-gate-android` is the only module that deals with `ClipboardManager` and the `Intent` that opens URLs. Apps make its classes and hand them to the session, which hands them to the gate.

- **`AndroidClipboard`** writes with `ClipboardManager.setPrimaryClip` as plain text with the label `Terminal`, not marked sensitive (section 4.3). It reads the text of the first item of the primary clip, and returns null when there is none; Android lets only the focused app in the foreground read the clipboard.
- **`AndroidUrlOpener`** starts an `ACTION_VIEW` intent for the URL with `CATEGORY_BROWSABLE`, so that a URL can start only activities that accept links from a browser, not an app's internal activity. The scheme is put in lower case (`Uri.normalizeScheme`), since Android matches schemes case-sensitively and the gate compared them in lower case. Without an activity as its context, it adds `FLAG_ACTIVITY_NEW_TASK`. It converts `ActivityNotFoundException` from `startActivity` into `NoApp` and `SecurityException` into `Refused`, and lets any other exception propagate ([decisions/0083](../decisions/0083-gate.md)).
