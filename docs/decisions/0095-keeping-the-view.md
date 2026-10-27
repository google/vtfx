# Keeping the view of a terminal across leaving the composition

- Status: Approved
- Date: 2026-10-27
- Related: Requirement 5.5, section 5 of [compose-input.md](../design/compose-input.md), section 4 of [session-and-threads.md](../design/session-and-threads.md), [usage.md](../usage.md), [0084](0084-session-and-threads.md), [0085](0085-compose-interaction.md)

## Background

The display state of a `Terminal` (the scrollback position, the selection, and the search) is held by its `TerminalDisplay`, which goes away when the `Terminal` leaves the composition ([0084](0084-session-and-threads.md)), and `TerminalState` is the app's handle on it (topic 5 of [0085](0085-compose-interaction.md)). Apps with tabs or Navigation take the `Terminal` of a screen out of the composition while another screen is shown, and keep only what the screen saved with `rememberSaveable`. The session outlives the composable; the process may not.

## Decision

1. When the `Terminal` that a `TerminalState` acts on leaves the composition, the state keeps the line at the top of the display, the query, and the current match, by their line numbers and generation and the key of the session, and gives them to the next display it is attached to, which shows the same lines and match if the session and the generation are the same, and the bottom otherwise, and searches for the same query (section 5 of [compose-input.md](../design/compose-input.md)).
2. `rememberTerminalState()` remembers the state with `rememberSaveable`, and saves the same values.
3. The selection is not kept.
4. A session is named in a saved view by a random key, held weakly by `vtfx-compose`.

## Why

An app with tabs or Navigation takes the `Terminal` of a screen out of the composition while another is shown, without resizing. If the view went with the display, the user who comes back would find the terminal at the bottom and the search gone, while the app's own search field, kept with `rememberSaveable`, still shows the text; and the app would have no means of keeping them, since the display holds them. Compose's states of scrolling (`rememberLazyListState`, `rememberScrollState`) are kept the same way. Line numbers keep naming the same line as output pushes it into the scrollback, so output while away does not move the view, and the generation tells when they no longer do: after a rotation, which resizes and reflows the lines, the display shows the bottom. The state, not the display, keeps the view, so that it is there for the `Saver` whether the instance state is saved before or after the display leaves, and gives it to the next display in either case. The selection is checked against the lines of the screen it held at the last check, which the state does not keep and a `Bundle` should not hold; text that changed while away could not be told from text that did not. A saved view outlives the process, while the session the app creates after the process was killed starts again from the same numbers and generation; a random key that the new process cannot have tells them apart, and needs nothing from `vtfx-session`.

## Alternatives considered

- **Keep nothing**: Rotation renumbers the lines anyway, but switching tabs does not, and the user loses the place in the scrollback and the search.
- **Have the app hold one `TerminalDisplay` per session across leaving the composition**: The display holds a `Handler`, observers, and the text of the scrollback while searching, which a hidden tab would keep, and it cannot be saved in a `Bundle` for Navigation or a configuration change.
- **Keep the view in `TerminalSession`, keyed by the `Terminal`'s saved key**: The session would hold display state ([0084](0084-session-and-threads.md)), and nothing would tell it when a key is no longer used.
- **Keep the scroll offset (lines from the bottom) instead of the line number**: Output while away would move the view by the lines it pushed into the scrollback.
- **Keep the selection, checking it against the screen when the display comes back**: A change of its text while away would go unnoticed.
- **Name the session by an identifier in `TerminalSession`**: Another internal API on the session for a concern of the display.

## Impact of reversing

Remove `SavedView`, `TerminalDisplay.view` and `restore`, and `TerminalState.Saver`, have `rememberTerminalState()` use `remember`, and revise section 5 of [compose-input.md](../design/compose-input.md), section 4 of [session-and-threads.md](../design/session-and-threads.md), and [usage.md](../usage.md).
