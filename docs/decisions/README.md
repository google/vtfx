# Records of design decisions

Design decisions are recorded one decision per file. Deviations from the references (requirement 5.1) are also recorded here.

## File names

`NNNN-<short English name>.md` (e.g. `0001-term-name.md`). The numbers are sequential.

## Format

```markdown
# <Title of the decision>

- Status: Provisional | Approved | Withdrawn
- Date: YYYY-MM-DD
- Related: <links to requirement sections, documents, commits>

## Background

What needed to be decided. What constraints there were.

## Decision

What was decided.

## Alternatives considered

The options not taken, and why.

## Impact of reversing

What would need to change if this decision were reversed.
```

The status of every decision written by an agent is "Provisional". Only a human sets it to "Approved".

Milestone numbers (M1–M13) in older decisions refer to the plan of the initial development, which has been completed.
