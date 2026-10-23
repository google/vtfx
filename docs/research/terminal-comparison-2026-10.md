# Features of other terminal emulators (2026-10)

> [!NOTE]
> A record of a comparison made on 2026-10-23, kept so that decisions about features can cite what other terminals do; it is not kept up to date. It was made by reading the sources and documents, not by running the programs. What vtfx supports is in the design documents, mainly [emulation.md](../design/emulation.md) and [compose-input.md](../design/compose-input.md); the vtfx column here is as of `d6e6b4a99b22c4055c5663a8ccbf272495295027`.

## 1. Terminals compared

| Terminal | Why it was chosen | Source read |
|---|---|---|
| alacritty_terminal | A terminal library without a UI | alacritty `542147067dd016fc5f49120ef0ba605a98a4e3a7` (0.26.1-dev), with `vte` `abeae765dd546dfff60b278f0757dcc71beb8ab1` (0.15.0) |
| Termux (`terminal-emulator`, `terminal-view`) | The most used terminal library on Android | termux-app `8629e632fcb95da272221be327db653fb24befe9` (0.118.0) |
| JediTerm | A terminal library for the JVM (the terminal of IntelliJ) | `1b354a63f9845aee3474b0749af0a62e406f0076` (3.76) |
| xterm.js, with its official addons | The usual library for embedding a terminal (VS Code and others) | `991857a1b1cacf06f9531ba74a6e927a7a6398bb` (6.0.0) |
| Ghostty (libghostty-vt, and the app where noted) | A recent terminal whose core is a library with a C API | `08a9518340e19487bd5fdf96963583fb32f0c41f` |
| WezTerm (wezterm-term, termwiz, and the app where noted) | A Rust library with a wide set of features | `e2a98e1b8af156988ad0ac778658ac27b60a55fb` |
| GNOME VTE | The GTK widget of GNOME Terminal and others, with long xterm compatibility | `5d75dcfd27bc2bfd17b1f6fddd6494abdc0355fc` (0.85.0 in development) |

In the tables, "Yes" means supported, "No" not supported, and "Partial" supported in part or under a condition given in the cell. Behavior marked "(from the source)" was worked out by reading the code and was not checked by running it.

## 2. Structure

| | vtfx | alacritty | Termux | JediTerm | xterm.js | Ghostty | WezTerm | VTE |
|---|---|---|---|---|---|---|---|---|
| Language, UI | Kotlin; Compose | Rust; none | Java; Android View | Java and Kotlin; Swing | TypeScript; DOM (also headless) | Zig, C API, wasm; the app uses AppKit and GTK | Rust; none (the app draws with the GPU) | C++; GTK 3 and 4 |
| Parser | Williams's state machine | `vte` (Williams) | Own state machine | Hand-written recursive descent that blocks on the stream | Williams (table) | Williams (with SIMD) | Williams (`vtparse`) | Williams |
| Encoding of keys and the mouse | Yes | No (in the app) | Yes | Yes | Yes | Yes | Yes | Yes |
| PTY | No (the app passes a `HostConnection`) | Yes | Yes (JNI; local processes only) | Partial (a `TtyConnector` interface) | No | No (in the app) | No (a separate crate) | Yes |
| Side effects (replies, clipboard) | Through `vtfx-gate`, with policies, under security review | `EventListener` | Calls `TerminalOutput` directly | Writes replies to the output stream | `onData` events; addons can reply directly | Callbacks; none set by default, so nothing is sent | Writes to a `Write`; `Clipboard` and `AlertHandler` traits | Writes to the PTY; GObject signals |
| Threads | Thread-safe session; immutable snapshots for drawing | `FairMutex` | UI thread only, without locking | Emulator thread and a lock | Single thread; work cut into 12 ms slices | The caller locks | `&mut`, no lock | GTK main loop |
| Redrawing | `RenderNode` per row | Damage per line | Whole view | Whole view at 50 FPS | Range of rows | Dirty flag per row | Sequence number per line | Rows in GTK 3; whole view in GTK 4 |

## 3. Emulation

| | vtfx | alacritty | Termux | JediTerm | xterm.js | Ghostty | WezTerm | VTE |
|---|---|---|---|---|---|---|---|---|
| C1 controls | Ignored | Raw C1 bytes executed | Ignored | Printed, except 0x9D (from the source) | Executed when encoded in UTF-8 | Ignored | Executed, raw and UTF-8 | Executed when encoded in UTF-8 |
| Limit on OSC and DCS strings | 256 Ki (dropped whole) | None | 16 Ki | None | 10 M characters | 2 KiB; 8 MiB for some OSCs | None | 4096 code points |
| Overline (SGR 53, 55) | Yes | No | No | No | Yes | Yes | Yes | Yes |
| Underline styles and colors | Yes | Yes | Partial (single only; color kept but not drawn) | No | Yes | Yes | Yes | Yes |
| Alternate screen modes | 47, 1047, 1048, 1049 | 1049 | All four | All four | All four | All four | All four | All four |
| Mouse encodings | 1005, 1006, 1015 | 1005, 1006 | 1006 | 1005, 1006, 1015 | 1006, 1016 | 1005, 1006, 1015, 1016 | 1005, 1006, 1016 | 1006 |
| Any-event mouse (1003) | Yes | Yes | No | Yes | Yes | Yes | Yes | Yes |
| Synchronized output (2026) | Yes, 200 ms | Yes, 150 ms | No | Yes, 500 ms (without a timeout if output stops, from the source) | Yes, 1000 ms | Yes, 1000 ms in the app | Partial (in the multiplexer, without a timeout) | No |
| Focus events (1004) | Yes | Yes | Partial (mode kept, nothing sent) | Partial (mode kept, nothing sent) | Yes | Yes | Yes | Yes |
| Alternate scroll (1007) | To be supported ([0068](../decisions/0068-features-of-alacritty-terminal.md)) | Yes | No | Partial (an app setting) | No | Yes | No | Yes |
| Reverse wraparound (45) | No | No | No | Partial | Yes | Yes | Yes | No |
| Left and right margins (DECLRMM) | No | No | Yes | No | No | Yes | Yes | Yes |
| DECSCA with DECSED, DECSEL | Partial (normal erases) | No | Yes | No | Yes | Yes | No (although DA1 claims selective erase) | Partial (normal erases) |
| Rectangle editing (DECCRA, DECFRA, DECERA, ...) | No | No | Yes | No | No | No | No | Yes |
| REP | Yes (up to the cells of the screen) | Yes | Yes | No | Yes | Yes | Yes (without a limit on the count, from the source) | Yes (up to the end of the line) |
| DECSTR | Yes | No | Yes | Yes | Yes | Yes | Yes | Yes |
| DECRQM, DECRQSS, XTVERSION | Yes, yes, yes | Yes, no, no | Partial, partial, no | No, no, no | Yes, yes (SGR always `0m`), yes | Yes, yes, yes | Yes, partial, yes | Yes, yes, yes |
| DA3, XTGETTCAP | No, no | No, no | No, yes | No, no | No, no | Yes, yes | Yes, yes | Yes, yes |
| Setting colors (OSC 4, 10–12, 104, 110–112) | No ([0009](../decisions/0009-emulation-scope.md)) | Yes | Yes | No | Yes | Yes (also OSC 21) | Yes | Yes |
| Color scheme reports (mode 2031, `CSI ? 996 n`) | No | No | No | No | Yes | Yes | No | Yes |
| OSC 22, OSC 50 | No, no ([0068](../decisions/0068-features-of-alacritty-terminal.md)) | Yes, yes | No, no | No, no | No, no | Yes, no | No, no | No, no |
| Title stack | 10, with a limit on the total length; titles cleaned | 4096 | 20; titles not cleaned | No limit | 10 (off by default) | No | No | 8; titles up to 1024 bytes |
| OSC 7 (working directory) | No | No | No | Partial (accepted and ignored) | No | Yes | Yes | Yes |
| OSC 8 | Yes, with limits | Yes | No | Partial (only if the embedder's filter accepts the URI) | Yes, without limits | Yes | Yes, without limits | Yes, with limits; off by default |
| Notifications (OSC 9, 777), progress (OSC 9;4) | No | No | No | No | Progress only (addon) | Yes | Yes | Progress; OSC 777 off by default |
| Shell integration (OSC 133) | No | No | No | No | No | Yes (selecting an output, moving between prompts) | Yes (selecting a zone) | Yes (moving between prompts) |
| OSC 52 by default | Reading and writing refused | Writing allowed | Writing allowed without asking | No | No; with the addon, reading and writing allowed | Reading asks; writing allowed | Writing always allowed, no setting to refuse it | No |
| kitty graphics | Yes (direct transfer, Unicode placeholders) | No | No | No | Partial (addon; no placeholders) | Yes (also files, shared memory, animation) | Yes (also files; on by default in the app only) | No |
| sixel, iTerm2 images | No, no | No, no | Yes, yes | No, no | Yes, yes (addon) | No, no | Yes, yes | Partial (off by default), no |
| Bidirectional text | No | No | No | No | No | No | Yes | Yes |

## 4. Character widths

| | vtfx | alacritty | Termux | JediTerm | xterm.js | Ghostty | WezTerm | VTE |
|---|---|---|---|---|---|---|---|---|
| Width table | Generated from UCD 16.0 | `unicode-width` 0.2 | wcwidth (Unicode 15) | widecharwidth (Unicode 17) | Unicode 6 by default; 11 or 15 with addons | uucode | widecharwidth (Unicode 16), version selectable | GLib 2.78 (Unicode 15) |
| East Asian Ambiguous as wide | Yes | No | No | Yes | No (no public API) | No | Yes | Yes |
| Combining characters per cell | 32 UTF-16 units | 9 | 15 | Not combined (from the source) | No limit | 64 code points | No limit | About 11; 100,000 kinds per process |
| Grapheme clusters (emoji ZWJ sequences) | No (flags only) | No | No | No (UTF-16 units) | Partial (experimental addon) | Yes (mode 2027) | Yes (always) | No |
| Mode 2027 | No | No | No | No | No | Yes | Partial (always reported set) | No |

## 5. Screen, scrollback, and resource limits

| | vtfx | alacritty | Termux | JediTerm | xterm.js | Ghostty | WezTerm | VTE |
|---|---|---|---|---|---|---|---|---|
| Reflow on resize | Main screen | Main screen | Both screens | Main screen | Main screen | Main screen | Main screen | Main screen |
| Scrollback by default | 10,000 lines and a memory budget | 10,000 lines | 2,000 lines | 5,000 lines | 1,000 lines | A budget in bytes (50 MB in the app) | 3,500 lines | 512 lines; unlimited goes to a compressed, encrypted temporary file |
| Limits against hostile output | Every resource, linear time, fuzzing | Some | Some (no limit on the number of images) | Few | Some (`write()` throws over 50 MB pending) | The main strings and payloads | Few (REP count, string lengths, the 2026 buffer) | Most, with a time limit per read and fuzzing |

## 6. Input and interaction

| | vtfx | alacritty | Termux | JediTerm | xterm.js | Ghostty | WezTerm | VTE |
|---|---|---|---|---|---|---|---|---|
| kitty keyboard protocol | No (a decision of its own, [0068](../decisions/0068-features-of-alacritty-terminal.md)) | Partial (modes; encoding in the app) | No | No | Yes (off by default) | Yes | Yes (off by default) | No |
| modifyOtherKeys | No | No | No | No | No (win32-input-mode instead) | Yes | Yes | No |
| IME | An editing model ([ime-contract.md](../design/ime-contract.md)) | No | Commits only (`TYPE_NULL` by default) | Composing text drawn over the cursor | Composing text drawn over the cursor | In the app, composing text drawn | In the app | Composing text drawn; no surrounding text |
| Block selection | No ([0068](../decisions/0068-features-of-alacritty-terminal.md)) | Yes | No | No | Yes | Yes | Yes (app) | Yes |
| Word delimiters | To be supported ([0068](../decisions/0068-features-of-alacritty-terminal.md)) | Yes, configurable | No (blanks only) | A fixed list | Yes, configurable | Yes, configurable | Yes, configurable | Yes, configurable |
| Search | Plain text ([0047](../decisions/0047-search.md)) | Regular expressions | No | Plain text | Regular expressions (addon) | Plain text, on its own thread | Regular expressions (app) | Regular expressions (PCRE2) |
| Keyboard mode for selecting (vi, copy mode) | No | Yes | No | No | No | Partial (key tables) | Yes (app) | No |
| Link detection | OSC 8 and regular expressions | OSC 8 | Regular expressions in the app | Asynchronous filters | OSC 8 and regular expressions (addon) | OSC 8 and regular expressions (app) | OSC 8 and regular expressions (app) | OSC 8 and regular expressions |
| Accessibility | Yes (a node per row) | No | Partial (the whole screen as one description) | No | Yes (screen reader mode) | Partial (macOS only) | No | Yes (ATK, GtkAccessible) |
| Other notable features | | | | Predictive echo of typed keys | A public parser API for any sequence | kitty clipboard protocol (OSC 5522) | tmux control mode, SSH, quick select | Legacy encodings (ICU) |

## 7. Features that vtfx lacks and several others have

| Feature | Terminals with it, of the seven | Where vtfx records it |
|---|---|---|
| Setting colors | 6: all but JediTerm | Left out, [0009](../decisions/0009-emulation-scope.md) |
| Block selection | 5: alacritty, xterm.js, Ghostty, WezTerm, VTE | Left out for now, [0068](../decisions/0068-features-of-alacritty-terminal.md) |
| XTGETTCAP | 4: Termux, Ghostty, WezTerm, VTE | Not answered, [0006](../decisions/0006-gate-defaults.md), [0009](../decisions/0009-emulation-scope.md) |
| DA3 | 3: Ghostty, WezTerm, VTE | Not recorded |
| Left and right margins | 4: Termux, Ghostty, WezTerm, VTE | Left out, [0009](../decisions/0009-emulation-scope.md) |
| Search with regular expressions | 4: alacritty, xterm.js, WezTerm, VTE | Left out, [0047](../decisions/0047-search.md) |
| sixel | 4: Termux, xterm.js, WezTerm, VTE (off by default) | Left out, section 4 of [emulation.md](../design/emulation.md) |
| Notifications and progress (OSC 9, 777, 9;4) | 4: xterm.js (progress), Ghostty, WezTerm, VTE | Not recorded |
| kitty keyboard protocol | 3: xterm.js, Ghostty, WezTerm | A decision of its own, [0068](../decisions/0068-features-of-alacritty-terminal.md) |
| Shell integration (OSC 133) | 3: Ghostty, WezTerm, VTE | Not recorded |
| Working directory (OSC 7) | 3: Ghostty, WezTerm, VTE | Not recorded |
| Grapheme clusters, mode 2027 | 3: Ghostty, WezTerm, xterm.js (experimental) | Not supported, section 7 of [emulation.md](../design/emulation.md) |
| Pixel mouse coordinates (1016) | 3: xterm.js, Ghostty, WezTerm | Not recorded |
| Color scheme reports (2031) | 3: xterm.js, Ghostty, VTE | Not recorded |

The other way, no other terminal compared has an IME editing model, and only VTE comes close to vtfx's limits against hostile output; vtfx's default for OSC 52 is the strictest of those that support it.
