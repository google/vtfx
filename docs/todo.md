# To do

Work for agents that has been decided on but not started: improvements that no bug report or decision tracks yet. Read it at the start of work, with [review-queue.md](review-queue.md) and the open [bug reports](reviews/README.md). Take an item, do it as usual (decisions in [decisions/](decisions/README.md), the design in the design documents, measurements in reports under `build/reports/`), and remove the item in the commit that finishes it. This file lists only open items; outcomes live in the decisions, the design documents, and [research/](research/).

For each item, write why it is worth doing, what to do, and when it is done. Keep items short, and point to commits by full SHA instead of retelling their changes.

## 1. Items

### 1.3 Copy only the lines that changed for each frame

**Why.** `Terminal.snapshot()` copies every line of the screen for each frame. On a Pixel 9, one snapshot of a full 120 × 40 screen of colored text took 27 µs, against 17 µs for reading alacritty_terminal's grid of the same screen into arrays through JNI. The display reads a snapshot on every frame, also while the host floods output ([0089](decisions/0089-performance.md) topic 2), so the copy is paid at every vsync.

**What.** Give lines a version that changes whenever the line changes, and let a snapshot reuse the `LineSnapshot` of a line whose version has not changed since the previous snapshot: snapshots are immutable, so sharing them is safe. Check the thread conventions of [session-and-threads.md](design/session-and-threads.md) and how `LineCache` in vtfx-compose keys its drawings, which could use the same version. Update the design documents to match.

**Done when.** The cost of a snapshot is measured on a device before and after, for a screen that does not change, one where one line changes, and the scenes of `RenderBenchmark`, with no regression in the scenes.
