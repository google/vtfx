# Performance optimizations and their effects (2026-10)

A record of the optimizations made in 2026-10 to reach the performance targets of [decisions/0014](../decisions/0014-performance.md), and of what each gained when it was made. It is not kept up to date: `scripts/benchmark.py` measures the current values.

## Setup

- Throughput and memory: the JVM on the development machine (Intel Core i5-8259U), with `TerminalBenchmark` in `vtfx-core` (`./gradlew :vtfx-core:benchmark`).
- Rendering: the sample app's `RenderBenchmark` on the emulator, whose GPU is software.

## Optimizations

| Change | Effect |
|---|---|
| Look up character widths in a one-byte table over the BMP | Japanese 46 → 83 MB/s |
| Write runs of printable ASCII in the ground state into cells all at once | ASCII 42 → 108 MB/s |
| Make the screen rows a ring buffer, and do not move rows on full-screen scrolls | ASCII 108 → 200 MB/s |
| Store scrollback lines compactly (drop trailing empty cells, collapse a uniform style into one). Do not reallocate `Line` on scroll | About 3 MiB for 10,000 lines (about 14 MiB before compaction) |
| Draw characters with `Canvas.drawText` (stop laying out with Compose's `TextMeasurer`) | Frame p50 of full-screen updates 100 → 50 ms (emulator) |
| Process CSI parameter bytes directly in the parsing loop, and clear parameters in constant time | SGR 56 → 70 MB/s |
| Cache line drawings in `RenderNode`s keyed by content | UI thread drawing p50 of 6 ms in the scene where a log flows |
| Share the strings of printable ASCII cells, and reuse run strings and `RenderNode`s evicted from the cache | UI thread drawing p50 of full-screen updates 26 → 21 ms |

## Notes on the line cache

- The line cache is used only with a hardware-accelerated `Canvas`. The `Canvas` of Robolectric's screenshot tests has none, so the provisional goldens check drawing that does not go through the cache. Drawing through the cache was checked by replaying recordings in the sample app on the emulator (`scripts/sample_replay.py`).
- The render thread discards the display lists of `RenderNode`s not drawn in a frame. The cache at first did not record such a node again, so the content of a line unused for one frame, appearing on another line, was drawn blank. It was found on a real device; Robolectric's rendering does not go through this path, so the cache's decisions are tested directly on `LineCache` (`LineCacheTest`).
