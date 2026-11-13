# Resets tell the IME the selection instead of restarting it

- Status: Provisional
- Date: 2026-11-13
- Related: Requirement 5.3, sections 5.1 and 5.2 of [ime-contract.md](../design/ime-contract.md), [0082](0082-ime-editing-model.md), [research/ime-gboard-reset.md](../research/ime-gboard-reset.md)

## Background

Every reset of the input history restarted the IME's input with `InputMethodManager.restartInput`, unless the IME had seen no edit since the last restart. A restart closes the IME's connection at once, and Android drops what the IME commits to the closed connection (`RemoteInputConnectionImpl` logs `commitText on inactive InputConnection`). Gboard handles the keys of a physical keyboard itself and commits each character through the connection, so a key whose commit crossed a restart was lost: one that reached the IME just before the restart, with its commit still on the way, and one that reached the IME in the tens of milliseconds after it, before the IME took the new connection. Programs that redraw another line on every key, such as a status line in tmux, made the host's echo of each key reset the input history, so the IME restarted on every key; keys typed about when the previous echo arrived were lost.

## 1. A reset tells the IME the new selection

Once the batch edit has ended and there is no composing text, a reset tells the IME the selection of the empty edit buffer with `InputMethodManager.updateSelection` (`ImeEditor.Listener.updateSelection`, `SessionObserver.onEditorReset`). The IME's input is restarted instead only when the IME may still have composing text that vtfx committed on its own (`ImeEditor` commits it for a key, a paste, the app's text, sticky modifiers, or the limit of the input history), and when switching direct input. See section 5.2 of [ime-contract.md](../design/ime-contract.md).

**Why.** The connection stays open, so what the IME commits around the reset arrives, and is sent after the edits made before the reset. Gboard reads the edit buffer again right after the report (`getSurroundingText`, which returns the empty buffer), on a physical keyboard and on the software keyboard, in English and in Japanese, and what reached the host was the same as with a restart, the double-space period included: a second space after a reset is a space, not a replacement of text that the IME no longer sees ([research/ime-gboard-reset.md](../research/ime-gboard-reset.md)). The report is also what any change of the edit buffer outside the IME's calls already makes (section 7 of [ime-contract.md](../design/ime-contract.md)). A composing text that vtfx committed is different: Gboard keeps its composition after `updateSelection` (topic 6 of [0082](0082-ime-editing-model.md)), and those resets follow a key or a tap of the user, after which the next key comes later than the restart takes. The IME reads `inputType` only when its input starts, so switching direct input needs a restart. Without the restarts for host output and for keys that only send, Gboard no longer hides and shows its toolbar for physical keyboards on each of them, and the rule that a reset restarts the IME only after an edit is no longer needed: the selection is reported only when it changes.

**Alternatives considered.**
- **Hold the restart while a key is at the IME, until the IME commits it or the key comes back unconsumed**: it fixed only the keys whose commit was on the way at the restart; keys that reached the IME right after the restart were still committed to the closed connection (on a real device, 18 lost keys of 240 became 8 of 600, and none of 400 with this decision).
- **`InputMethodManager.invalidateInput`** (API 34): it also drops what the IME made for the previous session of the connection, by a session id.
- **Reset only when the line under the cursor changed, not when the cursor visits another line and comes back**: fewer resets with status lines, but Enter and real prompt changes would still restart the IME while the user types.

**Impact of reversing.** Make `ImeEditor.tellResetIfReady` call `listener.restartInput()` again, with the rule of an edit since the last restart, and revise the tests of resets in `ImeEditorTest`, `TerminalSessionTest`, `TerminalDisplayTest`, and `TerminalInputViewTest`, section 5.2 of [ime-contract.md](../design/ime-contract.md), and section 15 of [device-checks.md](../device-checks.md).
