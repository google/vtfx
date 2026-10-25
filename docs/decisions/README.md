# Records of design decisions

Design decisions are recorded one decision per file. Deviations from the references (requirement 5.1) are also recorded here.

A decision records why: what needed to be decided, what was decided, the alternatives, and the impact of reversing. The current design is described in the design documents ([docs/design/](../design/)), and decisions link to them rather than describe it again ([0045](0045-decisions-and-design-documents.md)).

## File names

`NNNN-<short English name>.md` (e.g. `0001-term-name.md`). The numbers are sequential, and a number is never reused: the number of a deleted decision stays unused.

## Format

```markdown
# <Title of the decision>

- Status: Provisional | Approved
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

The status is for the whole decision. The status of every decision written by an agent is "Provisional". Only a human sets it to "Approved", except that a merge or a rewrite that keeps the meaning of everything it carries over keeps "Approved" when every decision it carries over was approved ([0078](0078-decisions-hold-current-reasons.md)).

## Changing a decision

Decisions hold the reasons that hold now; the past versions are in the history, which is never rewritten ([0078](0078-decisions-hold-current-reasons.md)).

- When a decision changes, rewrite it, or write a new decision in its place and delete the old one. A decision that is dropped with nothing in its place is deleted.
- Decisions about one area may be merged into one decision with a new number; the merged decisions are deleted. Say in the commit which decisions are carried over, and whether their meaning is kept.
- The change that deletes or renumbers a decision updates every reference to it. `scripts/check_doc_links.py` fails on a reference to a decision that does not exist.
- Correct errors of fact (a wrong name, count, or path) in place, without a dated note.
- Do not add measurements, experiment logs, or how something came about: those go in [docs/research/](../research/) or in commit messages.

The decisions written before 0078 are being merged area by area; until then, some of them keep the status `Superseded by [NNNN](...)`, or name their superseded items in their status, and `scripts/check_doc_links.py` checks that such a status links to a decision.

Milestone numbers (M1–M13) in older decisions refer to the plan of the initial development, which has been completed.
