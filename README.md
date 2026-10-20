# vtfx

A secure terminal emulator library for Android, with first-class IME support.

vtfx embeds a terminal into Android apps such as SSH clients. The UI is built on Jetpack Compose, and the terminal emulation can also be used from JVM programs without a UI.

## Features

### A small, explicit security boundary

A terminal constantly processes untrusted bytes from the host. vtfx is designed so that this input cannot reach anything sensitive by accident.

- **Capabilities live in two small modules.** Only `vtfx-gate` and `vtfx-gate-android` can send bytes to the host, use the clipboard, or open URLs. The rest of the library, including the parser and the UI, does not even hold the references needed to do so. Security review can focus on this small amount of code.
- **Side effects are values.** The terminal model never acts on its own: it emits typed requests ("the host asked for a reply", "the host wants to write the clipboard"), and the gate decides whether and how to carry them out.
- **Host strings are never echoed back.** Replies to host queries are built only from constants and numbers from the terminal state. Pastes are sanitized so that they cannot break out of bracketed paste mode.
- **Safe defaults.** Clipboard reads and writes through OSC 52 are denied (writes, when allowed, are size-limited), and only http and https links can be opened, on an explicit user action. Apps relax these only by configuring a `GatePolicy` explicitly.
- **Less attack surface.** vtfx does not access the network, start processes, use reflection, or load fonts from files or downloads. Input from the host is bounded by resource limits, and the parser, input, and gate are fuzzed.
- **Checked mechanically.** The allowed module dependencies and the places allowed to use sensitive APIs are declared in [config/boundaries.toml](config/boundaries.toml) and checked by `scripts/check.sh`, which CI runs. The paths that need security review are listed in [config/protected-paths.toml](config/protected-paths.toml).

### IME support

vtfx treats input methods as a core requirement, not an afterthought.

- **Works with how IMEs actually edit text.** Composing text, conversion candidates, predictive text, and autocorrect are translated into the bytes a terminal expects, including rewrites of already committed text. The candidate window follows the terminal cursor.
- **A documented contract.** The expected behavior of every `InputConnection` call is written down in [the IME contract](docs/design/ime-contract.md), and the editing logic is plain Kotlin that is tested on the JVM, including against hostile IME calls.
- **Verified against real IMEs.** IME sessions recorded on real devices are replayed as deterministic regression tests, and end-to-end tests drive a real IME on the emulator. Gboard (English, Japanese, and Korean) is verified today.
- **Direct input.** For IMEs that hold English words as composing text, apps can switch to direct input so that every key reaches the host immediately, as needed by programs like vim.
- **Private by design.** The IME sees only the text the user typed through it, never the screen contents, and the IME's personalized learning is disabled.

Physical keyboards are supported too, and apps can build their own extra key bars on top of the public API.

### Correct terminal emulation

The references (ctlseqs, DEC STD 070, and others) are stated explicitly, and the behavior is verified with recorded output of real programs (vim, less, htop, tmux) and with esctest2.

## Getting started

> [!NOTE]
> vtfx is not published to Maven Central yet, and the public API may change before 1.0.

Apps depend on `vtfx-compose`, which brings in the other modules. minSdk is 29.

```kotlin
val session = TerminalSession(
    // Everything vtfx sends to the host goes through here.
    connection = { bytes -> channel.send(bytes) },
    clipboard = AndroidClipboard(context),
    urlOpener = AndroidUrlOpener(context),
)

// Pass output from the host. Can be called from any thread.
session.feed(bytes)

// Show the terminal.
Terminal(session, Modifier.fillMaxSize())
```

JVM programs without a UI, and app tests, can depend on `vtfx-session` alone and use the same `TerminalSession`.

See the [embedding guide](docs/usage.md) for the details, and `vtfx-sample` for a working app. The API reference can be generated into `build/dokka/html` with `./gradlew :dokkaGenerate`.

## Modules

| Module | Responsibility |
|---|---|
| `vtfx-core` | Interprets host output and updates the terminal state (JVM) |
| `vtfx-input` | Converts key input and IME edits into input to the host (JVM) |
| `vtfx-gate` | **Security boundary.** Assembles the bytes sent to the host, and decides on requests with external effects (JVM) |
| `vtfx-gate-android` | **Security boundary.** Executes permitted requests with Android APIs |
| `vtfx-session` | The terminal session. Ties together the terminal, input, and requests to the host. Usable without a UI (JVM) |
| `vtfx-compose` | The Compose UI. Rendering, input, and accessibility |
| `vtfx-font` | Reads fonts that users choose and writes them again in one canonical form (JVM) |
| `vtfx-font-gate` | **Security boundary.** Checks the canonical form, which is all that reaches the platform's native font code (JVM) |
| `vtfx-font-gate-android` | **Security boundary.** Makes typefaces from checked fonts |
| `vtfx-sample` | A sample app |

The structure, the direction of dependencies, and how the boundary is checked are described in [docs/design/architecture.md](docs/design/architecture.md). Design decisions are recorded in [docs/decisions/](docs/decisions/README.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). This project follows the [Code of Conduct](CODE_OF_CONDUCT.md).

Building requires JDK 21 and the Android SDK. Run `scripts/check.sh` (which also needs Go) before sending changes; CI runs the same script. The test strategy is in [docs/design/testing.md](docs/design/testing.md).

## Security

To report a security issue, see [SECURITY.md](SECURITY.md).

Eligibility for the [Google Open Source Software Vulnerability Rewards Program](https://bughunters.google.com/open-source-security) is determined by the [Google Open Source Software Vulnerability Reward Program Rules](https://bughunters.google.com/about/rules/open-source/google-open-source-software-vulnerability-reward-program-rules).

## License

[Apache License 2.0](LICENSE). The character width tables are generated from the Unicode Character Database, which is under the Unicode License v3 ([third_party/unicode/LICENSE](third_party/unicode/LICENSE)).
