# Decisions hold the current reasons; the history holds the past ones

- Status: Provisional
- Date: 2026-10-25
- Related: [decisions/README.md](README.md), [AGENTS.md](../../AGENTS.md) ("Decisions"), [0045](0045-decisions-and-design-documents.md), [0046](0046-history-never-rewritten.md), section 3.4 of [reviews/README.md](../reviews/README.md)

## Background

[0045](0045-decisions-and-design-documents.md) moved the description of the current design out of the decisions, but kept decisions as records that are never rewritten: a change of a decision is a new decision, and the old one stays with the status `Superseded by` or with the items that were superseded named in its status. Five days later, `docs/decisions/` held 77 decisions and about 360 KB:

- 4 decisions were superseded as a whole, and 23 others in part. A reader of a decision has to follow its status to the decisions that replaced some of its items, and read the old text knowing which parts no longer hold.
- The reasons for one area are spread over many decisions written as the area grew: the session and its threads over 0007, 0013, 0021, 0024, 0025, 0026, 0027, 0065, and 0077; the IME's editing model over ten.

Since publication the history is never rewritten ([0046](0046-history-never-rewritten.md)), so every past version of a decision can be read from the commit before it changed. Closed review reports are already removed from the tree on that ground (section 3.4 of [reviews/README.md](../reviews/README.md)).

## Decision

1. **A decision holds the reasons that hold now.** When a decision changes, the decision is rewritten, or a new decision takes its place and the old one is deleted. No decision is kept with the status `Superseded` or `Withdrawn`, and no status names superseded items: a decision that is dropped with nothing in its place is deleted. The past versions are in the history.
2. **Decisions about one area may be merged into one decision**, which is given a new number; the merged decisions are deleted. Numbers are never reused, so a deleted number stays unused.
3. **The status is per decision**: `Provisional` or `Approved`, for the whole file. Only a human approves a decision. A merge, or a rewrite, that keeps the meaning of everything it carries over (it reorders, removes repetition, removes descriptions of the current design that the design documents hold, or drops what was superseded) keeps the status `Approved` when every decision it carries over was approved; otherwise the result is `Provisional`. The commit says which decisions it carries over and that their meaning is kept.
4. **The change that deletes or renumbers a decision updates every reference to it** in the same commit. `scripts/check_doc_links.py` fails on a reference to a decision that does not exist.
5. The rest of [0045](0045-decisions-and-design-documents.md) holds: the design documents are the only description of the current design, a decision records why, and errors of fact are corrected in place.

The decisions written before this one are merged area by area. Until a decision is merged, it keeps the status it has, superseded items included.

## Alternatives considered

- **Keep the records of [0045](0045-decisions-and-design-documents.md), and cut superseded decisions to a summary and a link**: Removes the old text but not the decisions that only point elsewhere, and the reasons for one area stay spread over many files.
- **Status per item of a merged decision**: Keeps an approval exact to the item, but a human would approve items one by one, and every item would carry a status line.
- **Move the reasons into the design documents and drop `docs/decisions/`**: The design documents are already up to 37 KB each, and mixing why into what makes the current design harder to read.
- **Keep the old numbers, merging into the oldest decision of each area**: Saves updating the references to that one decision, but the title and the scope of the decision change under a number that code comments cite for what it meant before.

## Impact of reversing

Deleted decisions can be restored from the history, and statuses `Superseded by` written again. `scripts/check_doc_links.py` would check superseded statuses again.
