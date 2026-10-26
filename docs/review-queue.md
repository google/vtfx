# Review queue

Items that need a human: decisions, work only humans can do, and the judgments that checks on real devices leave to a human. Agents add an item when they run into one, and remove it once it is resolved, after writing the outcome in the related decision or document. This file lists only open items; the outcomes live in [decisions/](decisions/README.md) and the design documents.

For each item, write what is needed, why the agent cannot do it, and links to the related decisions, documents, or code. Keep items short: point to commits by full SHA or range instead of retelling their changes, which their messages and the decisions explain.

## 1. Decisions

Until decided, the provisional decision is implemented. The impact of reversing is in "Impact of reversing" of each decision.

| Item | What to decide | Related |
|---|---|---|

## 2. Work only humans can do

| Item | Contents | Related |
|---|---|---|
| Publishing | Set up Maven Central (the permission for `com.google.vtfx`, and the publishing configuration, including the version alignment of the nine artifacts with dependency constraints in the Gradle module metadata and a BOM); decide how versions after 0.1.0 are numbered and when to go to 1.0. Agents do not publish | [0090](decisions/0090-distribution.md), [0087](decisions/0087-public-api.md) |
| Changes to protected paths | Review the changes to the paths under `security_review` in `config/protected-paths.toml` since the last review, which covered the tree up to `9841666957d7d409f19127e801c8a9fd7251d3e2` on 2026-10-26. Review the output of `scripts/report_protected_changes.py 9841666957d7d409f19127e801c8a9fd7251d3e2` (the reasons are in the commit messages). Commits that change protected paths do not update this item; after a review, replace the base with the full SHA of the reviewed commit | `config/protected-paths.toml`, section 5 of [architecture.md](design/architecture.md) |

## 3. Checks on real devices

Checks that need a human: a judgment of how something looks or feels, or hardware that an agent cannot drive. Agents run the other checks of [device-checks.md](device-checks.md) themselves, and add an item here with the evidence for the judgment (screenshots, recordings, measurements), so that the human only judges ([decisions/0092](decisions/0092-device-checks-by-agents.md)). Section 16 of device-checks.md says what each check is expected to leave to a human.

| Item | Section of [device-checks.md](device-checks.md) | Evidence |
|---|---|---|
