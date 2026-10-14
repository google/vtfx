# Run the fuzz tasks in parallel

- Status: Approved (2026-10-15, approved by a human in a review of the review queue. The approach was chosen by a human on 2026-10-14)
- Date: 2026-10-14
- Related: [0003](0003-fuzzing-gate.md) (fuzzing in the gate), sections 7 and 9 of [testing.md](../design/testing.md)

## Background

`./gradlew build` in `scripts/check.sh` runs the `fuzz` task of `vtfx-core`, `vtfx-gate`, and `vtfx-input` ([0003](0003-fuzzing-gate.md)). Each takes about 35 seconds: 30 seconds of fuzzing, and the start of the JVM and the replay of the regression inputs. A shared build service let only one of them run at a time, so that the fuzzers, each of which keeps a CPU busy, did not slow down the tests that ran beside them, including those with a time limit (`ResourceExhaustionTest`).

On a development machine with 128 CPUs, the build took about 110 seconds whatever had changed, with or without the build cache, even when every other task was up to date: the three fuzz tasks one after another took about 105 seconds, and all the other tasks ran in parallel beside them. The fuzz tasks are never up to date, so their time is the least that the check takes.

## Decision

- The fuzz tasks have no limit on how many run at once. They run in parallel with each other and with the other tasks, like any other task.
- The amount of fuzzing in the gate does not change: each target is still fuzzed for 30 seconds on every run.
- On the same machine, `scripts/check.sh` now takes about 40 seconds when nothing changed, instead of about 110. Each fuzzing JVM, with its heap of at most 1 GiB, took up to about 1.2 GB of resident memory, and the three together up to about 3.4 GB at once.
- The tests with a time limit do not need the fuzzers to wait. `ResourceExhaustionTest` limits the CPU time of its thread, which does not grow when other processes compete for the CPUs; its limit on the elapsed time (120 seconds) only catches hangs. The fuzzers themselves report an input as a hang only after 10 seconds.

## Alternatives considered

- **Keep one fuzz task at a time**: The check stays at about 110 seconds even when nothing changed.
- **Run the fuzz tasks in parallel, but after all the test tasks** (`mustRunAfter`): Keeps the tests apart from the fuzzers, but the fuzzing starts only after the slowest tests (the Robolectric tests of `vtfx-compose`), which makes a full build about 15 seconds longer. No test needs it, for the reason above.
- **Fuzz for less time, or skip fuzzing when the code has not changed**: Changes what the gate checks, which [0003](0003-fuzzing-gate.md) decided.

## Impact of reversing

Register a shared build service with `maxParallelUsages` of 1 in `vtfx.fuzz.gradle.kts` in `build-logic`, and declare that the fuzz task uses it (`usesService`).

On a machine with fewer CPUs than the fuzzers and the other tasks need, such as a CI runner with a few CPUs, the fuzzers share the CPUs, and each one tries fewer inputs in its 30 seconds than it would alone. A machine with little memory has to hold the three fuzzing JVMs beside the Gradle daemon and the other test JVMs. If either matters, a limit of the number of fuzz tasks at once by the number of CPUs or the memory can be added instead of reversing.
