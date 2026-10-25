# vtfx architecture

Status: Living document. It describes the current design and is kept in sync with the implementation; design choices and their reasons are recorded in [decisions/](../decisions/README.md).

This document defines the module structure, responsibilities, direction of dependencies, and security boundaries that satisfy [REQUIREMENTS.md](../REQUIREMENTS.md). The details of individual APIs and the contract for each IME call are covered in separate documents.

The design documents in this directory:

- [architecture.md](architecture.md) (this document): module structure, responsibilities, direction of dependencies, and security boundaries
- [emulation.md](emulation.md): how `vtfx-core` interprets host output: references, supported control functions, `TERM`, character widths, attributes, images, and resource limits
- [gate-policy.md](gate-policy.md): what `vtfx-gate` sends to the host, its policies for replies, the clipboard, and opening URLs, and what `vtfx-gate-android` does with allowed requests
- [ime-contract.md](ime-contract.md): the editing model, the contract for each IME call, the view the IME connects to, and the target IMEs
- [compose-input.md](compose-input.md): touch, the mouse, selection, links, hardware keys, the extra key bar, size, and accessibility of the `Terminal` composable
- [session-and-threads.md](session-and-threads.md): thread conventions of all modules, the terminal session, resize, and lifecycle
- [fonts.md](fonts.md): where fonts come from, sanitizing user-chosen fonts, and the font modules
- [boundaries.md](boundaries.md): how the build checks the module boundaries declared in `config/boundaries.toml`
- [testing.md](testing.md): test structure, oracles, and gates

## 1. Pillars of the design

1. **Gather security capabilities in one place.** Untrusted input may flow anywhere. However, only small modules subject to review can perform operations that affect the outside (sending to the host, the clipboard, opening URLs). Other modules do not even hold the references needed to perform them.
2. **Separate the logic from Android.** The terminal emulation and the IME editing model are pure Kotlin/JVM code that does not depend on Android. This makes deterministic tests, fuzzing, and replaying recorded IME traces fast and possible without an emulator.
3. **Represent side effects as values.** The terminal model does not itself perform replies to the host or writes to the clipboard; it outputs typed values that represent "what was requested". The modules subject to review decide whether and how to perform them.
4. **The modules under review check rather than trust.** The code outside them can be wrong. Each gate checks what it receives, so that a mistake in the parser, the font readers, or the editing model shows the wrong thing or is refused, instead of reaching the host, the clipboard, or native code ([gate-policy.md](gate-policy.md), section 2 of [fonts.md](fonts.md)). What the gates cannot check from the values, such as whether the user asked for an action, is the caller's contract, kept by types, KDoc, and tests ([decisions/0075](../decisions/0075-adversaries-are-inputs.md)).

## 2. Module structure

```
                    ┌──────────────────────────┐
  App ────────────▶ │ vtfx-compose (Android)   │  Compose UI. Rendering, gestures, IME connection,
                    │                          │  accessibility, display state
                    └────────────┬─────────────┘
                                 ▼
                    ┌──────────────────────────┐
  JVM program ────▶ │ vtfx-session (JVM)       │  The terminal session. Ties together the terminal,
                    │                          │  input, and requests to the host
                    └──┬────────┬────────┬─────┘
                       │        │        │
                       ▼        ▼        ▼
             ┌───────────┐ ┌──────────┐ ┌──────────────────────┐
             │ vtfx-input│ │ vtfx-core│ │ vtfx-gate (★)         │
             │ (JVM)     │ │ (JVM)    │ │ (JVM)                │
             └─────┬─────┘ └──────────┘ └──────────┬───────────┘
                   │            ▲  ▲               │
                   └────────────┘  └───────────────┘
                                                    ▲
                                     ┌──────────────┴─────────┐
                                     │ vtfx-gate-android (★)  │
                                     │ (Android)              │
                                     └────────────────────────┘
                     ★ = subject to security review
```

Dependencies are allowed only in the direction of the arrows. `vtfx-core` does not depend on other vtfx modules. The font modules are not in the figure: `vtfx-font-gate` depends on no other vtfx module, and `vtfx-font` and `vtfx-font-gate-android` depend on it (`vtfx-font-gate-android` also on `vtfx-core`, at compile time only, for `InternalVtfxApi`). Besides the arrows in the figure, `vtfx-compose` also depends on `vtfx-core`, `vtfx-input`, `vtfx-gate`, `vtfx-font`, `vtfx-font-gate`, and `vtfx-font-gate-android` to use their types, and on `vtfx-gate-android`, whose types it does not use, only to expose it to apps (below).

Every module is reviewed. Security review, the closer review of the paths in `config/protected-paths.toml`, covers whole modules, never single files, and the modules under it are named with `gate` ([decisions/0041](../decisions/0041-security-review-by-module.md), [decisions/0076](../decisions/0076-security-review-is-focused.md)).

| Module | Kind | Responsibility (one sentence) | Security review |
|---|---|---|---|
| `vtfx-core` | JVM | Interprets the host's output bytes and updates the terminal state (screen, scrollback, modes, cursor). | No |
| `vtfx-input` | JVM | Converts the user's key input and IME editing operations into values of input to send to the host. | No |
| `vtfx-gate` | JVM | Is solely responsible for assembling the bytes sent to the host and for allowing or denying requests with external effects. | **Yes** |
| `vtfx-gate-android` | Android | Performs the operations `vtfx-gate` allowed with Android APIs (clipboard, Intent). | **Yes** |
| `vtfx-session` | JVM | Ties together the terminal, the IME editing model, and requests to the host, and provides a terminal session without a UI. | No |
| `vtfx-compose` | Android | Displays the session as a Compose UI and receives input. | No |
| `vtfx-font` | JVM | Reads fonts that users choose and writes them again in one canonical form (section 2 of [fonts.md](fonts.md)). | No |
| `vtfx-font-gate` | JVM | Checks that bytes are a font in the canonical form, which is all that reaches the platform's native font code, and holds them as `SanitizedFont` (section 2 of [fonts.md](fonts.md)). | **Yes** |
| `vtfx-font-gate-android` | Android | Makes typefaces from `SanitizedFont`s, the only code that hands font data to the platform (section 2 of [fonts.md](fonts.md)). | **Yes** |
| `vtfx-sample` | Android app | A sample app with a fake host, for manual checks and the emulator e2e. It depends only on `vtfx-compose` and uses only the public API, as a third-party app would, so its classes are not checked ([decisions/0044](../decisions/0044-boundaries-in-gradle-and-classes.md)). | No |

This table is the canonical list of modules. The modules are those of `settings.gradle.kts`, and `config/boundaries.toml` lists the same modules with the dependencies each may have.

Apps depend on `vtfx-compose`. `vtfx-compose` exposes with `api` `vtfx-core`, `vtfx-gate`, and `vtfx-session` for the types the public API uses (`TerminalSession`, `HostConnection`, `KeyStroke`, `TerminalSnapshot`, and so on), `vtfx-gate-android` so that apps can use `AndroidClipboard` and `AndroidUrlOpener`, and `vtfx-font` and `vtfx-font-gate` for sanitizing fonts and for the `SanitizedFamily`s that `TerminalFont.Sanitized` takes. `vtfx-input` and `vtfx-font-gate-android`, which have no public API, are `implementation`. JVM programs without a UI depend on `vtfx-session`. The inside of each module is `internal`, and `public` declarations used only between modules (the terminal model's `Terminal`, the IME editing model's `ImeEditor`, `TerminalGate`, and so on) are marked `@InternalVtfxApi` and excluded from the public API ([decisions/0016](../decisions/0016-public-api.md)).

### 2.1 Threads

The thread conventions are described in section 1 of [session-and-threads.md](session-and-threads.md) ([decisions/0024](../decisions/0024-thread-conventions.md)). The main points are as follows.

- Modules that do not depend on Android (`vtfx-core`, `vtfx-input`, `vtfx-gate`, `vtfx-session`, `vtfx-font`, `vtfx-font-gate`) do not assume a particular thread. Only `vtfx-gate-android`, `vtfx-compose`, and `vtfx-font-gate-android` deal with the main thread.
- The KDoc of each public type says whether it is immutable, thread-safe, not thread-safe (the caller serializes), or main-thread-only.
- Unless stated otherwise, the app's code is called synchronously on the same thread as the call that triggered it. It is not called while holding an internal lock, except locks that only serialize calls, and reentry from the app's code is detected and rejected where it can be. Exceptions from the app's code are not swallowed.

## 3. Each module

### 3.1 vtfx-core

- **Parser**: Splits bytes into tokens following layer 1 of the references (ECMA-48 and the DEC ANSI parser state machine; sections 1 and 2 of [emulation.md](emulation.md)). Includes UTF-8 decoding.
- **Terminal model**: Applies tokens to update the screen state. This covers the main and alternate screens, scrollback, the cursor, the various modes, character attributes, and hyperlink attributes (URLs are kept as plain data). The supported control functions are in sections 3 to 5 of [emulation.md](emulation.md).
- **Character width**: Determines cell widths according to the Unicode version, the handling of East Asian Ambiguous, and the policy on grapheme clusters (requirement 5.2). The tables are generated from the Unicode Character Database in `third_party/unicode` by `scripts/generate_unicode_tables.py` and committed, so that the sources build without Gradle (section 7 of [emulation.md](emulation.md)).
- **Output**: Emits `TerminalEvent`s as the model is updated. This is a sealed type, with, for example, the following.
  - `ReplyRequested(query)`: The host queried the state (DA, DSR, DECRQSS, and so on). `query` is a sealed type representing the kind of query, and the values needed for the reply are taken from the terminal state. It does not include strings sent by the host.
  - `ClipboardWriteRequested(selection, text)`, `ClipboardReadRequested(selection)`: OSC 52.
  - `TitleChanged(title)`, `Bell`.
- **Images**: The kitty graphics protocol (section 9 of [emulation.md](emulation.md)). The `graphics` package parses the commands of APC strings, decodes the images (PNG in Kotlin, not with the platform's native decoders), and keeps the images and placements of each screen, anchored to lines so that they move with the text. Replies go out as `ReplyRequested` like other queries. The images that the display draws are in the snapshot, as internal API.
- **Resource limits**: Limits are set on the number of rows, columns, scrollback lines, the length of strings such as OSC, the number of parameters, and so on, and input beyond them is truncated (requirement 6.1). The number of scrollback lines, the string length, and the memory of images can be set with `TerminalLimits`, and the others are constants (section 10 of [emulation.md](emulation.md)).
- **Key types**: Defines the value types that represent keys the user pressed (`KeyStroke`, `TerminalKey`, `KeyModifiers`). They have no behavior. The editing model of `vtfx-input` receives them and `vtfx-gate` encodes them, so they live in core, which both can refer to ([decisions/0083](../decisions/0083-gate.md)).
- **Threads**: The model of one session is operated from only one thread at a time (it is not thread-safe; which thread does not matter. `TerminalSession` in `vtfx-session` serializes it with a lock). Rendering is given an immutable snapshot made after the update.

`vtfx-core` has no means at all of sending to the host. Even when something needs to be returned to the host, all it can do is issue a request with a `TerminalEvent`.

### 3.2 vtfx-input

- **IME editing model**: Implements the buffer shown to the IME, the computation of differences from committed text, and resetting the history, as pure logic that does not depend on Android types. The policies taken from measurements of IMEs are as follows (details in [ime-gboard-device.md](../research/ime-gboard-device.md)).
  - Show the IME only the text typed through the IME since the last reset (the input history). Do not show it the terminal's screen contents.
  - Convert edits to committed text into DELs and additional text sent to the host.
  - Reset the history on Enter (including a committed newline), non-character keys, the cursor moving to another line due to host output, and so on. Do not reset on BS.
  - Reject replacements outside the history. Only deletions outside it are accepted, as the user's BS, as DELs up to the limit of the input history (6.1 of [ime-contract.md](ime-contract.md)).
  - The contract for each call is defined in [ime-contract.md](ime-contract.md).
- The `InputConnection` in `vtfx-compose` is a thin adapter that delegates calls directly to this model. This makes it possible to replay traces recorded with `tools/ime-probe` in JVM tests and verify the bytes sent (requirement 7.2, "Capturing the behavior of IMEs on real devices").

### 3.3 vtfx-gate (subject to review)

Only this module can create bytes to send to the host. What it sends, how it replies, and how it decides on the clipboard and URLs are described in [gate-policy.md](gate-policy.md).

- **`HostWriter`**: The sole holder of the host connection (`HostConnection`) that the app passes. It limits what can be sent to a method per kind. `TerminalSession` creates it right away from the connection it receives. The whole class is `@InternalVtfxApi` ([decisions/0016](../decisions/0016-public-api.md), [decisions/0027](../decisions/0027-session-takes-connection.md)).
  - `sendKey(stroke, modes)`: A key. Encodes the key and modifiers into the same bytes as xterm according to the terminal modes (application cursor keys and so on), or, while the host set flag 1 of the kitty keyboard protocol, into the forms of that protocol (section 2.2 of [gate-policy.md](gate-policy.md)). The input is only the user's key operation and modes that are enums or booleans, and there is no path by which an arbitrary string sent by the host can get mixed in ([decisions/0083](../decisions/0083-gate.md)).
  - `sendText(text)`: Text committed by the IME and the like, on one line. Decides the handling of control characters in one place, and refuses line breaks, which only Enter and pastes send.
  - `sendDeletes(count)`: DELs that erase already-sent text in IME editing. The count has a limit (65536).
  - `sendPaste(text, bracketed)`: A paste. Removes control characters so that the end sequence of bracketed paste cannot be formed, and then brackets it (requirement 6.1, "Escaping from bracketed paste").
  - `sendReply(query, colors)`: The reply to `ReplyRequested`. The reply string is built, for the corresponding query, only from constants and numbers from the terminal state. Strings sent by the host are not included in replies.
  - `sendMouse(report, tracking, encoding)`, `sendFocus(focused)`: Mouse and focus reports, only when the host requested them.
- **Policy**: Allows or denies requests such as reading and writing the clipboard and opening URLs according to the app's settings (`GatePolicy`). The default is the safe side (requirement 6.5).
  - Example: Reading and writing with OSC 52 are both denied by default; when the app allows writing, it has a length limit (section 4.1 of [gate-policy.md](gate-policy.md), [decisions/0083](../decisions/0083-gate.md)).
  - Example: Hyperlinks are opened only on an explicit action by the user. The actual URL is shown before opening, and URLs whose displayed form may differ from where they go are refused (section 5 of [gate-policy.md](gate-policy.md)).
- **The user's copy and paste**: `TerminalGate.copyToClipboard` and `readClipboardForPaste` perform them for explicit actions of the user, to which the OSC 52 policy does not apply; the pasted text is then sent with `sendPaste`. `TerminalSession` hands the clipboard and the URL opener to the gate and does not call them itself (section 4.2 of [gate-policy.md](gate-policy.md), [decisions/0083](../decisions/0083-gate.md)).
- Performing capabilities is delegated to interfaces implemented by `vtfx-gate-android` (`ClipboardAccess`, `UrlOpener`). `vtfx-gate` itself does not depend on Android.
- `TerminalGate` receives `vtfx-core`'s `TerminalEvent`s, handles replies and clipboard requests, and decides on opening URLs by user action. The defaults are in [gate-policy.md](gate-policy.md), and the reasons for them in [decisions/0083](../decisions/0083-gate.md).

### 3.4 vtfx-gate-android (subject to review)

- The only module that deals with `ClipboardManager` and the `Intent` that opens URLs.
- A thin implementation that performs only the requests `vtfx-gate` allowed. It has no decision logic. What `AndroidClipboard` and `AndroidUrlOpener` do is in section 6 of [gate-policy.md](gate-policy.md).

### 3.5 vtfx-compose (public API)

- **Rendering**: Draws snapshots on the Compose Canvas. Includes CJK font fallback, drawing box-drawing and block elements as shapes, and the images of the kitty graphics protocol, each made into a bitmap once (sections 7.3, 8.2, and 9.6 of [emulation.md](emulation.md), [decisions/0014](../decisions/0014-performance.md)). The appearance is changed with `TerminalStyle` (colors, font, glyph locale, cursor shape).
- **Input**: The `InputConnection` adapter (connected to the IME through a `View` placed behind the terminal; section 12 of [ime-contract.md](ime-contract.md)), physical keyboards, and touch interactions (scrolling, mouse reporting, text selection, link taps; sections 2 to 7 of [compose-input.md](compose-input.md)). The app can switch to direct input, which asks the IME for input without predictive text (section 13 of [ime-contract.md](ime-contract.md)).
- **IME display**: Overlays composing text at the cursor position, and tells the IME the position of the candidate window with `CursorAnchorInfo`.
- **Accessibility**: Exposes the displayed terminal content as Compose semantics nodes per line (requirement 6.3, section 11 of [compose-input.md](compose-input.md)). It is a separate path from the IME, and they do not constrain each other.
- **Size**: Computes the number of rows and columns from the display size. The first size is applied to the session at once, and later sizes only after the size has stayed unchanged for 150 ms, so the host is notified once. The same mechanism works for the IME show/hide, rotation, and multi-window animations (requirement 5.5, section 3 of [session-and-threads.md](session-and-threads.md), [decisions/0013](../decisions/0013-resize-and-lifecycle.md)).
- **Session**: The app owns the `TerminalSession` (`vtfx-session`, 3.6). It lives outside the Composable, so it survives configuration changes. The Composable only connects to the session and displays it. The display state (the scrollback position, the selection, invalidating rendering, batching resizes, waiting for synchronized output) is held by a `TerminalDisplay` per `Terminal`, and goes away with the Composable. Session changes are notified outside the lock, from the thread that made the change, so `TerminalDisplay` forwards them to the main thread (sections 2.1 and 4 of [session-and-threads.md](session-and-threads.md), [decisions/0026](../decisions/0026-session-module.md)).
- **Extra key bar**: Not included in the library. So that apps can build an extra key bar, there is a public API for sending key input to the session (requirement 5.4).

### 3.6 vtfx-session

- **Session**: `TerminalSession` ties together the terminal of `vtfx-core`, the IME editing model of `vtfx-input`, and the `TerminalGate` of `vtfx-gate`, and interprets host output, sends user input, and handles host requests (requirements 3 and 4.1, [decisions/0026](../decisions/0026-session-module.md)). It does not depend on Android or Compose, so JVM programs can emulate a terminal without a UI, and app tests can use the same session as production.
- **Threads**: It is thread-safe and does not assume a thread. One lock guards the state, another serializes `feed` up to the end of the gate's handling of its requests, and a third serializes user input up to sending (section 2.2 of [session-and-threads.md](session-and-threads.md)). The app's code is called on the thread of the call that triggered it, outside the lock that guards the state. It has no timers and no display state, and holds no resources besides memory, so there is no `close()`.
- **Boundary with the display**: The display (`vtfx-compose`) holds the display state, batches resizes, and receives the session's changes through an observer used only between modules.

The locks, the reentry checks, the notifications, the division between the session and the display, resizing, and the lifetime are described in sections 2 to 4 of [session-and-threads.md](session-and-threads.md).

## 4. Data flow

**Host output → screen**

```
App ─ bytes ─▶ TerminalSession (vtfx-session) ─▶ vtfx-core (parser → model)
                                        ├─▶ snapshot ─▶ vtfx-compose (rendering, a11y)
                                        └─▶ TerminalEvent ─▶ vtfx-gate (policy)
                                                               ├─▶ HostWriter (reply)
                                                               └─▶ vtfx-gate-android (clipboard)
```

**User input → host**

```
Keys, IME ─▶ vtfx-compose (adapter) ─▶ vtfx-session ─▶ vtfx-input (editing model)
                                                             └─▶ vtfx-gate (HostWriter, encoding) ─ bytes ─▶ app ─▶ host
```

## 5. Verifying the security boundary

The checks of the module boundaries read Gradle's model of the build and the compiled classes and artifacts, so that they hold against changes written to get around them, not only against obvious mistakes. What they do not cover (the build itself, how allowed APIs are used, tests) is left to review, backed by being able to audit afterward (requirement 6.1). How code outside review calls the gate, such as whether text that it sends is what the user typed or whether the user asked for a copy, is guarded against mistakes: the gate checks what it can from the values and fails fast, types narrow what a caller can pass or see, and the KDoc states the caller's contract ([decisions/0075](../decisions/0075-adversaries-are-inputs.md)). How the checks work and what each rule means is in [boundaries.md](boundaries.md); why they work this way is in [decisions/0044](../decisions/0044-boundaries-in-gradle-and-classes.md).

| Verification | Method |
|---|---|
| Direction of dependencies | `config/boundaries.toml` lists, for each module, the modules it may depend on, and the build checks every configuration of every project against it, both the dependencies declared and those Gradle resolves. Tests have no list of their own (section 3 of [boundaries.md](boundaries.md)). |
| Location of capabilities | The build checks the compiled main classes of every library module against `config/boundaries.toml`: what only some modules may use or refer to (the connection to the host, the clipboard, opening other apps, fonts from files) and what no module may (reflection, processes, the network), an allowlist for each module of everything outside the project its classes may refer to, and the packages in which only the reviewed modules may declare classes: `com.google.vtfx.gate` for `vtfx-gate` and `vtfx-gate-android`, and `com.google.vtfx.font.gate` for `vtfx-font-gate` and `vtfx-font-gate-android` (sections 4 and 5 of [boundaries.md](boundaries.md)). The classes of tests are not checked, since they are not shipped. |
| What artifacts bring into apps | The build checks the published jar or AAR of each library for what an app takes in with it besides classes: permissions and components in the manifest, native code, services for `ServiceLoader`, lint checks, and embedded jars (section 6 of [boundaries.md](boundaries.md)). |
| Listing what is under review | `security_review` in `config/protected-paths.toml` lists the paths subject to review. Files for dependencies and the build configuration are included here too (requirement 6.1). |
| Integrity of dependencies | Dependencies are pinned by version, the Gradle distribution and the wrapper jar by checksum (`scripts/check.sh` checks the jar against the checksum that Gradle publishes), and GitHub Actions by commit SHA. `gradle/verification-metadata.xml` lists the SHA-256 checksum of every artifact the build resolves, and Gradle fails the build when one is missing or differs ([decisions/0035](../decisions/0035-dependency-verification.md)). It covers builds of this repository only, not apps that depend on the published artifacts. |
| Reporting changes | CI (`.github/workflows/check.yml`) uses `scripts/report_protected_changes.py` to report a list of changes that touch paths under review. It does not fail. |
| Human review | A human reviews the changes to the paths under review. The item "Changes to protected paths" in [review-queue.md](../review-queue.md) names the commit up to which the last review covered the tree, and the changes made after it wait there until the next one. |

## 6. Test structure

The tests of each module are listed below. Where the oracles live, the gate rules, and how each kind of test runs are in [testing.md](testing.md), which is binding on what gates; "Gate?" says whether `scripts/check.sh` runs the tests as a gate (section 9 of [testing.md](testing.md)).

| Target | Tests | Gate? |
|---|---|---|
| `vtfx-core` | JVM unit tests. Spec tests owned by humans (`spec/core`, section 3 of [testing.md](testing.md)). Replay tests of recordings of real apps (section 10 of [testing.md](testing.md)). | Yes |
| `vtfx-core`, `vtfx-gate`, `vtfx-input`, `vtfx-font`, `vtfx-font-gate` | Fuzzing, one target per module (section 7 of [testing.md](testing.md)). | A 30-second run each. Long runs are separate |
| `vtfx-input` | IME trace replay tests (`spec/ime`, section 4 of [testing.md](testing.md)). Tests with hostile IME calls. | Yes |
| `vtfx-gate` | Table-driven tests of key encoding. Unit tests of replies, pastes, and policies, including malicious inputs (section 12 of [testing.md](testing.md)). | Yes |
| `vtfx-gate-android` | Unit tests with Robolectric of the clipboard and of opening URLs. | Yes |
| `vtfx-session` | JVM unit tests of `TerminalSession`, including its threads and notifications. | Yes |
| `vtfx-compose` | Unit tests with Robolectric, and screenshot tests (section 5 of [testing.md](testing.md)). | Yes |
| `vtfx-font` | Unit tests with fonts that test code builds, a golden of the canonical form, and cross-checks with the verifier of `vtfx-font-gate` (section 2 of [testing.md](testing.md)). | Yes |
| `vtfx-font-gate` | Unit tests of the verifier with canonical fonts kept as files, and with changes to them. | Yes |
| `vtfx-font-gate-android` | No unit tests of its own; `SystemFontsTest` in the rendering e2e loads sanitized fonts through it. | No (reports only) |
| `vtfx-sample` | Unit tests of the fake host. | Yes |
| Whole | e2e on the emulator (IME e2e with a real IME, and rendering e2e with hardware acceleration; section 8 of [testing.md](testing.md)), esctest2 (section 11 of [testing.md](testing.md)). | No (reports only) |

## 7. Toolchain

- Kotlin, Gradle (Kotlin DSL), Jetpack Compose.
- The package name and Maven groupId are `com.google.vtfx`.
- CI is GitHub Actions. However, workflows only run commands that can also run locally (the check script, and the generation and check of the API reference), so that nothing depends on CI but the deployment of the API reference of the default branch to GitHub Pages ([decisions/0074](../decisions/0074-api-reference-of-the-default-branch.md)).
- The public API uses Kotlin's explicit API mode to make the public surface explicit.
- Every module is compiled with Kotlin's unused return value checker, so that builds that check the use of results, as CheckReturnValue does, take the sources as they are ([decisions/0072](../decisions/0072-results-are-used.md)).
- Compatibility of the public API is checked by `check` by comparing ABI dumps and dumps of the Kotlin signatures (each module's `api/`) (requirement 6.5, [decisions/0016](../decisions/0016-public-api.md)).
- minSdk is 29 (Android 10), weighed per feature (requirement 6.4, [decisions/0039](../decisions/0039-minsdk-29.md)). 29 is the first API level with `RenderNode`, which the line cache uses, and with foreground-only clipboard reads. On API 29 to 33, IMEs correct text without `replaceText` and the sensitive mark on the rows has no effect, and on API 29 to 32 the clipboard has no copy preview; the decision accepts these gaps. The two IME calls newer than 29 are guarded in `TerminalInputConnection` (sections 3 and 6 of [ime-contract.md](ime-contract.md)).

## 8. What this document does not decide

The following are not decided in this document; they were decided in [decisions/](../decisions/README.md) and elsewhere.

- The names and parameters of individual public APIs
- The range of supported sequences, and the handling of TERM and terminfo
- The concrete policy on character width
- The details of the rendering approach (text drawing, glyph caching), and performance targets
- Resource limits
