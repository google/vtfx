# Performance targets and benchmarks

- Status: Approved
- Date: 2026-10-25
- Related: Requirements 6.1 and 6.2, section 1 of [testing.md](../design/testing.md), section 10.2 of [emulation.md](../design/emulation.md), sections 1 and 5 of [device-checks.md](../device-checks.md), [research/performance-2026-10.md](../research/performance-2026-10.md), [0081](0081-resource-limits.md), [0084](0084-session-and-threads.md), `scripts/benchmark.py`

## Background

Requirement 6.2 asks for being more efficient than WebView with xterm.js, and for concrete targets for throughput, the rendering frame rate, and the memory limit of scrollback. Timing depends on the machine and the load, while gates are deterministic (section 1 of [testing.md](../design/testing.md)). The rendering benchmark runs in the sample app, whose launcher activity is exported, as a launcher activity must be.

## 1. Targets and how they are measured

`scripts/benchmark.py` measures the following and reports them against the targets, which are its constants; it is not a gate.

1. **Throughput**: `TerminalBenchmark` in `vtfx-core` (`./gradlew :vtfx-core:benchmark`) writes ASCII text, colored output with many SGRs, Japanese, and recordings of real apps (`recordings/`) in 4 KiB chunks into a 120 × 40 terminal, and measures MB/s of host output. The same loads run through `@xterm/headless` (6.0.0, Node 24) on the same machine (`tools/benchmark/xterm-headless.mjs`).
2. **Scrollback memory**: an estimate of the size of the array of scrollback lines when the default 10,000 lines are filled with each load, and, as the worst case, with lines in which every one of 120 cells has a different color.
3. **Rendering**: the sample app's `RenderBenchmark` on the emulator, in two scenes with updates every 16 ms (`full`: the whole screen, equivalent to 120 × 50, changes every time; `scroll`: colored log lines flow one at a time), measures the p50 of the UI thread's drawing time in `FrameMetrics` (`DRAW_DURATION`). The time of the whole frame is not a target. The steps are in section 5 of [device-checks.md](../device-checks.md).

| Item | Target |
|---|---|
| Throughput | ASCII ≥ 120 MB/s, SGR ≥ 40 MB/s, Japanese ≥ 70 MB/s, recordings of real apps ≥ 30 MB/s. And each at least 2 times xterm.js (headless) |
| Scrollback memory | 10,000 lines ≤ 5 MiB for typical output, and ≤ 16 MiB even in the worst case: any output, kept within it by the memory budget of the scrollback (section 10.2 of [emulation.md](../design/emulation.md), [0081](0081-resource-limits.md)); with a different color in every one of 120 cells, all 10,000 lines are kept |
| Rendering (UI thread, p50) | ≤ 25 ms in `full`, ≤ 10 ms in `scroll` |

The targets were set from measurements on the development machine's JVM and on the emulator, and have not been checked on real devices (ART); section 5 of [device-checks.md](../device-checks.md) checks the rendering on a real device. The optimizations made to reach them are in [research/performance-2026-10.md](../research/performance-2026-10.md).

**Why.** xterm.js in a WebView uses the same parsing code as `@xterm/headless`, so the headless comparison is a lower bound that excludes the WebView's share, and it is enough to confirm that vtfx is not slower. The emulator's GPU is software, so only the UI thread's drawing time is a target there.

**Alternatives considered.**
- **Put performance checks in the gate (check)**: Timing depends on the machine and the load, and the gate would no longer be deterministic. It is for reports.
- **Measure xterm.js in a WebView on the emulator**: It could include the differences in WebView initialization and rendering, but the measurement setup would become large.

**Impact of reversing.** To change the targets, change this decision and the constants in `scripts/benchmark.py`.

## 2. A rendering scene of output without a break

1. **A third scene, `flood`**: a thread of the benchmark feeds colored log lines, in writes of 16 KiB, without a break for 5 s.
2. **It reports frames per second and the share of janky frames first**: janky frames are those whose `TOTAL_DURATION` is longer than the refresh interval. The percentiles of the whole frame and of the UI thread's drawing are reported too.
3. **The throughput without a display**: before the flood, the same output is fed for as long, on another thread, to a session that no display shows. The throughput with the display divided by it is what the display costs the output on that device.
4. **No target yet.** A target needs a value on a reference device, which a human decides ([review-queue.md](../review-queue.md)); `scripts/benchmark.py` reports the scene without marking it.

The values are in section 5 of [device-checks.md](../device-checks.md); the scene runs in the benchmark build of topic 3.

**Why.** The scenes of topic 1 feed an update every 16 ms, so between two frames the session interprets at most one small write. When the host prints a large file, an app's reader thread calls `feed` again as soon as the previous call returns: the main thread waits for the state lock ([0084](0084-session-and-threads.md)), every row of the display is new on every frame, and the reader thread competes with the main thread, the render thread, and the garbage collector for the CPU. The janky frames this causes are not measured by the scenes of topic 1. 16 KiB is a common size of a read from a socket. The duration is fixed rather than the bytes, so that a slow and a fast device are measured over the same time. Since every row is new on every frame however fast the device is, a frame is due at every vsync and its work does not grow with the speed of the device: frames per second below the refresh rate are vsyncs without a frame. Both values are comparable between devices and refresh rates, which milliseconds are not.

**Alternatives considered.**
- **A fixed amount of output**: a fast device would finish in less time than a few hundred frames, and its result would be noisier.
- **Output at a fixed rate**, such as 1 MB/s: the work of a frame would be the same on every device, but the rate that loads a slow device leaves a fast one idle, and no rate stands for "as fast as the host prints".
- **Count janky frames with `dumpsys gfxinfo`**: its criteria change between Android versions, and the window of the scene would have to be cut out of the dump by a script; `FrameMetrics` gives the frames of the scene alone.

**Impact of reversing.** Remove the `flood` scene from `RenderBenchmark`, its section from `scripts/benchmark.py`, and its row from section 5 of device-checks.md.

## 3. The benchmark build and the test extras of the sample app

1. **A `benchmark` build type** of vtfx-sample: the release build (not debuggable, the release variants of the libraries), signed with the debug key so that it installs over the debug build, with `<profileable android:shell="true"/>` in its own manifest. `scripts/benchmark.py` builds and installs it (section 5 of [device-checks.md](../device-checks.md)).
2. **`benchmark` is taken by a debuggable build or one profileable by the shell; `logHostInput` only by a debuggable build**, so the release build takes neither. `locale` changes only the glyphs and is always taken (section 1 of [device-checks.md](../device-checks.md)).
3. **The flags are read at run time** (`ApplicationInfo.FLAG_DEBUGGABLE`, `ApplicationInfo.isProfileableByShell`), rather than from `BuildConfig.DEBUG` or a debug-only manifest or activity.

**Why.** ART runs a debuggable app much slower than one that users install, so in the debug build the scene of topic 2 showed far more janky frames than an app would, and targets would be set on the wrong numbers. Any app can start the exported activity with the extras. `logHostInput` writes every byte sent to the host, passwords included, to logcat until the process ends, from where it would reach bug reports and log collectors; and the sample is what app developers copy. An app that starts the benchmark build with `benchmark` only makes it draw a benchmark and learns nothing, since the results go to logcat, which other apps cannot read. The build does not generate `BuildConfig.DEBUG`, and a debug-only manifest or activity would change how the scripts start the app.

**Alternatives considered.**
- **Keep running the benchmark in the debug build** and set lower targets: the numbers would measure ART's debuggable mode as much as vtfx.
- **Take the extras in every build**: the release build would log what the user types, or draw a benchmark, for any app that asked; the sample is not published, but it should not show that pattern, and nothing needs it.
- **A separate activity for tests, not exported**: `am start` from the shell could still start it, but the scripts and device-checks.md would change for little gain over the flags.
- **A Macrobenchmark module** (`androidx.benchmark`): it measures frames from another process with its own tooling, but adds dependencies and a module for what `FrameMetrics` in the app already gives.

**Impact of reversing.** Remove the `benchmark` build type and `src/benchmark/`, make `LaunchOptions.read` take `benchmark` only in debuggable builds again, and have `scripts/benchmark.py` install the debug build. Taking the extras in every build would make `LaunchOptions.read` take them without the flags, and drop the note in section 1 of device-checks.md.
