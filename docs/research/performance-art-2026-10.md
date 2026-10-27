# Throughput of the core on ART (2026-10)

A record of the measurements of the core's throughput on a device, made on 2026-10-27, when the device's throughput was first measured (topic 4 of [decisions/0089](../decisions/0089-performance.md)), and of the optimizations made then. It is not kept up to date: `scripts/benchmark.py` measures the current values.

## Setup

- A Pixel 9 (Android 16, user build), one Cortex-X4, three Cortex-A720, and four Cortex-A520 cores, reached over adb from a device lab.
- The sample app's benchmark build and its `ThroughputBenchmark` (section 17 of [device-checks.md](../device-checks.md)): the workloads of `TerminalBenchmark`, fed to a `TerminalSession` in writes of 4 KiB into 120 × 40, for 1 second of warm-up and 3 seconds of measurement each.
- Unless said otherwise, after install, when ART interprets the app and compiles hot code with its JIT.
- The benchmark's thread ran on the Cortex-X4 almost all the time (`ps -T -o PSR`). Runs one after another varied by about 5 to 10 %, the first of a series the fastest; before-and-after comparisons alternated the two builds.
- Profiles: `simpleperf record --app` on the device, which the benchmark build allows, while one workload ran for 15 seconds.

## JIT and code compiled ahead of time

Before the optimizations, the app compiled with `cmd package compile -m speed -f` ran no faster than after install: ascii 61.3 and 61.5 MB/s against 61.0 and 62.1 after install, sgr 48.6 to 49.9 against 49.7 and 50.8, cjk 51.1 to 52.4 against 53.7 and 54.6, recordings 66.2 to 67.3 against 67.5 and 70.5. After the optimizations, `scripts/benchmark.py` measured the same: ascii 99.4 MB/s compiled against 100.2 after install, sgr 67.7 against 72.6, cjk 68.8 against 76.2, recordings 93.9 against 96.8. Baseline Profiles in the libraries, which would give apps the same compiled code at install, were therefore not added: they might shorten the start of an app, which this does not measure, but they would not make the interpretation of output faster.

## What the profiles showed

- Calls per character were not inlined: before the first optimization, `Line.setNarrow`, called for each character of plain text, took 23 % of the time of the ascii workload.
- Loops over the cells that read an array from a field read the field on every cell: keeping the arrays of a line in locals made `Line.compact` alone gain ascii 84 → 100 MB/s.
- The parser's state machine took a third of the recordings workload, with calls through the interfaces `CodePointSink` and `ParserHandler` for each byte of a control sequence; `art_quick_imt_conflict_trampoline`, ART's slow path for a call through an interface, still takes 3 to 5 % of the sgr and recordings workloads.

## Optimizations

Each row is a commit; the effect is the average of the runs on the device before and after it, which the commit messages give one by one.

| Change | Effect on the device (MB/s) |
|---|---|
| Write runs of plain text into a line at once (`Line.setAscii`) | ascii 61 → 84, recordings 68 → 78 |
| Keep the arrays of a line in locals while compacting it for the scrollback | ascii 84 → 100, sgr 54 → 59 |
| Compute the underline color of the cells once per rendition, and check only the edges of the cells written for wide characters to break | cjk 60 → 69 |
| Decode whole UTF-8 sequences of two and three bytes at once | cjk 69 → 77 |
| Take the parameters of a control sequence in a loop of their own (`Params.accept`) | sgr 57 → 59, near the noise; the parser's share of the sgr profile 33 % → 27 % |
| Make no new rendition for an SGR that sets the current one or the default, and tell whether a style underlines from its bits | sgr 59 → 63, recordings 79 → 85 |
| Enter control sequences (ESC [), execute C0 controls, and dispatch final bytes in the parser's loop | sgr 63 → 73, recordings 85 → 99 |

## Before and after

On the device, alternating the build before the optimizations (`bbef86450582805e4d19108444d839759fe92440`) and the build after them (`80259426ca3aff4e57dd9c5540b7bed7a172f578`), two runs of each in two rounds:

| Workload | Before (MB/s) | After (MB/s) | alacritty_terminal 0.26.0 (MB/s) |
|---|---|---|---|
| ascii | 60.5 to 62.8 | 100.0 to 106.1 | 109 |
| sgr | 49.6 to 52.0 | 71.7 to 73.8 | 145 |
| cjk | 52.4 to 56.9 | 76.7 to 82.7 | 137 |
| recordings | 66.0 to 68.5 | 94.0 to 99.2 | 123 |

alacritty_terminal 0.26.0 (from crates.io) was measured once before, on the same device, fed the same workloads in the same chunks through JNI from an app; it was not measured again.

On the development machine's JVM (`./gradlew :vtfx-core:benchmark`, Intel Core i5-8259U), alternating the same two commits, two runs each: ascii 132 and 135 → 164 and 185 MB/s, sgr 55 and 56 → 71 and 71, cjk 62 and 71 → 94 and 95, recordings 55 and 57 → 78 and 79.

## Candidates left

From the profiles after the optimizations:

- `Line.compact`, which every line that scrolls into the scrollback goes through: 16 % of sgr and the largest part of ascii.
- `Sgr.apply`: 11 % of sgr, with an allocation of a `Pair` and a boxed color for each extended color.
- The calls through `ParserHandler`, which take ART's slow path for interfaces.
- The scan of a run of printable ASCII in `Parser.parse` and its copy in `Line.setAscii`, two passes over the same bytes.
