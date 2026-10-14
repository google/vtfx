# Performance targets

- Status: Approved (2026-10-13, approved by a human in the final review. On 2026-10-14, the worst case of scrollback memory was extended to all output, with the budget of [0002](0002-resource-limits.md))
- Date: 2026-10-12
- Related: Requirement 6.2, section 8 (performance targets), `scripts/benchmark.py`

## Background

Requirement 6.2 asks for being more efficient than WebView with xterm.js, and for concrete targets for throughput, the rendering frame rate, and the memory limit of scrollback.

## Decision

### Measurement methods

- **Throughput**: `TerminalBenchmark` in `vtfx-core` (`./gradlew :vtfx-core:benchmark`). Writes ASCII text, colored output with many SGRs, Japanese, and recordings of real apps (`recordings/`) in 4 KiB chunks into a 120 × 40 terminal, and measures MB/s of host output.
- **Comparison with xterm.js**: Run the same loads through `@xterm/headless` (6.0.0, Node 24) on the same machine (`tools/benchmark/xterm-headless.mjs`). xterm.js in a WebView uses the same parsing code, so this is a lower-bound comparison excluding the WebView's share.
- **Memory**: An estimate of the size of the array of scrollback lines when the default scrollback (10,000 lines) is filled with each load. As the worst case, it is also measured with lines in which every one of 120 cells has a different color.
- **Rendering**: Run the sample app's `RenderBenchmark` on the emulator in two scenes with updates every 16 ms (one where the whole screen, equivalent to 120 × 50, changes every time, and one where colored log lines flow one at a time), and measure the p50 of the UI thread's drawing time in `FrameMetrics` (`DRAW_DURATION`). The emulator's GPU is software, so the time of the whole frame is not a target.

### Targets

| Item | Target |
|---|---|
| Throughput | ASCII ≥ 120 MB/s, SGR ≥ 40 MB/s, Japanese ≥ 70 MB/s, recordings of real apps ≥ 30 MB/s. And each at least 2 times xterm.js (headless) |
| Scrollback memory | 10,000 lines ≤ 5 MiB for typical output, and ≤ 16 MiB even in the worst case: any output, kept within it by the memory budget of the scrollback ([0002](0002-resource-limits.md)); with a different color in every one of 120 cells, all 10,000 lines are kept |
| Rendering (UI thread, p50) | ≤ 25 ms in the scene where the whole screen changes every time, ≤ 10 ms in the scene where a log flows |

The JVM values are from the development machine (Intel Core i5-8259U). Values on real devices (ART) have not been checked. Checking the rendering frame rate on a real device was put in the review queue.

### Optimizations done

| Change | Effect (development machine) |
|---|---|
| Look up character widths in a one-byte table over the BMP | Japanese 46 → 83 MB/s |
| Write runs of printable ASCII in the ground state into cells all at once | ASCII 42 → 108 MB/s |
| Make the screen rows a ring buffer, and do not move rows on full-screen scrolls | ASCII 108 → 200 MB/s |
| Store scrollback lines compactly (drop trailing empty cells, collapse a uniform style into one). Do not reallocate `Line` on scroll | About 3 MiB for 10,000 lines (about 14 MiB before compaction) |
| Draw characters with `Canvas.drawText` (stop laying out with Compose's `TextMeasurer`) | Frame p50 of full-screen updates 100 → 50 ms (emulator) |
| Process CSI parameter bytes directly in the parsing loop, and clear parameters in constant time | SGR 56 → 70 MB/s |
| Cache line drawings in `RenderNode`s keyed by content | UI thread drawing p50 of 6 ms in the scene where a log flows |
| Share the strings of printable ASCII cells, and reuse run strings and `RenderNode`s evicted from the cache | UI thread drawing p50 of full-screen updates 26 → 21 ms |

### Known constraints

- The line drawing cache is used only with a hardware-accelerated `Canvas`. The `Canvas` of Robolectric's screenshot tests has none, so the provisional goldens compare drawing that does not go through the cache. Drawing through the cache was checked by replaying in the sample app on the emulator (`scripts/sample_replay.py`).
- The cache takes the drawing colors from `TerminalStyle`. When values that affect drawing (the number of columns, reverse video) change, all lines are redrawn.
- The render thread discards the display lists of `RenderNode`s not drawn in a frame. If a node taken from the cache has no display list (`hasDisplayList()`), it is recorded again (same as `View`). This was overlooked, so when the content of a line unused for one frame appeared on another line, it was drawn blank (found on a real device; `LineCacheTest`). Robolectric's rendering does not go through this path, so the cache's decisions are tested directly on `LineCache`.

## Alternatives considered

- **Put performance checks in the gate (check)**: Timing depends on the machine and the load, and the gate would no longer be deterministic. It is for reports.
- **Measure xterm.js in a WebView on the emulator**: It could include the differences in WebView initialization and rendering, but the measurement setup would become large. The parsing comparison is enough to confirm that vtfx is not slower.

## Impact of reversing

To change the targets, change this document and the constants in `scripts/benchmark.py`.
