# To do

Work for agents that has been decided on but not started: improvements that no bug report or decision tracks yet. Read it at the start of work, with [review-queue.md](review-queue.md) and the open [bug reports](reviews/README.md). Take an item, do it as usual (decisions in [decisions/](decisions/README.md), the design in the design documents, measurements in reports under `build/reports/`), and remove the item in the commit that finishes it. This file lists only open items; outcomes live in the decisions, the design documents, and [research/](research/).

For each item, write why it is worth doing, what to do, and when it is done. Keep items short, and point to commits by full SHA instead of retelling their changes.

## 1. Items

### 1.2 Differential testing against alacritty_terminal

**Why.** `spec/core` and the four recordings do not tell vtfx and alacritty_terminal 0.26.0 apart: both reproduce the text of every recording's reference screen, and alacritty_terminal fails a single case of spec/core, "ED 1 erases from the start of the screen to the cursor, inclusive" (its `clear_screen` clears the rows above the cursor only when the cursor is below the second row: `if cursor.line > 1`). Running the same large inputs through both and comparing the screens finds differences that the hand-written specs miss, and each difference is a bug in one of them or an open question for the specs. A mature engine as the other side makes the core's coverage grow faster than writing cases by hand.

**What.**
1. A tool outside the gate (for example under `tools/`) that feeds the same bytes to vtfx-core and to alacritty_terminal and compares the screen's text and the cursor, and later the styles. The engine side can be a small Rust program that reads bytes and prints the screen, so that no JNI is needed. Rust and Cargo must not become requirements of `scripts/check.sh` or CI; pin the crates with a committed `Cargo.lock`, and record the new tool and its dependencies in a decision.
2. Inputs: the fuzzers' corpora ([testing.md](design/testing.md) section 7), the recordings, and generated sequences around the control functions of [emulation.md](design/emulation.md).
3. Triage each difference: a bug in vtfx gets a fix and a regression test; a question of expected behavior goes to `spec-proposals/` for a human; a bug in alacritty_terminal is noted in the tool's report. Reporting upstream is for a human to decide; add it to [review-queue.md](review-queue.md).
4. Record more apps with `tools/recordings/record.py` (for example neovim, emacs, mc, btop, tig, fzf, ncdu), since recordings are references that both engines and tmux are compared with.

**Done when.** The tool runs from one command and writes a report to `build/reports/`, its inputs include the corpora and the recordings, and the differences found in the first runs are triaged as above.

### 1.3 Copy only the lines that changed for each frame

**Why.** `Terminal.snapshot()` copies every line of the screen for each frame. On a Pixel 9, one snapshot of a full 120 × 40 screen of colored text took 27 µs, against 17 µs for reading alacritty_terminal's grid of the same screen into arrays through JNI. The display reads a snapshot on every frame, also while the host floods output ([0089](decisions/0089-performance.md) topic 2), so the copy is paid at every vsync.

**What.** Give lines a version that changes whenever the line changes, and let a snapshot reuse the `LineSnapshot` of a line whose version has not changed since the previous snapshot: snapshots are immutable, so sharing them is safe. Check the thread conventions of [session-and-threads.md](design/session-and-threads.md) and how `LineCache` in vtfx-compose keys its drawings, which could use the same version. Update the design documents to match.

**Done when.** The cost of a snapshot is measured on a device before and after, for a screen that does not change, one where one line changes, and the scenes of `RenderBenchmark`, with no regression in the scenes.
