# vtfx architecture

Status: Proposal (not approved)

This document defines the module structure, responsibilities, direction of dependencies, and security boundaries that satisfy [REQUIREMENTS.md](../REQUIREMENTS.md). The details of individual APIs and the contract for each IME call are covered in separate documents.

## 1. Pillars of the design

1. **Gather security capabilities in one place.** Untrusted input may flow anywhere. However, only small modules subject to review can perform operations that affect the outside (sending to the host, the clipboard, opening URLs). Other modules do not even hold the references needed to perform them.
2. **Separate the logic from Android.** The terminal emulation and the IME editing model are pure Kotlin/JVM code that does not depend on Android. This makes deterministic tests, fuzzing, and replaying recorded IME traces fast and possible without an emulator.
3. **Represent side effects as values.** The terminal model does not itself perform replies to the host or writes to the clipboard; it outputs typed values that represent "what was requested". The modules subject to review decide whether and how to perform them.

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

Dependencies are allowed only in the direction of the arrows. `vtfx-core` does not depend on other vtfx modules. The font modules are not in the figure: `vtfx-font-gate` depends on no other vtfx module, and `vtfx-font` and `vtfx-font-gate-android` depend on it (`vtfx-font-gate-android` also on `vtfx-core`, at compile time only, for `InternalVtfxApi`). Besides the arrows in the figure, `vtfx-compose` also depends on `vtfx-core`, `vtfx-input`, `vtfx-gate`, `vtfx-gate-android`, `vtfx-font`, `vtfx-font-gate`, and `vtfx-font-gate-android` to use their types.

Security review covers whole modules, never single files, and the modules under review are named with `gate` ([decisions/0041](../decisions/0041-security-review-by-module.md)).

| Module | Kind | Responsibility (one sentence) | Review |
|---|---|---|---|
| `vtfx-core` | JVM | Interprets the host's output bytes and updates the terminal state (screen, scrollback, modes, cursor). | Not needed |
| `vtfx-input` | JVM | Converts the user's key input and IME editing operations into values of input to send to the host. | Not needed |
| `vtfx-gate` | JVM | Is solely responsible for assembling the bytes sent to the host and for allowing or denying requests with external effects. | **Needed** |
| `vtfx-gate-android` | Android | Performs the operations `vtfx-gate` allowed with Android APIs (clipboard, Intent). | **Needed** |
| `vtfx-session` | JVM | Ties together the terminal, the IME editing model, and requests to the host, and provides a terminal session without a UI. | Not needed |
| `vtfx-compose` | Android | Displays the session as a Compose UI and receives input. | Not needed |
| `vtfx-font` | JVM | Reads fonts that users choose and writes them again in one canonical form ([decisions/0040](../decisions/0040-sanitized-user-fonts.md)). | Not needed |
| `vtfx-font-gate` | JVM | Checks that bytes are a font in the canonical form, which is all that reaches the platform's native font code, and holds them as `SanitizedFont` ([decisions/0041](../decisions/0041-security-review-by-module.md)). | **Needed** |
| `vtfx-font-gate-android` | Android | Makes typefaces from `SanitizedFont`s, the only code that hands font data to the platform. | **Needed** |

Apps depend on `vtfx-compose`. `vtfx-compose` exposes with `api` `vtfx-core`, `vtfx-gate`, and `vtfx-session` for the types the public API uses (`TerminalSession`, `HostConnection`, `KeyStroke`, `TerminalSnapshot`, and so on), `vtfx-gate-android` so that apps can use `AndroidClipboard` and `AndroidUrlOpener`, and `vtfx-font` and `vtfx-font-gate` for sanitizing fonts and for the `SanitizedFamily`s that `TerminalFont.Sanitized` takes. `vtfx-input` and `vtfx-font-gate-android`, which have no public API, are `implementation`. JVM programs without a UI depend on `vtfx-session`. The inside of each module is `internal`, and `public` declarations used only between modules (the terminal model's `Terminal`, the IME editing model's `ImeEditor`, `TerminalGate`, and so on) are marked `@InternalVtfxApi` and excluded from the public API ([decisions/0016](../decisions/0016-public-api.md)).

### 2.1 Threads

The thread conventions are defined in [decisions/0024](../decisions/0024-thread-conventions.md). The main points are as follows.

- Modules that do not depend on Android (`vtfx-core`, `vtfx-input`, `vtfx-gate`, `vtfx-session`, `vtfx-font`, `vtfx-font-gate`) do not assume a particular thread. Only `vtfx-gate-android` and `vtfx-compose` deal with the main thread.
- The KDoc of each public type says whether it is immutable, thread-safe, not thread-safe (the caller serializes), or main-thread-only.
- Unless stated otherwise, the app's code is called synchronously on the same thread as the call that triggered it. It is not called while holding an internal lock, and reentry from the app's code is detected and rejected. Exceptions from the app's code are not swallowed.

## 3. Each module

### 3.1 vtfx-core

- **Parser**: Splits bytes into tokens following layer 1 of [conformance-references.md](../research/conformance-references.md) (ECMA-48 and the DEC ANSI parser state machine). Includes UTF-8 decoding.
- **Terminal model**: Applies tokens to update the screen state. This covers the main and alternate screens, scrollback, the cursor, the various modes, character attributes, and hyperlink attributes (URLs are kept as plain data).
- **Character width**: Determines cell widths according to the Unicode version, the handling of East Asian Ambiguous, and the policy on grapheme clusters (requirement 5.2). The tables are generated from the Unicode Character Database in `third_party/unicode` by `scripts/generate_unicode_tables.py` and committed, so that the sources build without Gradle ([decisions/0011](../decisions/0011-unicode.md), [decisions/0029](../decisions/0029-import-without-gradle.md)).
- **Output**: Emits `TerminalEvent`s as the model is updated. This is a sealed type, with, for example, the following.
  - `ReplyRequested(query)`: The host queried the state (DA, DSR, DECRQSS, and so on). `query` is a sealed type representing the kind of query, and the values needed for the reply are taken from the terminal state. It does not include strings sent by the host.
  - `ClipboardWriteRequested(selection, text)`, `ClipboardReadRequested(selection)`: OSC 52.
  - `TitleChanged(title)`, `Bell`.
- **Images**: The kitty graphics protocol ([decisions/0038](../decisions/0038-kitty-graphics.md)). The `graphics` package parses the commands of APC strings, decodes the images (PNG in Kotlin, not with the platform's native decoders), and keeps the images and placements of each screen, anchored to lines so that they move with the text. Replies go out as `ReplyRequested` like other queries. The images that the display draws are in the snapshot, as internal API.
- **Resource limits**: Limits are set on the number of rows, columns, scrollback lines, the length of strings such as OSC, the number of parameters, and so on, and input beyond them is truncated (requirement 6.1). The number of scrollback lines and the string length can be set with `TerminalLimits`, and the others are constants ([decisions/0002](../decisions/0002-resource-limits.md)).
- **Key types**: Defines the value types that represent keys the user pressed (`KeyStroke`, `TerminalKey`, `KeyModifiers`). They have no behavior. The editing model of `vtfx-input` receives them and `vtfx-gate` encodes them, so they live in core, which both can refer to ([decisions/0005](../decisions/0005-key-bytes-through-gate.md)).
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

Only this module can create bytes to send to the host.

- **`HostWriter`**: The sole holder of the host connection (`HostConnection`) that the app passes. It limits what can be sent to a method per kind. `TerminalSession` creates it right away from the connection it receives. The whole class is `@InternalVtfxApi` ([decisions/0016](../decisions/0016-public-api.md), [decisions/0027](../decisions/0027-session-takes-connection.md)).
  - `sendKey(stroke, modes)`: A key. Encodes the key and modifiers into the same bytes as xterm according to the terminal modes (application cursor keys and so on). The input is only the user's key operation and modes that are enums or booleans, and there is no path by which an arbitrary string sent by the host can get mixed in ([decisions/0005](../decisions/0005-key-bytes-through-gate.md)).
  - `sendText(text)`: Text committed by the IME and the like. Decides the handling of control characters (turning `\n` into CR and so on) in one place.
  - `sendDeletes(count)`: DELs that erase already-sent text in IME editing. The count has a limit.
  - `sendPaste(text, bracketed)`: A paste. Removes control characters so that the end sequence of bracketed paste cannot be formed, and then brackets it (requirement 6.1, "Escaping from bracketed paste").
  - `sendReply(query, colors)`: The reply to `ReplyRequested`. The reply string is built, for the corresponding query, only from constants and numbers from the terminal state. Strings sent by the host are not included in replies.
  - `sendMouse(report, tracking, encoding)`, `sendFocus(focused)`: Mouse and focus reports, only when the host requested them.
- **Policy**: Allows or denies requests such as reading and writing the clipboard and opening URLs according to the app's settings (`GatePolicy`). The default is the safe side (requirement 6.5).
  - Example: Reading with OSC 52 is denied by default. Writing is allowed, with a length limit.
  - Example: Hyperlinks are opened only on an explicit action by the user. The actual URL is shown before opening.
- **The user's copy and paste**: `TerminalGate.copyToClipboard` and `readClipboardForPaste` perform them for explicit actions of the user, to which the OSC 52 policy does not apply; the pasted text is then sent with `sendPaste`. `TerminalSession` hands the clipboard and the URL opener to the gate and does not call them itself ([decisions/0042](../decisions/0042-user-clipboard-through-gate.md)).
- Performing capabilities is delegated to interfaces implemented by `vtfx-gate-android` (`ClipboardAccess`, `UrlOpener`). `vtfx-gate` itself does not depend on Android.
- `TerminalGate` receives `vtfx-core`'s `TerminalEvent`s, handles replies and clipboard requests, and decides on opening URLs by user action. The defaults are in [decisions/0006](../decisions/0006-gate-defaults.md).

### 3.4 vtfx-gate-android (subject to review)

- The only module that deals with `ClipboardManager` and the `Intent` that opens URLs.
- A thin implementation that performs only the requests `vtfx-gate` allowed. It has no decision logic.

### 3.5 vtfx-compose (public API)

- **Rendering**: Draws snapshots on the Compose Canvas. Includes CJK font fallback, drawing box-drawing and block elements as shapes, and the images of the kitty graphics protocol, each made into a bitmap once ([decisions/0038](../decisions/0038-kitty-graphics.md)) ([decisions/0011](../decisions/0011-unicode.md), [decisions/0014](../decisions/0014-performance.md)). The appearance is changed with `TerminalStyle` (colors, font, glyph locale, cursor shape).
- **Input**: The `InputConnection` adapter (connected to the IME through a `View` placed behind the terminal; [decisions/0008](../decisions/0008-ime-view-and-selection.md)), physical keyboards, and touch interactions (scrolling, mouse reporting, text selection, link taps; [decisions/0012](../decisions/0012-touch-and-selection.md), [decisions/0019](../decisions/0019-extensibility.md)). The app can switch to direct input, which asks the IME for input without predictive text ([decisions/0017](../decisions/0017-ime-scope.md)).
- **IME display**: Overlays composing text at the cursor position, and tells the IME the position of the candidate window with `CursorAnchorInfo`.
- **Accessibility**: Exposes the displayed terminal content as Compose semantics nodes per line (requirement 6.3, [decisions/0015](../decisions/0015-accessibility.md)). It is a separate path from the IME, and they do not constrain each other.
- **Size**: Computes the number of rows and columns from the display size. The terminal size changes only after the size has stayed unchanged for 150 ms, so the host is notified once. The same mechanism works for the IME show/hide, rotation, and multi-window animations (requirement 5.5, [decisions/0013](../decisions/0013-resize-and-lifecycle.md)).
- **Session**: The app owns the `TerminalSession` (`vtfx-session`, 3.6). It lives outside the Composable, so it survives configuration changes. The Composable only connects to the session and displays it. The display state (the scrollback position, the selection, invalidating rendering, batching resizes, waiting for synchronized output) is held by a `TerminalDisplay` per `Terminal`, and goes away with the Composable. Session changes are notified outside the lock, from the thread that made the change, so `TerminalDisplay` forwards them to the main thread ([decisions/0026](../decisions/0026-session-module.md)).
- **Extra key bar**: Not included in the library. So that apps can build an extra key bar, there is a public API for sending key input to the session (requirement 5.4).

### 3.6 vtfx-session

- **Session**: `TerminalSession` ties together the terminal of `vtfx-core`, the IME editing model of `vtfx-input`, and the `TerminalGate` of `vtfx-gate`, and interprets host output, sends user input, and handles host requests (requirements 3 and 4.1, [decisions/0026](../decisions/0026-session-module.md)). It does not depend on Android or Compose, so JVM programs can emulate a terminal without a UI, and app tests can use the same session as production.
- **Threads**: It is thread-safe and does not assume a thread. One lock guards the state, and another lock serializes user input up to sending, so that it reaches the host in the order the editing model produced it. The app's code (listeners, the clipboard, opening URLs) is called outside the locks, on the thread of the call that triggered it ([decisions/0024](../decisions/0024-thread-conventions.md)).
- **Reentry**: `feed` from inside `HostConnection.write` is rejected. `ClipboardAccess.read` (for pasting from the clipboard) is called before taking any lock.
- **Notifications**: `TerminalSessionListener` is called on the thread of the triggering call, after the locks are released, and may call the session. Bells are batched to one notification per `feed`. Value notifications (`onResize`, `onTitleChanged`, `onStickyModifiersChanged`) report the latest value, so that even when the value is changed from several threads, the last notification is the current value ([decisions/0026](../decisions/0026-session-module.md)).
- **Lifetime**: It holds no resources besides memory and schedules no timers, so there is no `close()`; dropping it lets GC collect it.
- **Boundary with the display**: It has no timers and no display state. The display (`vtfx-compose`) receives screen changes, sent input, and requests to restart the IME through an observer used only between modules (`SessionObserver`; several may be registered), and IME operations, including reading the composing text for drawing, go through the session's lock with `edit`. The contents are divided as follows.

| vtfx-session (`TerminalSession`) | vtfx-compose (`TerminalDisplay` per `Terminal`) |
|---|---|
| The terminal, `TerminalGate`, `feed`, and handling requests to the gate (outside the lock) | Forwarding notifications to the main thread |
| Bells and titles, and their notifications | Invalidating rendering |
| `ImeEditor`, and deciding to reset the input history (5.1 of [ime-contract.md](ime-contract.md)) | The connection with the IME's `View` |
| Sending keys, text, pastes, mouse reports, and focus reports; opening URLs | The scrollback position, following scrollback, the selection, and copying it |
| `stickyModifiers`, `directInput` | Batching resizes over 150 ms ([decisions/0013](../decisions/0013-resize-and-lifecycle.md)) |
| `resize` (applied immediately), `snapshot`, modes | Waiting up to 200 ms to draw during synchronized output |
| Answering color queries with the colors the display set (not answered without a display) | Setting the colors and the default cursor shape of `TerminalStyle` |

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

As in requirement 6.1, in the initial phase the verification is at the level of a guardrail, backed by being able to audit afterward.

| Verification | Method |
|---|---|
| Direction of dependencies | `config/boundaries.toml` lists, for each module, the modules it may depend on. The convention plugin `vtfx.boundaries`, which the root project applies to every project, checks that the projects of the build are the modules listed, and checks the project dependencies of every configuration of every project against the list: those declared, and every edge to a project in the graphs that Gradle resolves, so that dependencies added by plugins, on resolution, or by substitution are seen too. Tests have no list of their own ([decisions/0044](../decisions/0044-boundaries-in-gradle-and-classes.md)). |
| Location of capabilities | `restricted_apis` in the same `boundaries.toml` lists APIs such as `ClipboardManager`, `Intent`, `startActivity`, `HostConnection`, `ClipboardAccess` and `UrlOpener` (allowed in `TerminalSession.kt`, which only hands them to vtfx-gate, and in tests, which implement fakes; [decisions/0027](../decisions/0027-session-takes-connection.md), [decisions/0042](../decisions/0042-user-clipboard-through-gate.md)), reflection, launching processes, the network, loading fonts from files and the like ([decisions/0020](../decisions/0020-fonts.md)), and declaring the package `com.google.vtfx.font.gate` or its subpackages (only the font gate modules may, so that no other module can put a class of the same name in front of a reviewed one on the class path; [decisions/0041](../decisions/0041-security-review-by-module.md)), with the paths allowed to use them. The lists include the same capabilities under other names: Compose's clipboard (`LocalClipboardManager`, `LocalClipboard`, `ClipEntry`) and URL opening (`LocalUriHandler`, `UriHandler`, `LinkAnnotation.Url`), AndroidX's `ShareCompat` and `CustomTabsIntent`, `java.lang.invoke` and Kotlin's `kotlin.reflect.full`/`jvm`, and `javax.net` and `DownloadManager`. `check_boundaries.py` matches each line of the source with regular expressions. This is a guardrail against obvious mistakes: a change written to get around it can still do so (for example, through an API under a name not listed), and checking compiled classes or the resolved dependency graph instead is deferred until after publication ([review-queue.md](../review-queue.md)). |
| Listing what is under review | `security_review` in `config/protected-paths.toml` lists the paths subject to review. Files for dependencies and the build configuration are included here too (requirement 6.1). |
| Integrity of dependencies | Dependencies are pinned by version, the Gradle distribution by checksum, and GitHub Actions by commit SHA. `gradle/verification-metadata.xml` lists the SHA-256 checksum of every artifact the build resolves, and Gradle fails the build when one is missing or differs ([decisions/0035](../decisions/0035-dependency-verification.md)). It covers builds of this repository only, not apps that depend on the published artifacts. |
| Reporting changes | CI (`.github/workflows/check.yml`) uses `scripts/report_protected_changes.py` to report a list of changes that touch paths under review. It does not fail. |
| Human review | A human reviews the changes to the paths under review. The last review covered the whole tree (2026-10-15). Changes made after a review wait in [review-queue.md](../review-queue.md) until the next one. |

## 6. Test structure

| Target | Tests | Gate? |
|---|---|---|
| `vtfx-core` | JVM unit tests. Spec tests owned by humans (`spec/core`). Replay tests that compare recordings of real apps with a reference terminal. | Yes |
| `vtfx-core`, `vtfx-gate`, `vtfx-input`, `vtfx-font`, `vtfx-font-gate` | Fuzzing (detecting exceptions, non-terminating states, memory exhaustion, and each module's invariants). | A 30-second run each. Long runs are separate |
| `vtfx-input` | IME trace replay tests (`spec/ime`). Tests with hostile IME calls. | Yes |
| `vtfx-gate` | Table-driven tests of key encoding. Unit tests of replies, pastes, and policies, including malicious inputs. | Yes |
| `vtfx-compose` | Unit tests with Robolectric, and screenshot tests (agents manage provisional goldens, and humans approve them). | Yes |
| `vtfx-font` | Unit tests with fonts that test code builds. A golden of the canonical form: the fonts that `vtfx-font-gate`'s tests use are what sanitizing writes. Cross-checks of the verifier of `vtfx-font-gate` with the readers and the writer. | Yes |
| `vtfx-font-gate` | Unit tests of the verifier with canonical fonts that `vtfx-font` writes, kept as files, and with changes to them. | Yes |
| Whole | e2e on the emulator (IME e2e with a real IME, and rendering e2e with hardware acceleration), esctest2. | No (reports only) |

Details are in [testing.md](testing.md).

Tests, fixtures, and goldens owned by humans are gathered in dedicated directories, and CI reports changes by agents (requirement 7.2).

## 7. Toolchain

- Kotlin, Gradle (Kotlin DSL), Jetpack Compose.
- The package name and Maven groupId are `com.google.vtfx`.
- CI is GitHub Actions. However, workflows only call the check script, which can also run locally, so that nothing depends on CI.
- The public API uses Kotlin's explicit API mode to make the public surface explicit.
- Compatibility of the public API is checked by `check` by comparing ABI dumps and dumps of the Kotlin signatures (each module's `api/`) (requirement 6.5, [decisions/0016](../decisions/0016-public-api.md)).
- minSdk is 29 (Android 10), weighed per feature (requirement 6.4, [decisions/0039](../decisions/0039-minsdk-29.md)). It started at 34, the first API level with `InputConnection.replaceText`. 29 is the first with `RenderNode`, which the line cache uses, and with foreground-only clipboard reads. On API 29 to 33, IMEs correct text without `replaceText`, the sensitive mark on the rows has no effect, and the clipboard has no copy preview; the decision accepts these gaps. The two IME calls newer than 29 are guarded in `TerminalInputConnection` (sections 3 and 6 of [ime-contract.md](ime-contract.md)).

## 8. What this document does not decide

The following are not decided in this document; they were decided in [decisions/](../decisions/README.md) and elsewhere.

- The names and parameters of individual public APIs
- The range of supported sequences, and the handling of TERM and terminfo
- The concrete policy on character width
- The details of the rendering approach (text drawing, glyph caching), and performance targets
- Resource limits
