# Research: telling Gboard about a reset with updateSelection

Status: Record. It records measurements made on 2026-11-13 and is not kept up to date. The current contract is in [ime-contract.md](../design/ime-contract.md), and the decision it led to is [0102](../decisions/0102-resets-tell-the-ime-the-selection.md).

## 1. Environment

| Device | Gboard | Input |
|---|---|---|
| A laptop with a keyboard (x86_64, API 37) | 18.5.6 | A virtual USB keyboard through `uhid` (`scripts/device_checks.py`) |
| A phone (arm64-v8a, API 37) | 17.2.3 | Taps on the software keyboard |
| The `vtfx-e2e` AVD (API 37) | 17.2.2 | Taps on the software keyboard (`scripts/e2e_ime.py`) |

The sample app's pseudo host was changed for the measurements, and the change was not kept: it could delay its output, and redraw the bottom row with every echo (save the cursor, move to the bottom row, write, restore the cursor), as a status line in tmux does. That makes every echo a reset (section 5.1 of [ime-contract.md](../design/ime-contract.md)). A logging wrapper around the `InputConnection` recorded the IME's calls.

## 2. Keys lost to restarts

The commit of a key arrived 2 to 6 ms after the key reached the app. With the echo delayed so that it arrived about when the next key was pressed, keys were lost whenever a restart fell between a key's dispatch to the IME and its commit, or a key reached the IME in the first few milliseconds after a restart; Android logged `commitText on inactive InputConnection` for each lost key, and no other key was lost.

| Reset | Keys lost |
|---|---|
| `restartInput` | 18 of 240 |
| `restartInput`, held while a key is at the IME | 8 of 600; each was a key that reached the IME 2 to 9 ms after a restart |
| `updateSelection` | 0 of 400 |

The virtual keyboard's keys reached the device in steps of about 113 ms, so the delay of the echo was set to 110 to 112 ms to hit the window.

## 3. What Gboard does after updateSelection

After `updateSelection(0, 0, -1, -1)`, Gboard called `getSurroundingText` at once, got the empty buffer, and called `finishComposingText`, the same calls as after a restart less the new connection's `requestCursorUpdates`. This held on all three devices, on the physical keyboard, and on the software keyboard in English and Japanese.

What reached the host was the same with a restart and with `updateSelection`, while every echo reset the input history:

| Typed | Without resets | With resets, either way |
|---|---|---|
| `ok`, Space, Space (phone) | `ok ⌫. ` (double-space period) | `ok  ` |
| `abc`, BS, BS (phone) | `abc⌫⌫` | `abc⌫⌫` |
| `nihon`, Space, Enter, `ka`, Enter (phone, AVD) | `日本か` | `日本か` |
| `ok`, Space, Space, `abc`, BS, BS (laptop keyboard) | `ok  abc⌫⌫` | `ok  abc⌫⌫` |

With resets, the second space was committed as a space: Gboard had read the empty buffer, so it did not replace the first space, which it no longer saw. The physical keyboard made no double-space period, and its BS arrived as `sendKeyEvent(KEYCODE_DEL)`.

Not measured: a reset after vtfx committed composing text, which still restarts the IME (topic 6 of [0082](../decisions/0082-ime-editing-model.md)); IMEs other than Gboard; Japanese on the physical keyboard.
