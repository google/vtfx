# A rendering benchmark of output without a break

- Status: Approved (2026-10-23, approved by a human in a review of the review queue)
- Date: 2026-10-22
- Related: Requirement 6.2, [0014](0014-performance.md), [0067](0067-profileable-benchmark-build.md), [0065](0065-fair-state-lock.md), section 5 of [device-checks.md](../device-checks.md), `RenderBenchmark` in `vtfx-sample`, `scripts/benchmark.py`

## Background

The rendering benchmark of [0014](0014-performance.md) feeds an update every 16 ms, so between two frames the session interprets at most one small write. When the host prints a large file, an app's reader thread calls `feed` again as soon as the previous call returns: the main thread then waits for the state lock ([0065](0065-fair-state-lock.md)), every row of the display is new on every frame, and the reader thread competes with the main thread, the render thread, and the garbage collector for the CPU. On a Pixel XL this showed as janky frames that neither scene of the benchmark measured.

## Decision

- **A third scene, `flood`**, after the two of [0014](0014-performance.md): a thread of the benchmark feeds colored log lines, in writes of 16 KiB, a common size of a read from a socket, without a break for 5 s. The duration is fixed rather than the bytes, so that a slow and a fast device are measured over the same time.
- **Frames per second and the share of janky frames** are what the scene reports first. Every row is new on every frame however fast the device is, so a frame is due at every vsync, and the work of a frame does not grow with the speed of the device. Frames per second below the refresh rate are vsyncs without a frame; janky frames are those whose `TOTAL_DURATION` is longer than the refresh interval. Both are comparable between devices and refresh rates, which milliseconds are not. The percentiles of the whole frame and of the UI thread's drawing are reported too.
- **The throughput without a display**: before the flood, the same output is fed for as long to a session that no display shows, on another thread too. The throughput with the display divided by it is what the display costs the output on that device.
- **No target yet.** A target needs a reference device and a value, which a human decides (review-queue.md). `scripts/benchmark.py` reports the scene without marking it.

## Alternatives considered

- **A fixed amount of output**: a fast device would finish in less time than a few hundred frames, and its result would be noisier.
- **Output at a fixed rate**, such as 1 MB/s: the work of a frame would be the same on every device, but the rate that loads a slow device leaves a fast one idle, and no rate stands for "as fast as the host prints".
- **Count janky frames with `dumpsys gfxinfo`**: its criteria change between Android versions, and the window of the scene would have to be cut out of the dump by a script; `FrameMetrics` gives the frames of the scene alone.

## Impact of reversing

Remove the `flood` scene from `RenderBenchmark` and its section from `scripts/benchmark.py`, and its row from section 5 of device-checks.md.
