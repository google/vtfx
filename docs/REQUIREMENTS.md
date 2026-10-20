# vtfx requirements

This document deals only with requirements. It does not include concrete design (module structure, types, implementation approach).

Research findings and proposals are in [research/](research/).

## 1. Overview

vtfx is a terminal emulator library for Android. The UI is Android-only and built on Jetpack Compose. The terminal emulation can also be used from JVM programs without a UI.

- It is used by mounting it as a Compose UI.
- It interprets the output bytes received from the host and draws them on the screen.
- It sends the user's key input and IME input to the host as bytes.

## 2. Background and motivation

An existing approach is to render xterm.js in a WebView. It has the following problems.

- **Efficiency**: Going through a WebView is inefficient.
- **IME**: There are cases in which the IME does not work correctly.
  - xterm.js assumes cross-platform use, so it is hard to guarantee IME-specific hacks in every environment.
  - In fact, bugs have been reported in which specific combinations of platform and IME do not work.
  - Fixes that are sent are hard to get merged.

The UI of vtfx is Android-only, taking advantage of not having to account for differences in IMEs across platforms.

The biggest point of differentiation from existing libraries is **limiting, by design, the code that needs security review to a small part**.

## 3. Intended use

- Designed as a general-purpose library. It is expected to be used not only by the author's own apps but also by third-party apps.
- Takes the stability of the public API into account (6.5).
- The connection to the host (SSH, a local PTY, and so on) is the responsibility of the app using the library. The library does not assume a particular connection method.
- It can also be used from JVM programs to emulate terminal behavior without a UI (unit tests of terminal apps, automation through a terminal, and so on).

## 4. Scope

### 4.1 In scope

- Interpreting output bytes and managing the terminal screen state
- Rendering as a Compose UI
- Converting key input and IME input into bytes for the host
- Notifying the host of terminal size changes
- Scrollback, text selection and copy
- A UI-independent terminal emulation API, usable from Kotlin programs on the JVM.
- Images by the kitty graphics protocol, with the image data sent in the escape sequences (direct transmission). Enabled by default, and bounded by resource limits (`TerminalLimits.maxImageBytes`) like other host output ([decisions/0038](decisions/0038-kitty-graphics.md)).

### 4.2 Out of scope

- Creating PTYs and launching processes (including JNI)
- UI on platforms other than Android (rendering, input, IME)
- Runtimes other than the JVM (Kotlin Multiplatform native, JavaScript, and so on)
- Use from Java. The public API assumes use from Kotlin and may use Kotlin features such as value classes, default arguments, and `@RequiresOptIn`.
- Other image protocols, such as sixel, and the parts of the kitty graphics protocol that read files or shared memory of the device or animate images
- The following are also out of scope in phases 1 to 6 (7.1).
  - Ligatures
  - bidi / RTL

## 5. Functional requirements

### 5.1 Terminal emulation

- **Standard of correctness**: Do not match the behavior of xterm.js; pursue correctness as a terminal emulator.
- **Explicit references**: To make "correctness" decidable, explicitly define the references to conform to.
  - Candidates: xterm ctlseqs, DEC STD 070, vttest, esctest
  - Proposal: [research/conformance-references.md](research/conformance-references.md)
  - When deviating from a reference, record the decision.
- **Documented scope**: Document the range of supported escape sequences and modes. The candidates are as follows.
  - SGR (256 colors / truecolor)
  - alternate screen
  - bracketed paste
  - SGR mouse
  - focus events
  - synchronized output
- **TERM / terminfo**: Define the TERM to announce and how terminfo is handled.

### 5.2 Unicode and character width

Assuming Japanese use, define the handling of character width clearly. A mismatch with the host's width calculation breaks cursor positions, so the items to decide are as follows.

- East Asian fullwidth
- Handling of East Asian Ambiguous (whether to make it configurable)
- grapheme clusters (ZWJ emoji, combining characters)
- The policy on which Unicode version to conform to

In addition, Japanese must display correctly, including CJK font fallback.

### 5.3 IME (the most important requirement)

- The goal is that Japanese input works.
  - Making the most of IME features (autocorrect, handwriting input, and so on) is not required.
  - How far to support them is judged as a trade-off, and the decision is recorded.
- Input with Japanese IMEs works correctly.
  - Composing text is displayed at the terminal's cursor position.
  - The candidate window is displayed at an appropriate position.
  - Committing, deleting, and sending keys produce the expected bytes.
  - Even when a key other than for conversion (Enter and so on) is pressed during composition, the order of what is sent is as expected.
- For Latin input, there is a way to send each character to the host one at a time.
  - Because some apps, like vim's normal mode, assume immediate processing of each key.
  - IMEs with predictive text commit word by word, so compatibility with Japanese input is judged as a trade-off.
- Document the expected behavior of the interaction with the IME as a verifiable contract.
  - Contents: for which call, what is sent, and what is displayed.
- Works with both software keyboards and physical keyboards.
- Define the matrix of target IMEs.
  - Candidates: Gboard (Japanese, English, Korean, voice input), Samsung Keyboard, SwiftKey, Simeji
- The range of surrounding text disclosed to the IME is consistent with the privacy requirement (6.1).
- Define how to handle requests to rewrite committed text (autocorrect and so on).
- See [research/ime-languages.md](research/ime-languages.md) for differences in IME characteristics by language, and [research/xterm-js-ime.md](research/xterm-js-ime.md) for the approach of xterm.js.

### 5.4 Other input and interaction

- Handles physical keyboards and modifier keys (Ctrl / Alt / Meta and so on).
- Handles the following touch interactions.
  - Scrolling
  - Mouse reporting when the app requests the mouse
  - Text selection
- Regarding an extra key bar for keys not on software keyboards (Esc / Ctrl / Tab and so on):
  - Do not include it in the library itself.
  - Provide a means for the app to implement it.
- Paste supports bracketed paste.

### 5.5 Screen size and lifecycle

- Resize notifications to the host do not fire repeatedly during the IME show/hide animation.
- Size changes are handled correctly on screen rotation, multi-window, and foldable devices.
- The session can be kept across configuration changes.
- Decide whether to reflow on resize.

## 6. Non-functional requirements

### 6.1 Security (the core of the differentiation)

**Threat model**

The following inputs are not trusted.

- Output bytes from the host (assuming a malicious server, or displaying a malicious file)
- Pasted content
- The IME, which is a third-party app

**Attack types to prevent** (at a minimum)

- Command injection into the host through terminal responses
  - Including paths by which a string controlled by the attacker returns as input to the host
- Unauthorized reading and writing of the clipboard through terminal sequences
- Spoofed hyperlinks and opening unintended URLs
- Escaping from bracketed paste through pasted content
- Unintended leakage of terminal content (including disclosure of surrounding text to the IME)
- Resource exhaustion (huge repetitions, very long lines, bloated scrollback, and so on)

**Limiting the scope of review**

- Most of the code does not need security review, and human review is limited to a part of the code.
- Explicitly list the code subject to review.
- Mechanically verify that the boundary is respected, and detect violations in CI.
  - In phases 1 to 3 (7.1), the enforcement did not need to be impossible for an agent to circumvent: a guardrail-like check that keeps agents from making obviously wrong changes was enough. From phase 4, the check holds against changes written to get around it, within the limits of what review covers ([0044](decisions/0044-boundaries-in-gradle-and-classes.md)).
  - This is backed by keeping the whole git history so that it can be audited afterward.
- Adding or changing dependencies and changing the build configuration are included in the review scope.

**Handling resource exhaustion**

- Define the acceptable range (how far crashes are acceptable, how to set limits).
- In no case may it lead to a compromise.

**Privacy of IME learning**

- Define the policy on whether to disable the IME's personalized learning.

### 6.2 Performance

- More efficient than WebView + xterm.js.
- Set concrete targets at design time for the following.
  - Throughput with large amounts of output
  - Rendering frame rate
  - The memory limit of scrollback

### 6.3 Accessibility

- Terminal content can be read with TalkBack. It is hard to add later, so consider it from the start.
- The IME and accessibility implementations do not constrain each other (2.7 of [research/xterm-js-ime.md](research/xterm-js-ime.md)).

### 6.4 Supported Android versions

- Supporting old versions is not required.
  - A general-purpose library is constrained by the apps' minSdk, but priority is given to not compromising the design for legacy APIs.
- Decide minSdk by weighing trade-offs per feature. Judge case by case.
  - Examples of factors: notification of the candidate window position, handwriting input with a stylus
- Record the reason for each decision.

### 6.5 Public API

- **Stability**: Define the compatibility policy of the public API (versioning, the deprecation procedure, when to start guaranteeing stability).
- **Detecting changes**: Mechanically detect changes to the public API (including binary compatibility) and report them in CI.
- **Minimal surface**: Keep the public API to the minimum needed, and do not expose internal implementation.
- **Customizability**: The app can change the appearance (colors, font, cursor shape, and so on) and behavior settings.
- **Safe defaults**: Behavior related to security (clipboard access, opening URLs, and so on) defaults to the safe side, and settings that relax it are chosen explicitly by the app.
- **Minimal dependencies**: Minimize the dependencies brought transitively into the app.
- **Documentation**: Document the contract of the public API (when what is called, threads, lifetimes).

## 7. Development process requirements

### 7.1 Phases

1. Prepare for coding agents to develop autonomously without human intervention.
   - Test infrastructure, oracles, boundary verification, emulator environment
2. Agents develop autonomously. Human review is minimized.
3. Release after human review.
   - The review centers on the code under review in 6.1 and the public API in 6.5.
   - Done: the repository was published on 2026-10-15 at version 0.1.0 after a human review of the whole tree ([0018](decisions/0018-distribution.md)).
4. Continuous development after publication.
   - Agents develop autonomously as in phase 2. What needs a human (decisions, work only humans can do, checks on real devices) is gathered in [review-queue.md](review-queue.md).
   - Humans review the changes to the code under review in 6.1 periodically, and before every release.
   - Ends when a human judges that the features and quality are enough for the author's own apps to adopt vtfx.
5. Trial use with binary releases (0.x).
   - Artifacts are published to Maven Central as 0.x versions. Breaking changes to the public API are still allowed (6.5).
   - The author's own apps adopt vtfx. The checks on real devices are worked through, and the defects found in real use are fixed.
   - Ends when vtfx has a record of use in real apps and no open issues in the public API remain.
6. Stabilization for 1.0.
   - 1.0 is when the stability of the public API starts to be guaranteed (6.5), under the compatibility policy that section 8 resolved. The public API is settled before it.
   - Before 1.0 is released, humans review the whole tree, as in phase 3.
7. Maintenance after 1.0.
   - Versions follow semantic versioning: breaking changes only in a major version.
   - Define how security vulnerabilities are reported and fixed, and whether contributions from outside are accepted.
   - Reconsider what 4.2 leaves out of scope in phases 1 to 6.

Every release in phases 4 to 6 requires that humans have reviewed the changes to the code under review in 6.1 since the previous release, and that no item in section 1 (Decisions) of [review-queue.md](review-queue.md) is open.

### 7.2 Testing

- **Protecting oracles**: The tests that support the autonomous loop must be trustworthy.
  - Agents cannot change the spec tests, fixtures, and screenshot goldens owned by humans.
  - They are distinguished from the tests agents add.
  - The strength of protection follows the same thinking as the verification of the review scope in 6.1. Until phase 6 (7.1), guardrail-like detection and auditing afterward are enough.
- **Deterministic gates**: The tests that gate the loop are deterministic.
  - Non-deterministic tests (such as e2e with a real IME) are not gates and are treated as reports.
- **Emulator e2e**: Agents can run e2e tests on the Android emulator (mainly for the IME).
  - Hardware virtualization is available in the execution environment.
- **Capturing the behavior of IMEs on real devices**: Behavior observed with each IME on real devices can be captured in a form that deterministically reproduces it as a regression test.
- **Fuzzing the parser**: Fuzz the interpretation of output bytes.
  - It can detect non-terminating states and memory exhaustion on abnormal input.

## 8. Open questions to decide at design time

- The references to conform to, and the range of supported sequences (Resolved: [0001](decisions/0001-parser.md), [0009](decisions/0009-emulation-scope.md))
- Handling of TERM / terminfo (Resolved: [0010](decisions/0010-term-terminfo.md))
- The policy on the Unicode version, Ambiguous width, and grapheme clusters (Resolved: [0011](decisions/0011-unicode.md))
- The matrix of target IMEs (Resolved: [0017](decisions/0017-ime-scope.md))
- The range of surrounding text disclosed to the IME (Resolved: [0017](decisions/0017-ime-scope.md))
- Handling of requests to rewrite committed text (Resolved: [0017](decisions/0017-ime-scope.md))
- A means to send Latin letters one at a time, and compatibility with Japanese input (Resolved: [0017](decisions/0017-ime-scope.md))
- The policy on whether to disable the IME's personalized learning (decided: always disabled. Section 3 of [design/ime-contract.md](design/ime-contract.md)) (Resolved: [0017](decisions/0017-ime-scope.md))
- Whether to reflow on resize (Resolved: [0013](decisions/0013-resize-and-lifecycle.md))
- The policy for resize notifications during the IME animation (Resolved: [0013](decisions/0013-resize-and-lifecycle.md))
- The acceptable range and limits for resource exhaustion (Resolved: [0002](decisions/0002-resource-limits.md))
- Performance targets (Resolved: [0014](decisions/0014-performance.md))
- minSdk (trade-offs per feature) (Resolved: minSdk is 29, [0039](decisions/0039-minsdk-29.md); the initial value was 34)
- The concrete scope of the code under security review, and how to verify the boundary (Resolved: [0041](decisions/0041-security-review-by-module.md), [0044](decisions/0044-boundaries-in-gradle-and-classes.md))
- The compatibility policy of the public API, and when to start guaranteeing stability (Resolved: [0016](decisions/0016-public-api.md))
- Distribution and license (Resolved: [0018](decisions/0018-distribution.md))
