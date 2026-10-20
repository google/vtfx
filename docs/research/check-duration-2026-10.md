# Duration of the check with the fuzz tasks one at a time and in parallel (2026-10-14)

A record of measurements made on 2026-10-14, when the fuzz tasks were changed to run in parallel ([decisions/0028](../decisions/0028-parallel-fuzzing.md)). It is not kept up to date: since then two more modules (`vtfx-font` and `vtfx-font-gate`) have fuzz tasks, and the build has changed.

## Setup

- A development machine with 128 CPUs.
- `scripts/check.sh` with three fuzz tasks (`vtfx-core`, `vtfx-gate`, `vtfx-input`), each fuzzing for 30 seconds.

## Results

| Configuration | `scripts/check.sh` when nothing changed |
|---|---|
| One fuzz task at a time (a shared build service with `maxParallelUsages` of 1) | About 110 seconds, with or without the build cache. The three fuzz tasks one after another took about 105 seconds; each took about 35 seconds (30 seconds of fuzzing, the start of the JVM, and the replay of the regression inputs). |
| Fuzz tasks in parallel | About 40 seconds |

Each fuzzing JVM, with its heap of at most 1 GiB, took up to about 1.2 GB of resident memory, and the three together up to about 3.4 GB at once.

Running the fuzz tasks in parallel but after all the test tasks (`mustRunAfter`) was estimated to make a full build about 15 seconds longer than running them in parallel with the tests, since the fuzzing would start only after the Robolectric tests of `vtfx-compose`.
