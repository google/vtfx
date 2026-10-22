# 2026-10-22 Bugs reproduced in a code review

- Review target: `c8ca7d75f90b459b893c072e7c2f2d2ec7365b45`
- Reviewer: coding agent
- Points of view: 2 (losing input), 3 (discrepancies with ime-contract.md and decisions/0014)

Reproduced 2 bugs with 2 test cases. This review did not change product code.

R1 was reproduced in `vtfx-sample` on a real device with Gboard and a hardware keyboard, and both on the JVM with `vtfx-session` alone.

## Status

| ID | Severity | Bug | State | Fix commit / verification |
|---|---|---|---|---|
| R1 | High | A key typed right after Enter is lost: the echo of Enter restarts the IME a second time | Open | — |
| R2 | Medium | While output floods, the display waits for the state lock for hundreds of milliseconds per frame | Open | — |

The meaning of the states and how to update them are in 3.3 of [README.md](README.md).

## Running the reproduction tests

The reproduction tests are in [repro-2026-10-22-device-checks/](repro-2026-10-22-device-checks/). Run them from the root of the repository.

```sh
./gradlew --console=plain --no-configuration-cache \
  -I docs/reviews/repro-2026-10-22-device-checks/review.init.gradle \
  :vtfx-session:reviewRepro
```

At the target commit, the result is **2 tests completed, 2 failed**.
Only the JDK is needed. R2 measures time: it failed in each of the runs at review time (see the results at the end), but it depends on the machine.

## R1: A key typed right after Enter is lost

- Related: [ImeEditor.kt](../../vtfx-input/src/main/kotlin/com/google/vtfx/input/ImeEditor.kt) (`endOperation`, `restartIfReady`, `changedSinceRestart`), [TerminalSession.kt](../../vtfx-session/src/main/kotlin/com/google/vtfx/session/TerminalSession.kt) (`feed`, which applies a reset by host output), [TerminalInputView.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalInputView.kt) (`restartInput`)
- Contract: section 5.2 step 3 of [ime-contract.md](../design/ime-contract.md): `restartInput` is not called "unless the editing buffer has not changed since the last restart", so that host output that moves the cursor to another line does not restart the IME while the user types nothing
- Reproduction test: `aFinishWithoutComposingTextAfterEnterDoesNotRestartAgainOnTheEcho`

Steps:

1. The IME commits `ab`, then Enter arrives as a key down. The session restarts the IME once.
2. `finishComposingText` arrives with no composing text. On the device, Android calls it on the connection that the restart closed, right after `restartInput` (traced in `vtfx-sample`: `sendKeyEvent(ENTER)`, `restartInput`, `onCreateInputConnection`, `closeConnection`, then `finishComposingText`).
3. The host echoes `ab\r\n`, which moves the cursor to the next line.

Expected result: one restart. The editing buffer has not changed since the restart in step 1.

Actual result: two restarts (`expected:<1> but was:<2>`).

On the device, the second restart makes the IME lose a key. With Gboard and a hardware keyboard, Gboard takes each key down and commits the character through the connection. In `vtfx-sample`, `adb shell input keyevent 66` followed at once by `adb shell input text typing_test_0123456789` sent `yping_test_0123456789` to the host in 1 of 5 runs, and with `clear` and Enter before the line, in 3 of 5 runs. The trace shows the key down of the first `t` reaching the view, but its `commitText` never reaching any open connection; the key up arrives on the new connection. Typing at an idle prompt lost nothing (5 of 5).

View of the cause: `finishComposingText` (and any other operation that ends with `endOperation`) sets `changedSinceRestart` even when it changed nothing. The reset that the host's echo triggers then sees a change and restarts the IME again. Between the two restarts, the IME is bound to a connection that the second restart closes, and what it commits there is dropped.

Direction of the fix: check the other IME calls that Android or IMEs make on a connection right after a restart without changing the buffer (`beginBatchEdit`/`endBatchEdit` with nothing in between, `setComposingRegion` of an empty region, `finishComposingText` with no composing text), and output that moves the cursor several times right after Enter (a prompt drawn in several receives). The lost key comes from any restart that follows another closely, so it may also be worth checking whether a reset by Enter and the reset by its echo can share one restart.

## R2: While output floods, the display waits for the state lock for hundreds of milliseconds per frame

- Related: [TerminalSession.kt](../../vtfx-session/src/main/kotlin/com/google/vtfx/session/TerminalSession.kt) (`feed`, `snapshot`, the state lock), [TerminalDisplay.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/TerminalDisplay.kt) (`displaySnapshot`)
- Contract: [decisions/0014](../decisions/0014-performance.md): the UI thread's drawing takes at most 25 ms (p50). Its rendering benchmark feeds an update every 16 ms, so output that arrives without a break is not measured
- Reproduction test: `aSnapshotDoesNotWaitLongWhileOutputFloods`

Steps:

1. Another thread feeds 16 KiB chunks of text without a break, as an app's reader thread does while the host prints a large file.
2. Every 16 ms, take `snapshot()`, as the display does for each frame, and measure how long it takes.

Expected result: a snapshot takes far less than 25 ms. Without output, it takes about 0.06 ms.

Actual result: most frames wait hundreds of milliseconds for the lock. In the 7 runs at review time, 8 to 17 snapshots were taken in 2 seconds instead of about 120, with p90 between 222 and 1237 ms.

View of the cause: `feed` holds the state lock while it interprets a whole chunk, and takes it again right away for the next one. The lock is an intrinsic lock, which does not hand itself to a waiting thread, so the thread taking the screen for a frame waits until the feeding thread happens to leave a gap. Drawing also takes the lock through `edit` for the composing text.

Direction of the fix: check with the chunk sizes that apps pass (a network connection can deliver tens of KiB at a time), on a real device, and with the main thread also handling keys during the flood (each key takes the same lock). Check that a fix keeps the throughput targets of decisions/0014.

## Unverified concerns

- The lost keys of R1 were reproduced only with Gboard and a hardware keyboard on one device, with keys injected by `adb shell input`, which is much faster than a person types. Whether the second restart also loses keys typed by a person was not tried.
- R2 was reproduced only on the JVM. How long frames take on a device while output floods was not measured in `vtfx-sample`. Whether the main thread also falls behind on the messages that `TerminalDisplay.onOutput` posts for each `feed` that scrolls (`followScrollback`) was not measured separately from the waits for the lock.

## Verification results at review time

- `scripts/check.sh`: passed
- Reproduction tests: 2 tests completed, 2 failed, in each of 3 runs. An earlier version of R2 that asserted on p50 passed in 1 of 4 runs, so it asserts on p90
- Verification not run: the emulator e2e; a device repro of R2 in `vtfx-sample`
