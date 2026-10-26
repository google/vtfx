# Checks on real devices are run by agents

- Status: Provisional
- Date: 2026-10-26
- Related: Requirements 5.3 and 7.2, [device-checks.md](../device-checks.md), [review-queue.md](../review-queue.md), sections 1 and 8 of [testing.md](../design/testing.md), section 13 of [ime-contract.md](../design/ime-contract.md), [AGENTS.md](../../AGENTS.md) ("Things only humans can do"), [0082](0082-ime-editing-model.md)

## Background

[device-checks.md](../device-checks.md) holds what the emulator and the JVM cannot check. It was written as steps for humans, who ran each check and told an agent the result, and every check stood in section 3 of [review-queue.md](../review-queue.md). Most of the steps are commands over adb and taps that an agent can send itself. A human is needed only for a judgment of how something looks or feels. The area decides who runs the checks, which of them are run now, and what the tools that drive devices may connect to.

## 1. Agents run the checks; humans judge

Agents run the checks of [device-checks.md](../device-checks.md) on real devices that they reach over adb, record the results in the related documents and decisions as before, and handle results different from the expectation as bugs ([reviews/README.md](../reviews/README.md)). A check goes to a human, in section 3 of [review-queue.md](../review-queue.md), only for a judgment of how something looks or feels, or for hardware that an agent cannot drive. The agent then prepares the evidence (screenshots, recordings, measurements) so that the human only judges. Section 16 of device-checks.md says who runs each check.

Keys, pointers, and touches reach the app through the same path as physical hardware where the device allows it: virtual input devices made with the platform's `uhid` and `uinput` (the `hid` and `uinput` shell commands that CTS uses), not events injected with `adb shell input`, which take another path than a physical device (while composing, Gboard took the keys injected with `adb shell input` itself; section 15 of device-checks.md). Where that path is not available, the check goes to a human.

The results are reports, not gates: they depend on real IMEs, devices, and timing, which section 1 of [testing.md](../design/testing.md) keeps out of gates.

**Why.** A check that waits for a human waits for days, while an agent with a device runs it in minutes and runs it again after a fix. The judgment of a human is what the agent cannot give, so that is what the human is asked for.

**Alternatives considered.**
- **Keep the checks with humans**: the checks wait, and the human's time goes to typing commands and reading logcat.
- **Make the checks gates**: real IMEs and devices are not deterministic (section 1 of testing.md).

**Impact of reversing.** Restore the opening of device-checks.md, section 3 of review-queue.md, and the item "Things only humans can do" of AGENTS.md to say that humans run the checks, and remove section 16 of device-checks.md.

## 2. IMEs other than Gboard and voice input are left for later

Rows 2.1 to 2.6 of [device-checks.md](../device-checks.md) (Samsung Keyboard, SwiftKey, Simeji, and Gboard's voice input) are not run now. They are left for some later time, with no date set. Gboard's handwriting input (rows 2.7 and 2.8) is run with the rest.

**Why.** Gboard is the required IME ([0082](0082-ime-editing-model.md)); the other IMEs are a goal without a guarantee (section 13 of [ime-contract.md](../design/ime-contract.md)). They need the IMEs installed from a store with an account, or devices of their makers, and voice input needs speech, none of which an agent has by itself.

**Impact of reversing.** Mark rows 2.1 to 2.6 as run by agents, or by humans, in section 16 of device-checks.md.

## 3. Tools that drive devices do not connect from the device to the development machine

Data flows from the development machine to the device only through adb (`adb push`, `adb shell`, `adb install`). The tools do not make the development machine reachable from the device: no `adb reverse`, and no service on the development machine for the device to connect to. Programs that a check needs on the other side of the terminal run on the device itself.

**Why.** A device under test runs code from outside the repository (IMEs, the platform, the programs that a check runs), and a lab device may be shared. A path from the device to the development machine would expose the machine, which holds credentials and source, to all of them.

**Alternatives considered.**
- **Run real programs in a pseudo-terminal on the development machine and reach them from the sample app through `adb reverse`**: the simplest way to show real programs, but the path that this topic forbids.
- **Start `/system/bin/sh` from the sample app**: a pseudo-terminal needs native code (JNI), which vtfx does not have, and the device has none of the programs that the checks need, such as programs that show images.
- **Connect the sample app over SSH to a separate machine**: the sample app would take an SSH client and a network path out of the device, which widens what security review covers.

**Impact of reversing.** Allow `adb reverse` in the tools, and write in [testing.md](../design/testing.md) what the development machine exposes to the device.
