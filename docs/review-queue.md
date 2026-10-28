# Review queue

Items that need a human: decisions, work only humans can do, and the judgments that checks on real devices leave to a human. Agents add an item when they run into one, and remove it once it is resolved, after writing the outcome in the related decision or document. This file lists only open items; the outcomes live in [decisions/](decisions/README.md) and the design documents.

For each item, write what is needed, why the agent cannot do it, and links to the related decisions, documents, or code. Keep items short: point to commits by full SHA or range instead of retelling their changes, which their messages and the decisions explain.

## 1. Decisions

Until decided, the provisional decision is implemented. The impact of reversing is in "Impact of reversing" of each decision.

| Item | What to decide | Related |
|---|---|---|
| Colors set by the host | Approve [0096](decisions/0096-host-colors.md) (the host may set the palette, the default colors, and the cursor color, without a setting for the app to refuse them; xterm's forms without X11 color names; DECSTR and RIS reset only the palette, as in xterm), and approve again [0079](decisions/0079-emulation-scope.md), which went back to Provisional when setting colors left its list of features left out | [0096](decisions/0096-host-colors.md), [0079](decisions/0079-emulation-scope.md), section 12 of [emulation.md](design/emulation.md) |
## 2. Work only humans can do

| Item | Contents | Related |
|---|---|---|
| Publishing | Set up Maven Central (the permission for `com.google.vtfx`, and the publishing configuration, including the version alignment of the nine artifacts with dependency constraints in the Gradle module metadata and a BOM); decide when to go to 1.0. Agents do not publish | [0090](decisions/0090-distribution.md), [0087](decisions/0087-public-api.md), [0093](decisions/0093-releases.md) |
| Changes to protected paths | Review the changes to the paths under `security_review` in `config/protected-paths.toml` since the last review, which covered the tree up to `2fb4b48e82ff3af82874d9d69b896d4d935cacb8` on 2026-10-27. Review the output of `scripts/report_protected_changes.py 2fb4b48e82ff3af82874d9d69b896d4d935cacb8` (the reasons are in the commit messages). Commits that change protected paths do not update this item; after a review, replace the base with the full SHA of the reviewed commit | `config/protected-paths.toml`, section 5 of [architecture.md](design/architecture.md) |
| Spec proposals from differential testing | Decide the expected behavior on the open questions in `spec-proposals/core/differential.vtspec` (whether `IL`/`DL` reset the cursor column, whether `CBT` cancels pending wrap, whether `CSI ? 1049 h`/`l` saves or restores the cursor when already on that screen, and whether a combining mark on a blank cell is dropped) and promote approved cases to `spec/core/`. Humans own `spec/` | `spec-proposals/core/differential.vtspec`, sections 1 and 2 of [testing.md](design/testing.md), [0094](decisions/0094-differential-testing.md), `017ed5b8987cade26f48e5882ae9aceedef37cd7` |
| Upstream bug reports for alacritty_terminal 0.26.0 | Decide whether to report upstream the bugs found in `alacritty_terminal` 0.26.0 (hang on resizing wide characters to 1 column, panic on printing wide characters in 1 column, `ED 1` on row 2 leaving row 1, `EL 0`/`ICH`/`DCH` and `LF`/`IND`/`RI` keeping pending wrap, `HT` wrapping at pending wrap, `DECAWM` off setting pending wrap, `DECALN` keeping margins and cursor, `CUU`/`CUD`/`CNL`/`CPL` ignoring `DECSTBM` margins, `DECSTBM` clamping order, `DECRST 6` not homing when `DECOM` is off, `CBT` after `CSI 3 g` stopping short of column 0, and wide-character split/wrap cell cleanup). Agents do not contact external projects | `tools/alacritty-diff/reasons.toml`, [0094](decisions/0094-differential-testing.md), `017ed5b8987cade26f48e5882ae9aceedef37cd7` |

## 3. Checks on real devices

Checks that need a human: a judgment of how something looks or feels, or hardware that an agent cannot drive. Agents run the other checks of [device-checks.md](device-checks.md) themselves, and add an item here with the evidence for the judgment (screenshots, recordings, measurements), so that the human only judges ([decisions/0092](decisions/0092-device-checks-by-agents.md)). Section 16 of device-checks.md says what each check is expected to leave to a human.

| Item | Section of [device-checks.md](device-checks.md) | Evidence |
|---|---|---|
