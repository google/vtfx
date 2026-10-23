# The benchmark runs in a release build that the shell can profile

- Status: Approved (2026-10-23, approved by a human in a review of the review queue)
- Date: 2026-10-23
- Related: Requirement 6.2, [0014](0014-performance.md), [0053](0053-sample-test-extras-debug-only.md) (superseded), [0066](0066-flood-benchmark.md), sections 1 and 5 of [device-checks.md](../device-checks.md), `LaunchOptions` and `MainActivity` in `vtfx-sample`, `scripts/benchmark.py`

## Background

The sample app took its test extras, `logHostInput` and `benchmark`, only in debuggable builds ([0053](0053-sample-test-extras-debug-only.md)), so the rendering benchmark ran in the debug build. ART runs a debuggable app much slower than one that users install: on a Pixel XL, the output that a session without a display interprets in a debuggable build was about 60 % of that in a build that is not debuggable. The flood scene of [0066](0066-flood-benchmark.md) then showed far more janky frames than an app would, and targets set on it would be set on the wrong numbers.

The launcher activity is exported, so any app on the device can start it with the extras: `logHostInput` writes what the user types, passwords included, to logcat.

## Decision

- **A `benchmark` build type** of vtfx-sample: the release build (not debuggable, the release variants of the libraries), signed with the debug key so that it installs over the debug build, and with `<profileable android:shell="true"/>` in its own manifest. `scripts/benchmark.py` builds and installs it.
- **`benchmark` is taken by a debuggable build or one profileable by the shell** (`ApplicationInfo.isProfileableByShell`, read at run time like the debuggable flag); **`logHostInput` only by a debuggable build**, as before. The release build is neither, and takes neither. Another app that starts the benchmark build with `benchmark` only makes it draw a benchmark; it learns nothing from it, since the results go to logcat, which other apps cannot read.
- The other decisions of [0053](0053-sample-test-extras-debug-only.md) stay: `locale` is always taken, and the flags are read at run time rather than from `BuildConfig`.

## Alternatives considered

- **Keep running the benchmark in the debug build** and set lower targets: the numbers would measure ART's debuggable mode as much as vtfx.
- **Take `benchmark` in every build**: the release build would draw a benchmark for any app that asked; the sample should not show that pattern, and nothing needs it.
- **A Macrobenchmark module** (`androidx.benchmark`): it measures frames from another process with its own tooling, but adds dependencies and a module for what `FrameMetrics` in the app already gives.

## Impact of reversing

Remove the `benchmark` build type and `src/benchmark/`, make `LaunchOptions.read` take `benchmark` only in debuggable builds again, and have `scripts/benchmark.py` install the debug build.
