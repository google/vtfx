# Defaults of the gate's replies and policies

- Status: Approved (2026-10-13, approved by a human in the final review)
- Date: 2026-10-12
- Related: Requirements 6.1 and 6.5 (safe defaults), 3.3 of [architecture.md](../design/architecture.md), `vtfx-gate`

## Background

Of the attack types in requirement 6.1, replies to the host, the clipboard, opening URLs, and pastes are handled by `vtfx-gate`. The default behavior of each needed to be decided.

## Decision

### Replies

| Query | Reply |
|---|---|
| DA1 (`CSI c`) | `CSI ? 62 ; 22 c` (VT220, ANSI color) |
| DA2 (`CSI > c`) | `CSI > 0 ; 10 ; 1 c` (same as Windows Terminal) |
| DSR 5 | `CSI 0 n` |
| DSR 6, DECXCPR | `CSI row ; column R`, `CSI ? row ; column R` |

- Replies are built only from constants and numbers taken from the terminal state. No path is created by which strings sent by the host are included in replies. `TerminalQuery` is a type that holds no host strings.
- The following queries are not answered (the model does not emit `ReplyRequested`).
  - Reports of the window title and icon name (XTWINOPS 20, 21): a classic command injection path. They will not be implemented in the future either.
  - Checksums of the screen contents (DECRQCRA): a means of guessing the screen contents. Whether to enable it only in test builds for esctest is decided in M7.
  - DECRQSS, XTGETTCAP, DECRQM, and color queries (OSC 4, 10, 11): the supported scope is decided in M6. Replies are likewise built only from constants and the terminal state.

### Clipboard (OSC 52)

| Request | Default | Reason |
|---|---|---|
| Write | Allowed, up to 64 Ki (UTF-16 units). If exceeded, the whole is dropped. | Used by remote copy in tmux and vim. The limit keeps the load from huge data in check. |
| Read | Denied | The clipboard may contain passwords and the like copied in other apps. |

Even when reading is allowed, the content is returned encoded in base64, so it is never interpreted as control sequences. The selection parameter is also checked in the gate.

Written content is not marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`) (2026-10-13, decided by a human). On Android 13 and later, the OS shows a preview of the clipboard on each copy. With the mark, the content is masked, so the user can tell that the host rewrote the clipboard with OSC 52 but cannot see what it was rewritten to. Showing the content lets the user notice the rewrite. When a secret is copied (OSC 52 from a password manager on the host and so on), it is shown in the preview for a few seconds, but OSC 52 has no means of telling whether the content is secret, and the terminal side cannot mask only part of it. Most copies are not secret, so the choice was to show everything. When the user selects and copies ([0012](0012-touch-and-selection.md)), it goes through the same `ClipboardAccess`, so it is likewise not marked. Originally the mark was set.

### Opening URLs

- There is no path that opens a URL from host output alone (OSC 8 and so on). `TerminalGate.openUrl` is called only on an explicit action by the user, after the UI has shown the actual URL (the caller's contract).
- The schemes allowed by default are only `http` and `https`. `javascript:`, `file:`, `intent:`, `content:`, and others are not opened.
- URLs containing whitespace, control characters, format characters (bidirectional controls and zero-width characters), or surrogates (characters outside the BMP) are not opened, because the displayed URL and the actual URL may differ.
- URLs containing other default-ignorable code points are not opened either: the whole BMP list of Unicode 16.0's `Default_Ignorable_Code_Point` (for example U+034F, U+115F, U+1160, U+17B4, U+17B5, U+180B to U+180F, U+3164, U+FE00 to U+FE0F, U+FFA0, and the unassigned U+2065 and U+FFF0 to U+FFF8). They draw as nothing (U+3164 HANGUL FILLER) or change the character before them (variation selectors). The list is not taken from the runtime, so that it does not depend on the runtime's Unicode version: `scripts/generate_unicode_tables.py` generates it (`UnicodeDefaultIgnorables` in `vtfx-gate`, committed) from the copy of `DerivedCoreProperties.txt` in `third_party/unicode`, and `scripts/check.sh` fails if it is not up to date. (2026-10-15, decided by a human after a security review; generated from the UCD file at a human's request, replacing a list written by hand)
- `http` and `https` URLs (in any case) with userinfo are not opened: an `@` in the authority, such as `https://good.example@evil.example`, makes the URL start with a host it does not go to, and a long userinfo can push the real host out of view. An `@` in the path, query, or fragment (`https://medium.com/@user`) is allowed. A `\` in the authority is refused too: browsers treat it as `/` there but other parsers do not, so they disagree on the host of `https://evil.example\@good.example`. As in browsers (the WHATWG URL Standard), the authority starts after any `/` and `\` that follow `scheme:`, with or without `//` (`https:good.example@evil.example` is refused), and ends at the first `/`, `?`, or `#`. Refusing `\` rather than ending the authority at it also refuses an `@` after it, whichever way a parser reads it. Other schemes are not checked for userinfo (`mailto:a@b` stays openable when allowed). (2026-10-15, decided by a human after a security review)
- `http` and `https` URLs with a character that is not ASCII in the authority (the same range as for userinfo above) are not opened, because the host could look like another one: letters of other scripts (`https://apple.com` with U+0430 CYRILLIC SMALL LETTER A for the `a`, or a Greek omicron for an `o`), and characters that look like `.` or `/` but are mapped to `.` by browsers or stay in the host (U+FF0E FULLWIDTH FULL STOP, U+FF0F FULLWIDTH SOLIDUS, U+2215 DIVISION SLASH). A host in punycode (`https://xn--pple-43d.com`) is ASCII, so it is opened: the URL shown is where it goes. Characters that are not ASCII in the path, query, or fragment (`https://example.com/日本語`) are allowed, since they do not change the host. Refusing every character that is not ASCII rather than only confusable ones keeps the check independent of a confusables table and of the runtime's Unicode version; a user who needs an internationalized domain can open its punycode form. Other schemes are not checked (`mailto:` has no authority). (2026-10-19, decided by the agent; waiting for the security review of `vtfx-gate`)

### Pastes

- All control characters (C0, DEL, C1) other than HT and newlines are removed from pasted content, and newlines become CR. Since no ESC remains, the content cannot include the end of bracketed paste (`ESC [ 201 ~`) to escape.
- The same conversion is done even when not in bracketed paste mode.

## Alternatives considered

- **Remove only the end sequence from pastes**: Escapes and malfunctions through the C1 CSI (U+009B) or other sequences containing ESC would remain. Removing all control characters is simpler and more reliable.
- **Deny OSC 52 writes by default too**: Safe, but copying with tmux's OSC 52 would stop working. Following the example in architecture.md, they are allowed with a limit.

## Impact of reversing

Change the defaults of `GatePolicy` and the constants of `Replies`. Update the expected values in `AttackTest` and `HostWriterTest`.
