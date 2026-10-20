# Decisions record reasons; design documents describe the current design

- Status: Approved (2026-10-20, approved by a human in a review of the review queue)
- Date: 2026-10-20
- Related: [decisions/README.md](README.md), [AGENTS.md](../../AGENTS.md) ("Decisions", "Updating documents"), `scripts/check_doc_links.py`, section 9 of [testing.md](../design/testing.md)

## Background

`docs/` had grown to about 490 KB, of which decisions were about 400 KB. The size came less from the number of files than from what the decisions held:

- Many decisions were the only place that described the current behavior: the support matrix of the emulation (0009), the URL rules of the gate (0006), the canonical form of fonts (0040), the semantics of the boundary check (0044), the mouse behavior (0031). The design documents linked to them for the current design, so every change of behavior edited an approved decision.
- Changes were appended to the old text as dated notes ("2026-10-20, correction: ...", strikethroughs, "this said six"). To know the current state of threads and the session, a reader had to go through 0007, 0021, 0024, 0025, 0026, and 0027.
- Where a later decision changed an earlier one without a note, the earlier one silently contradicted the code (the resize delay in 0013, the copy path in 0031, the API names in 0040).
- Measurements, experiment logs, and narratives of how something came about were mixed into the reasons.

Code comments, scripts, and configuration cite documents by path and by section number (164 files outside `docs/` cite a path), and `config/protected-paths.toml` lists some documents by path.

## Decision

1. **The design documents (`docs/design/`) are the only description of the current design.** When behavior changes, they change. Decisions link to the sections that describe what they decided, rather than describing it again. Where a decision held the only description, the description moves to a design document.
2. **A decision records why**: the background, what was decided, the alternatives, and the impact of reversing. Once written it is not appended to.
   - When a decision changes, a new decision says what it replaces, and the status of the old one becomes `Superseded by [NNNN](...)`, or names the items that were superseded when only some were. A superseded decision may be cut to a summary and the link; its full text stays in the history.
   - Errors of fact (a wrong name, count, or path) are corrected in place, without a dated note.
   - Measurements and research go in `docs/research/` or in commit messages; how something came about goes in commit messages. A research document records what was measured at its date and is not kept up to date: what it led to is in the design documents.
3. **Files are not moved to new directories.** The existing paths (`decisions/`, `design/`, `research/`, and the files at the top of `docs/`) stay, and new design documents are added under `design/`.
4. **References are checked mechanically where they can be.** `scripts/check_doc_links.py`, run by `scripts/check.sh`, checks that cited paths and relative links exist and that a superseded decision links to its successor. References to sections are not checked, so documents keep their section numbers and add new sections at the end.

## Alternatives considered

- **Keep decisions as living documents with dated corrections.** Every change of behavior would go on edited approved records, and a reader would still rebuild the current state from the notes. Rejected.
- **Reorganize the directories by audience** (`process/`, `history/`, a guide for users). The gain is small next to the cost: the paths in code comments, AGENTS.md, and `config/protected-paths.toml` (security review) would all change, and the size problem is in the contents of the decisions, not in where files are. Rejected for now; with the link check in place, it can be done later.
- **Move `research/` to an archive directory.** Marking each research document as a record of its date gives the same signal without changing paths. Rejected.

## Impact of reversing

Specifications would move back from the design documents into the decisions, and the status `Superseded` would go back to dated notes. `scripts/check_doc_links.py` would stay useful on its own.
