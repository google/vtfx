# The sample app takes its test extras only in debuggable builds

- Status: Provisional
- Date: 2026-10-22
- Related: Requirement 6.1, section 1 of [device-checks.md](../device-checks.md), `LaunchOptions` and `MainActivity` in `vtfx-sample`, `scripts/e2e_ime.py`, `scripts/benchmark.py`

## Background

The launcher activity of vtfx-sample is exported, as a launcher activity must be. Its extras `logHostInput` and `benchmark` exist for the emulator tests and the benchmark: the first writes every byte sent to the host, passwords included, to logcat in hex until the process ends. Any app on the device could start the activity with it, and the input would then reach bug reports and log collectors. The sample is also what app developers copy.

## Decision

- **`logHostInput` and `benchmark` are taken only when the app is debuggable** (`ApplicationInfo.FLAG_DEBUGGABLE`), which the debug build that the scripts install is. `locale` changes only the glyphs and is always taken.
- **Read from the flag at run time**, rather than from `BuildConfig.DEBUG`, which the build does not generate, or a debug-only manifest or activity, which would change how the scripts start the app.

## Alternatives considered

- **A separate activity for tests, not exported**: `am start` from the shell could still start it, but the scripts and device-checks.md would change for little gain over the flag.
- **Keep the extras in every build**: the sample is not published, but it should not show an unsafe pattern.

## Impact of reversing

`LaunchOptions.read` would take the extras without the flag, and section 1 of device-checks.md would drop the note.
