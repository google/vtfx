# Test strategy

Status: Living document. It describes the current design and is kept in sync with the implementation; design choices and their reasons are recorded in [decisions/](../decisions/README.md). Its gate rules are binding: agents may tighten them, and only a human may relax them.

This document defines the structure of the tests that support the autonomous development loop, where oracles live and their formats, and the rules for gates (requirement 7.2). The tests for each module are listed in section 6 of [architecture.md](architecture.md).

## 1. Principles

- **Humans own the oracles.** Spec test data, fixtures, and screenshot goldens are in `spec/`, and agents do not change them.
- **Agents own the test code.** Agents write, and may change, the runners that load and run oracles, the unit tests, and the fuzzing harnesses.
- **Gates are deterministic.** Tests used as gates do not depend on time, random numbers, thread timing, or real IMEs.
- **Gates run with one command.** `scripts/check.sh` runs all gates. CI calls the same script.

## 2. Directories

| Path | Owner | Contents |
|---|---|---|
| `spec/core/` | Humans | Spec tests of terminal emulation (format in section 3) |
| `spec/ime/` | Humans | Expected sent content for IME traces (format in section 4) |
| `spec/screenshots/` | Humans | Screenshot goldens (section 5) |
| `tools/ime-probe/traces/` | Humans | Traces recorded with real IMEs. Only the traces that `spec/ime/` refers to are kept |
| `<module>/src/test/` | Agents | Unit tests and runners that load oracles |
| `<module>/src/test/resources/spec-expected-failures.txt` | Agents | The list of spec tests that do not pass yet (section 6) |
| `spec-proposals/` | Agents | Spec tests proposed to humans for addition. Written in the same format as `spec/`. The same content is also run as tests owned by agents. |
| `vtfx-compose/src/test/screenshots/` | Agents | Provisional goldens (section 5) |
| `vtfx-font-gate/src/fuzz/resources/com/google/vtfx/font/gate/canonical/` | Agents | Canonical fonts that `FontFile.sanitize` writes, which the tests of `vtfx-font-gate` check the verifier with. `CanonicalFixturesTest` in `vtfx-font` checks that sanitizing still writes them, so they are also a golden of the canonical form ([decisions/0086](../decisions/0086-fonts.md)). Under security review with the rest of `vtfx-font-gate` |

`spec/` and `tools/ime-probe/traces/` are listed under `oracle` in `config/protected-paths.toml`, and changes to them are reported.

## 3. Spec tests of terminal emulation (`spec/core/*.vtspec`)

One file can contain multiple cases. The format is text independent of the API, so that it can be written before the implementation of vtfx-core.

```
=== CUP moves the cursor
size 10x3
input "\e[2;3HX"
screen
  |          |
  |  X       |
  |          |
cursor 4,2
```

| Line | Meaning |
|---|---|
| `=== <name>` | The start of a case. The name is unique within the file. |
| `size <columns>x<rows>` | The terminal size. |
| `input "<string>"` | Host output. If there are multiple lines, each is given in order as a separate write (used to verify UTF-8 and sequences that span writes). Escapes are `\e`, `\xNN` (one byte), `\uNNNN` (a character encoded in UTF-8), `\b`, `\r`, `\n`, `\t`, `\\`, `\"`. |
| `screen` | The following lines are the expected screen. Each line is enclosed in `|`. A fullwidth character is written as one character occupying 2 cells, and the continuation cell after it is not written. |
| `cursor <column>,<row>` | The expected cursor position (1-based). |
| `ref <source>` | Optional. The place in the reference that is the basis (e.g. `ctlseqs CUP`, `DEC STD 070 5.3`). |

How to write expected values for character attributes and replies (`ReplyRequested` and so on) is added to this document when needed.

## 4. Expected IME sent content (`spec/ime/*.toml`)

Write the expected values for replaying the `ic` events of a `tools/ime-probe` trace into the editing model that follows [ime-contract.md](ime-contract.md).

```toml
[[case]]
trace = "tools/ime-probe/traces/gboard-device-contract/contract.jsonl"
expected = "dont\u007f't \rteh \ri\u007fI \rok \u007f. \rhello world\u001b[D\u001b[Dx\r"
```

The `send` events recorded in traces are not used as expected values, because the policy of the recording tool differs in part from the contract.

## 5. Screenshot tests

- Use Roborazzi, which renders Compose on the JVM. It does not need an emulator and runs deterministically.
- Humans do not review every change to the goldens. Therefore goldens are handled in two stages.
  - **Provisional goldens**: Kept in `vtfx-compose/src/test/screenshots/` and managed by agents. Used to detect regressions. When updating them, write in the commit message what changed and why.
  - **Official goldens**: Kept in `spec/screenshots/` and owned by humans. Humans check the provisional goldens and promote them, by copying them with the same file name.
- Implementation: Render with Robolectric's native graphics and compare with Roborazzi. The settings are in `vtfx.screenshot-test` in build-logic. With `roborazzi.test.verify=true` in `gradle.properties`, every test run, including `check`, compares with the goldens. Record goldens with `./gradlew :vtfx-compose:recordRoborazziDebug`. Images of comparison differences are output to `build/outputs/roborazzi/`.
- `OfficialGoldensTest` in `vtfx-compose` checks that each official golden has a provisional golden with the same pixels. Since the screenshot tests compare the rendering with the provisional goldens, this makes the official goldens a gate too. When a change to the rendering updates a provisional golden that has an official golden, list it in `spec-expected-failures.txt` of `vtfx-compose` as `screenshots/<file name>` (section 6), explain the change in the commit message, and add it to [review-queue.md](../review-queue.md) for a human to promote again ([decisions/0088](../decisions/0088-build-boundaries-and-tests.md)).

## 6. Handling spec tests that do not pass

In the middle of development, many spec tests do not pass yet. Agents cannot change the oracles, so they are handled with the following mechanism.

- Cases that do not pass yet are listed in the module's `spec-expected-failures.txt` in the form `<file>:<case name>`. Official screenshot goldens that the provisional goldens no longer match are listed in the same way, as `screenshots/<file name>` (section 5).
- The gate checks both of the following.
  - All cases not in the list pass
  - Cases in the list fail (this forces removing them from the list once they pass, keeping the list accurate)
- Agents may change the list. Progress can be measured by the number of entries in the list. Changes to the list are checked in the usual code review.

## 7. Fuzzing

- Fuzz the modules that receive untrusted input (the threat model in requirement 6.1). Each target is a libFuzzer entry point, which Jazzer runs, and which does not depend on JUnit or Jazzer, so that other fuzzing engines can run it too ([decisions/0088](../decisions/0088-build-boundaries-and-tests.md)).
- What to detect: exceptions, non-terminating processing (timeouts), and memory use beyond the limits.
- The gate replays a fixed corpus and fuzzes for a short time (e.g. 30 seconds). Long fuzzing runs separately.
- Agents add the inputs of crashes found as regression tests in the unit tests.
- Implementation: One target per module (Jazzer can fuzz only one target per run). A target is an `object` with a `@JvmStatic fun fuzzerTestOneInput(input: ByteArray)` in the `fuzz` source set of the module (`src/fuzz/kotlin/`), which the module's build file names with `fuzzTarget`; the helpers it shares with the tests (`HostileIme`, `KnownReplies`, `CanonicalFonts` and the canonical fonts) are there too. Its corpus is `src/fuzz/corpus/<target>/`, one input per file: hand-written seeds and the inputs that found bugs.
  - `TerminalFuzzer` in `vtfx-core`: Checks the invariants of the parser and the screen model with host output and resizes.
  - `GateFuzzer` in `vtfx-gate`: Passes host output through a real `Terminal`, which it may resize (up to 256 × 256) and give another cell size between writes, and checks that replies have only known shapes, that the amount of replies is linear in the output, that no URL is opened, that the clipboard is written only when the policy allows it, and the limit on clipboard writes. It also pastes the same bytes and checks that they do not escape bracketed paste.
  - `ImeFuzzer` in `vtfx-input`: Checks the invariants of the editing model and the limit on DELs with sequences of IME calls (the same checks as `HostileImeTest`).
  - `FontFuzzer` in `vtfx-font`: Reads arbitrary bytes as a font file and as a saved sanitized font, and checks that the only exception is `InvalidFontException`, that sanitizing a sanitized font gives the same bytes, and that bytes the verifier accepts are what sanitizing them gives ([decisions/0086](../decisions/0086-fonts.md)).
  - `VerifierFuzzer` in `vtfx-font-gate`: Reads its input as changes to one of the canonical fonts that the tests of `vtfx-font-gate` keep as files (replacing bytes, and inserting or removing bytes in a table, with the tables laid out again), computes the checksums of the tables and `head.checksumAdjustment` again, so that the changed fonts get past the checksums to the checks of every table, and passes them to `SanitizedFont.fromCanonical`. Checks that the only exception is `InvalidFontException` of kind `Malformed`, and that an accepted font keeps its bytes ([decisions/0091](../decisions/0091-security-review-and-process.md)).
- The fixed corpus is replayed in the usual `test` task by the module's `<target>CorpusTest`, which runs every file and the empty input through the target, each with a time limit of 10 seconds. The `fuzz` task (which `check` depends on; configured in `vtfx.fuzz` in `build-logic`) runs Jazzer's driver on the target for 30 seconds, with a 1 GiB heap and the same limit of 10 seconds for one input. It writes an input that makes the target fail into the corpus directory, and keeps the corpus that fuzzing grows in `build/fuzz/corpus/`. The fuzz tasks of the modules run in parallel ([decisions/0088](../decisions/0088-build-boundaries-and-tests.md)). Long fuzzing is done with `scripts/fuzz.sh <duration> [module...]`, and the results are appended to `build/reports/fuzzing-log.tsv`. Details, and the relation to the determinism of gates, are in [decisions/0088](../decisions/0088-build-boundaries-and-tests.md).

## 8. e2e on the emulator

- e2e with real IMEs runs on the emulator. The results are for reports and are not gates (requirement 7.2).
- Regressions in IME behavior are gated by the trace replay tests (section 4).
- Implementation: `scripts/emulator.sh start` starts the AVD `vtfx-e2e` on a fixed port (serial `emulator-5580`), and then `scripts/e2e_ime.py` runs. The script installs the sample app (`vtfx-sample`), operates the Gboard settings screens to add the English, Japanese (QWERTY), and Korean (두벌식) keyboards, and taps keys following the scenarios in `tools/ime-probe/scripts/run_scenarios.py`. It collects the bytes the app sent to the host from logcat, compares them with the expected values in `spec/ime/gboard.toml`, and writes `build/reports/e2e-ime.md`. Failed cases are rerun once, and those that pass on the rerun are reported as flaky.

- Rendering e2e: `scripts/e2e_render.py` runs the instrumented test of `vtfx-compose` (`RenderConsistencyTest`, `src/androidTest/`) on the emulator and writes `build/reports/e2e-render.md`. It compares, cell by cell, the screen actually drawn with hardware acceleration against a reference drawing of the same terminal state with a software `Canvas`, and fails if any cell is drawn in only one of them. Robolectric's rendering does not go through the hardware acceleration path (the cache of line drawings), so bugs in that path are found here (including regression tests for bugs found on real devices; [decisions/0089](../decisions/0089-performance.md)). It covers the images of the kitty graphics protocol too (placed, under the text, scrolled into the scrollback, deleted, on the alternate screen, and as Unicode placeholders; [decisions/0080](../decisions/0080-kitty-graphics.md)): since a cell has ink in both drawings when neither draws an image, those tests also check that the image's color is on the display. The same run includes `SystemFontsTest`, which sanitizes the first face of each font of the device (`/system/fonts`, TrueType and CFF, with the Noto CJK collections), loads each through the platform's font code, checks that a CJK face draws its own glyphs, and logs the time of each face ([decisions/0086](../decisions/0086-fonts.md)). It is for reports and is not a gate. It also includes `DeviceLimitsTest`, which checks the limits that exist only on a device: one image of the largest size that the core takes (64 MiB, [decisions/0080](../decisions/0080-kitty-graphics.md)) is drawn by the hardware-accelerated canvas, which throws on a bitmap above 100 MiB, and a larger one is refused; copies of a large selection and of all the text reach the system clipboard within the Binder limit of a transaction (section 4 of [compose-input.md](compose-input.md)); and glyphs that the host stacks or that are wider than their cell are not drawn outside `Terminal` (section 7.3 of [emulation.md](emulation.md)).

## 9. `scripts/check.sh`

Runs the following in this order, and fails if any one fails.

1. `scripts/check_kdoc.py` (public declarations of library modules have KDoc, requirement 6.5; `@InternalVtfxApi` declarations are excluded from the API reference with `@suppress`; KDoc that appears in the reference does not refer to repository documents or requirement sections. [decisions/0087](../decisions/0087-public-api.md))
2. `scripts/check_doc_links.py` (the `docs/...` paths written anywhere in the repository and the relative links of the Markdown files point at files that exist, and a superseded decision links to its successor. References to sections are not checked, so the documents keep their section numbers ([AGENTS.md](../../AGENTS.md)))
3. License headers (`addlicense -check`, built from `tools/addlicense/go.mod`, which pins its version, and `go.sum`, which pins the checksums of its modules, and run on the files tracked by git; files that are not Google's own source code are excluded. [decisions/0090](../decisions/0090-distribution.md))
4. Tests of the scripts themselves (`python3 -m unittest discover -s scripts/tests`)
5. The generated sources are up to date: `scripts/generate_unicode_tables.py --check` regenerates the character width tables of `vtfx-core` and the default-ignorable code points of `vtfx-gate` from `third_party/unicode` and compares them with the committed files ([decisions/0088](../decisions/0088-build-boundaries-and-tests.md))
6. `gradle/wrapper/gradle-wrapper.jar` has the SHA-256 checksum that Gradle publishes for the version of `gradle-wrapper.properties`, written in the script. The jar is the first code that `./gradlew` runs, before the checksum of the distribution and dependency verification apply, and a binary that review cannot judge from the diff
7. `./gradlew build` (Kotlin formatting check, the module boundaries: the dependencies between modules and what the compiled main classes of each module refer to ([boundaries.md](boundaries.md), [decisions/0088](../decisions/0088-build-boundaries-and-tests.md)), compilation, in which a dropped result of a call is an error ([decisions/0088](../decisions/0088-build-boundaries-and-tests.md)), unit tests, spec tests, lint, and the public API compatibility check. Formatting is [decisions/0088](../decisions/0088-build-boundaries-and-tests.md))

Long fuzzing and the emulator e2e take time, so they are not included. They run with separate scripts (sections 7 and 8). Short fuzzing is included in the `check` of `./gradlew build` in step 7 (section 7, [decisions/0088](../decisions/0088-build-boundaries-and-tests.md)).

## 10. Recording and replaying the output of real apps

- On the development machine, run real apps (vim, less, htop, tmux) in a pseudo-terminal and record their output (`tools/recordings/record.py`, recordings in `recordings/`). The size is 80 × 24 with `TERM=xterm-256color`.
- The expected screen is obtained by replaying the same output into a reference terminal (tmux). The reference pane is put in raw mode (the apps ran in raw mode, so output processing that turns LF into CR LF must not happen).
- `RecordingReplayTest` in `vtfx-core` compares the screen after replaying a recording with the reference. It checks that all characters match, and that attributes (color, bold, italic, underline, reverse, strikethrough) match for non-blank cells. The reference attributes are read with a reader independent of vtfx's interpretation of SGR.
- `RecordingScreenshotTest` in `vtfx-compose` has provisional goldens of the same recordings, the graphics recordings included. `replay <name>` in the sample app can display the same recording.
- Programs that show images with the kitty graphics protocol (`kitten icat`, timg, fastfetch) are recorded by `tools/recordings/record_graphics.py` into `recordings/graphics/`, answering their queries as vtfx does. There is no reference screen for them, since tmux does not know the protocol: `GraphicsRecordingTest` in `vtfx-core` checks where the images are and where the cursor ends ([decisions/0080](../decisions/0080-kitty-graphics.md)).
- Agents own the recordings. When recording again because an app's version changed, write the reason in the commit message.

## 11. esctest2

- `scripts/esctest.py` runs esctest2 and writes the results to `build/reports/esctest.md`. It is for reports and is not a gate.
- esctest2 is GPL-2.0, so it is not included in the repository. The script fetches a pinned commit into `.cache/esctest2`.
- esctest runs in a pseudo-terminal, and its counterpart is `EsctestHost` in the tests of `vtfx-gate` (a JVM program that replies with `vtfx-core` and `vtfx-gate`). Only DECRQCRA for esctest to read the screen, the resize to 80 × 25 at the start of each test (XTWINOPS 8), and the report for reading the title (XTWINOPS 21) are intercepted and handled by `EsctestHost`, because vtfx does not support them. The title report answers the last title vtfx notified with `TitleChanged`.
- The reasons for failing tests are written in `tools/esctest/reasons.toml` (matched by a regular expression on the test name or the test's source). Failures without a reason appear at the top of the report. A failure without a reason is resolved either by fixing vtfx or by writing a reason.

## 12. Tests for each attack type in requirement 6.1

The correspondence between the "attack types to prevent" in requirement 6.1 and the tests that check them. In every test, host output goes through a real `Terminal`.

| Attack type | Tests |
|---|---|
| Command injection through terminal responses | `AttackTest.ReplyInjection` in `vtfx-gate` (replies do not include host strings, DECRQSS and the like do not reflect the request, graphics replies carry only numbers and fixed statuses, random output produces only replies of known shapes), `GateFuzzer` (any output produces only replies of known shapes) |
| Unauthorized reading and writing of the clipboard | `AttackTest.Clipboard` (reading and writing are denied by default, the limit on writing, only the first read of a write answered with the clipboard, only the last write of a write performed, writes with controls or bidi controls refused, the limit on the length of reads, malformed selections handed straight to the gate, when there is no clipboard), `GateFuzzer` (replies to reads are only base64, no writes unless the policy allows them, the limit on writing) |
| Spoofed hyperlinks and opening unintended URLs | `AttackTest.Hyperlinks` (host output alone does not open anything, only allowed schemes, rejecting URLs whose display and destination can be spoofed), `TerminalSessionTest.hyperlinksAreFoundAndOpenedThroughTheGate` in `vtfx-session`, `TerminalGesturesTest` (a link tap only notifies the app and does not open it), `EmulationCoverageTest.Hyperlinks` in `vtfx-core` (URIs that are too long) |
| Escaping from bracketed paste | `AttackTest.PasteEscape`, `TerminalSessionTest.pasteIsSanitizedAndBracketedWhenEnabled`, `GateFuzzer` (arbitrary pastes) |
| Leakage of terminal content (including disclosure to the IME) | `AttackTest.ContentLeakage` (screen contents are not reported), `TerminalInputConnectionTest` in `vtfx-compose` (IME queries see only the input history, and the `EditorInfo` contract, including disabling personalized learning) |
| Resource exhaustion | `ResourceExhaustionTest` in `vtfx-core` (for each hostile output, time is linear in the input and memory is within the limits), `TerminalTest` (limits on combining characters, scrollback, and REP), `ParserTest` (limits on parameters, intermediate bytes, and strings), `EmulationCoverageTest.Hyperlinks` (the limit on the link table and thinning out the search), `EmulationCoverageTest.SavedTitles` (limits on the number and total length of saved titles), `ResourceExhaustionTest` and `GraphicsTest` (the limits on the pixels, number, and size of images and placements of the kitty graphics protocol, and work linear in the input; [decisions/0080](../decisions/0080-kitty-graphics.md)), `AttackTest.ResourceExhaustion` (the amount of replies is linear in the queries, including OSC 52 reads with a large clipboard), `GateFuzzer` (the amount of replies is linear in arbitrary output), `LinksTest` in `vtfx-core` (the effort of removing overlaps is linear in the number of matches; no limit is set on the effort of matching the app's patterns, which is the app's responsibility; [decisions/0087](../decisions/0087-public-api.md)), `ImageBitmapsTest` in `vtfx-compose` (the bitmaps of images that the display keeps stay within their budget when the host replaces an image on every frame; [decisions/0080](../decisions/0080-kitty-graphics.md)), `ComposingLayoutTest` in `vtfx-compose` (even with a very long composing text from the IME, the layout work is bounded by the grid size), `TerminalSessionTest.bellsAndTitlesOfOneFeedAreReportedOnce` (bell and title notifications are at most once per output), fuzzing (section 7) |

The IME is not trusted, so `HostileImeTest` (random) and `ImeFuzzer` (fuzzing) in `vtfx-input` check the invariants of the editing model and what the host receives against sequences of calls with arbitrary arguments. The limit on DELs for deletions outside the range is also checked here. `DroppedControlsAgreementTest` in `vtfx-session` checks, for every UTF-16 code unit, that the input history keeps what the host receives of committed text, and what the host receives of the same text pasted, which the gate's rule alone handles, since `ImeEditor` and the gate each have a copy of the rule for dropping control characters (ime-contract.md section 4.2).

The limits are in section 10 of [emulation.md](emulation.md), and the default policies of the gate are in [gate-policy.md](gate-policy.md).

## 13. Test framework

- The tests of the library modules and the sample app run on JUnit 4, so that a build of the sources with another build system can run them too ([decisions/0088](../decisions/0088-build-boundaries-and-tests.md)). The tests of `build-logic`, which test the Gradle build itself, run on JUnit Jupiter.
- Parameterized tests use TestParameterInjector: `@TestParameter` values, or a values provider for data listed at run time, such as the spec cases, the recordings, and the fuzzing corpus.
- Assertions use `kotlin.test`. Failures that carry an expected and an actual value, such as those of the spec runners, are `ComparisonFailure`, which IDEs show as a diff.
- Groups of tests are nested classes run by `Enclosed`. JUnit 4 constructs each nested class on its own, so what the tests of a group share is an interface that each nested class implements by delegation to a fresh instance (such as `AttackTest` in `vtfx-gate`, and `EmulationCoverageTest` and `GraphicsTest` in `vtfx-core`).
- A time limit on a part of a test is `assertCompletesWithin` in the tests of `vtfx-core`, which runs that part on a thread of its own; a limit on a whole test is `@Test(timeout = ...)`.
