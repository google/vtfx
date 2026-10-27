# Differential testing against alacritty_terminal

- Status: Provisional
- Date: 2026-10-27
- Related: Requirements 5.1, 6.1, and 7.2, section 6 of [architecture.md](../design/architecture.md), section 11 of [emulation.md](../design/emulation.md), sections 1, 2, 10, and 15 of [testing.md](../design/testing.md), [review-queue.md](../review-queue.md), [0079](0079-emulation-scope.md), [0080](0080-kitty-graphics.md), [0081](0081-resource-limits.md), [0084](0084-session-and-threads.md), [0088](0088-build-boundaries-and-tests.md), `scripts/alacritty_diff.py`, `tools/alacritty-diff`, `spec-proposals/core/differential.vtspec`

## Background

Hand-written cases in `spec/core`, `esctest2`, and recorded app sessions check individual control functions and real programs, while `TerminalFuzzer` and `GateFuzzer` check invariants and resource bounds without an expected screen. Feeding large corpora, recordings, and generated combinations to both `vtfx-core` and another mature terminal emulator exposes edge-case disagreements—such as pending-wrap cancellation, wide characters at margins, mode transitions, and resize reflow—without writing expected screens by hand. At the same time, `scripts/check.sh` and CI must remain deterministic and independent of a Rust toolchain, and `spec/` is owned by humans (section 1 of [testing.md](../design/testing.md)).

## 1. Standalone alacritty_terminal 0.26.0 runner outside the gates

`scripts/alacritty_diff.py` runs `DifferentialHost` in `vtfx-core`'s tests and a standalone Rust executable in `tools/alacritty-diff` (`alacritty_terminal = "=0.26.0"` with `default-features = false`, pinned by a committed `Cargo.lock`) as child processes speaking a line-based protocol on standard input and output, and writes `build/reports/alacritty-diff.md` (section 15 of [testing.md](../design/testing.md)). The tool is for reports and is not part of `scripts/check.sh` or CI; Rust and Cargo are needed only when running `scripts/alacritty_diff.py`, which builds into `build/alacritty-diff/target` with `cargo --locked`.

**Why.** `alacritty_terminal` is a mature, embeddable terminal emulation library whose supported control functions largely overlap `vtfx-core`'s ([0079](0079-emulation-scope.md)), and disabling default features drops `serde` and windowing/PTY code to leave a small, pure-emulation crate graph pinned in `Cargo.lock`. Communicating over standard input and output avoids JNI, native toolchains in Gradle, and platform-specific native artifacts in `gradle/verification-metadata.xml` ([0088](0088-build-boundaries-and-tests.md)). Keeping the comparison out of `scripts/check.sh` avoids making Rust and Cargo prerequisites for contributors or CI, and keeps gates independent of bugs in a third-party engine.

**Alternatives considered.**
- **Call `alacritty_terminal` in-process from JUnit via JNI**: couples test compilation to Cargo and C/Rust toolchains, and adds native libraries to the Gradle build.
- **Run `scripts/alacritty_diff.py` inside `scripts/check.sh` and CI**: requires Rust and Cargo on every developer machine and CI runner for a comparison whose inputs and pinned reference crate only change when `vtfx-core`, the corpora, or `Cargo.lock` changes.
- **Drive `xterm` or `tmux` in a pseudo-terminal for every case**: `tmux` already serves as the oracle for recorded apps (section 10 of [testing.md](../design/testing.md)) and `esctest2` checks `xterm`'s rules (section 11 of [testing.md](../design/testing.md)), whereas spawning or synchronizing a PTY per fuzzer seed is orders of magnitude slower than two batch processes and entangles raw byte streams with termios and terminal replies.

**Impact of reversing.** Delete `tools/alacritty-diff/`, `scripts/alacritty_diff.py`, `scripts/tests/test_alacritty_diff.py`, `DifferentialHost.kt`, the `differentialClasspath` task in `vtfx-core/build.gradle.kts`, and section 15 of [testing.md](../design/testing.md) together with the references in [architecture.md](../design/architecture.md) and [emulation.md](../design/emulation.md).

## 2. Inputs and triage of differences

`scripts/alacritty_diff.py` feeds `spec/core`, `spec-proposals/core`, the corpora of `TerminalFuzzer` (decoding its resize records into interleaved writes and resizes) and `GateFuzzer` (plus optional `--extra-corpus` directories from long fuzzing), the recordings in `recordings/` and `recordings/graphics/` (expanded to eleven CLI apps in section 10 of [testing.md](../design/testing.md)), and deterministic feature and fixed-seed sequences covering [emulation.md](../design/emulation.md), comparing screen text, cursor position, and, with `--styles`, cell styles (section 15 of [testing.md](../design/testing.md)). Every difference is resolved in one of four ways:

1. **Bug in `vtfx-core`**: fixed in `vtfx-core` with a regression test.
2. **Open question of expected behavior**: written as `.vtspec` cases in `spec-proposals/core/`, executed as a gate by `ProposedSpecTest` in `vtfx-core`, matched in `tools/alacritty-diff/reasons.toml`, and queued in [review-queue.md](../review-queue.md) for a human to decide and promote to `spec/core/`.
3. **Bug or unimplemented feature in `alacritty_terminal`**: recorded in `tools/alacritty-diff/reasons.toml`; bugs in `alacritty_terminal` are also listed in [review-queue.md](../review-queue.md) so a human can decide whether to report them upstream.
4. **Intentional design difference**: recorded in `tools/alacritty-diff/reasons.toml` with a link to the governing decision ([0079](0079-emulation-scope.md), [0080](0080-kitty-graphics.md), [0081](0081-resource-limits.md), [0084](0084-session-and-threads.md)).

**Why.** Reusing the fuzzer corpora and app recordings turns inputs already kept for security, crash, and rendering checks into behavioral comparisons at zero maintenance cost, while deterministic sequences cover combinations of margins, modes, wide characters, SGR attributes, and resizes systematically. Matching known divergences in `tools/alacritty-diff/reasons.toml` and surfacing unmatched differences at the top of `build/reports/alacritty-diff.md`—following the pattern of `tools/esctest/reasons.toml` (section 11 of [testing.md](../design/testing.md))—keeps newly introduced discrepancies immediately visible. Running `spec-proposals/core/` through `ProposedSpecTest` gates `vtfx-core`'s behavior right away without modifying human-owned `spec/` (sections 1 and 2 of [testing.md](../design/testing.md)).

**Alternatives considered.**
- **Treat `alacritty_terminal` as an oracle**: `alacritty_terminal` 0.26.0 has bugs of its own (such as `ED 1` on the second row, pending-wrap handling across `EL 0`, `ICH`, `DCH`, `LF`, `IND`, `RI`, and `HT`, scrolling margins on `CUU`/`CUD`, and wide characters in a 1-column grid) and omits features that `vtfx-core` supports (`DECSTR`, `LNM`, modes 47/1047/1048, `SGR 21`/`53`, and the kitty graphics protocol); conforming to it blindly would break [0079](0079-emulation-scope.md).
- **Keep open behavioral questions only in Kotlin unit tests**: unit tests catch regressions, whereas `.vtspec` files in `spec-proposals/core/` already use the oracle format so a human can promote them directly into `spec/core/`.

**Impact of reversing.** To drop `ProposedSpecTest` and `spec-proposals/core/`, move their cases into `TerminalTest`, delete `ProposedSpecTest.kt` and `spec-proposals/core/`, and remove `specProposals` and `vtfx.specProposalsDir` from `vtfx-core/build.gradle.kts`.
