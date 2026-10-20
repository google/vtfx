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

`scripts/benchmark.py` measures all of them and compares them with these targets; the targets are its constants. The targets were set from measurements on the development machine's JVM and on the emulator. They have not been checked on real devices (ART); checking the rendering on a real device is in section 5 of [device-checks.md](../device-checks.md). The optimizations made to reach the targets, and what each gained, are recorded in [research/performance-2026-10.md](../research/performance-2026-10.md).

## Alternatives considered

- **Put performance checks in the gate (check)**: Timing depends on the machine and the load, and the gate would no longer be deterministic. It is for reports.
- **Measure xterm.js in a WebView on the emulator**: It could include the differences in WebView initialization and rendering, but the measurement setup would become large. The parsing comparison is enough to confirm that vtfx is not slower.

## Impact of reversing

To change the targets, change this document and the constants in `scripts/benchmark.py`.
