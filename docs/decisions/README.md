# Records of design decisions

Design decisions are recorded one decision per file. Deviations from the references (requirement 5.1) are also recorded here.

A decision records why: what needed to be decided, what was decided, the alternatives, and the impact of reversing. The current design is described in the design documents ([docs/design/](../design/)), and decisions link to them rather than describe it again ([0045](0045-decisions-and-design-documents.md)).

## File names

`NNNN-<short English name>.md` (e.g. `0001-term-name.md`). The numbers are sequential, and a number is never reused.

## Format

```markdown
# <Title of the decision>

- Status: Provisional | Approved | Superseded by [NNNN](NNNN-name.md) | Withdrawn
- Date: YYYY-MM-DD
- Related: <links to requirement sections, documents, commits>

## Background

What needed to be decided. What constraints there were.

## Decision

What was decided. Link to the sections of the design documents that describe the result.

## Alternatives considered

The options not taken, and why.

## Impact of reversing

What would need to change if this decision were reversed.
```

The status of every decision written by an agent is "Provisional". Only a human sets it to "Approved".

## Changing a decision

A decision is not appended to once written.

- When a decision changes, write a new decision that says which decision, or which of its items, it replaces. Set the status of the old one to `Superseded by [NNNN](...)`, or, when only some items are replaced, keep its status and add which items were superseded by what (e.g. `Approved; items 2 and 3 superseded by [0044](0044-boundaries-in-gradle-and-classes.md)`). `scripts/check_doc_links.py` checks that such a status links to a decision. A superseded decision may be cut to a summary and the link; its full text stays in the history.
- "Withdrawn" is for a decision that was dropped with nothing in its place.
- Correct errors of fact (a wrong name, count, or path) in place, without a dated note.
- Do not add measurements, experiment logs, or how something came about: those go in [docs/research/](../research/) or in commit messages.

Milestone numbers (M1–M13) in older decisions refer to the plan of the initial development, which has been completed.
