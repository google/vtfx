# Review queue

Items that need a human: decisions, work only humans can do, and checks on real devices. Agents add an item when they run into one, and remove it once it is resolved, after writing the outcome in the related decision or document. This file lists only open items; the outcomes live in [decisions/](decisions/README.md) and the design documents.

For each item, write what is needed, why the agent cannot do it, and links to the related decisions, documents, or code. Keep items short: point to commits by full SHA or range instead of retelling their changes, which their messages and the decisions explain.

## 1. Decisions

Until decided, the provisional decision is implemented. The impact of reversing is in "Impact of reversing" of each decision.

| Item | What to decide | Related |
|---|---|---|

## 2. Work only humans can do

| Item | Contents | Related |
|---|---|---|
| Publishing | Set up Maven Central (the permission for `com.google.vtfx`, and the publishing configuration, including the version alignment of the nine artifacts with dependency constraints in the Gradle module metadata and a BOM); decide how versions after 0.1.0 are numbered and when to go to 1.0. Agents do not publish | [0090](decisions/0090-distribution.md), [0087](decisions/0087-public-api.md) |
| Changes to protected paths | Review the changes to the paths under `security_review` in `config/protected-paths.toml` since the last review, which covered the tree up to `b6330d650be21161fa65f95d3f3d3815db83e12f` on 2026-10-25. Review the output of `scripts/report_protected_changes.py b6330d650be21161fa65f95d3f3d3815db83e12f` (the reasons are in the commit messages). Commits that change protected paths do not update this item; after a review, replace the base with the full SHA of the reviewed commit | `config/protected-paths.toml`, section 5 of [architecture.md](design/architecture.md) |

## 3. Checks on real devices

Things that cannot be checked on the emulator and the JVM. What to check, the steps, and what was already checked are in the linked section of [device-checks.md](device-checks.md).

| Item | Section of [device-checks.md](device-checks.md) |
|---|---|
| IMEs other than Gboard, voice and handwriting input | 2 |
| Key interception | 3.1 |
| AltGr on physical keyboards | 3.2 |
| TalkBack | 4 |
| Rendering performance | 5 |
| Han glyphs and locale | 6 |
| Mouse and touchpad | 7 |
| Images, and the largest image | 8 |
| The input history on switching IMEs | 9 |
| Links taken by a protected activity | 10 |
| Imported fonts | 11 |
| Copying all the text | 12 |
| IMEs on API 29 to 33 | 13 |
| Searching | 14 |
| Restarts of the IME | 15 |
