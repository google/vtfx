# vtfx

A terminal emulator library for Android. The UI is Android-only and built on Jetpack Compose; the terminal emulation can also be used from JVM programs without a UI.

This file is the canonical source of the development rules shared by coding agents, and applies to the whole repository.
The scope of work and the instructions given by the user take precedence.

## Documents to read first

- [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md): requirements
- [docs/design/architecture.md](docs/design/architecture.md): module structure, responsibilities, and security boundaries
- [docs/design/ime-contract.md](docs/design/ime-contract.md): the contract for each IME call
- [docs/design/testing.md](docs/design/testing.md): test structure, oracles, and gates
- [docs/design/emulation.md](docs/design/emulation.md): terminal emulation: supported control functions, character widths, attributes, images, and resource limits
- [docs/design/gate-policy.md](docs/design/gate-policy.md): what the gate sends to the host, and its policies for replies, the clipboard, and opening URLs
- [docs/design/session-and-threads.md](docs/design/session-and-threads.md): thread conventions, the terminal session, resize, and lifecycle
- [docs/design/compose-input.md](docs/design/compose-input.md): touch, mouse, selection, links, hardware keys, and accessibility in the Compose UI
- [docs/design/fonts.md](docs/design/fonts.md): where fonts come from, font sanitizing, and the font modules
- [docs/design/boundaries.md](docs/design/boundaries.md): how the build checks the module boundaries in `config/boundaries.toml`
- [docs/decisions/README.md](docs/decisions/README.md): records of design decisions
- [docs/review-queue.md](docs/review-queue.md): items waiting for a human
- [docs/reviews/README.md](docs/reviews/README.md): the procedure of the code review feedback loop, and the list of open bug reports

Write documents, code, comments, and commit messages in English.
This rule applies only to what is written into the repository. Conversations with the user do not have to be in English: reply in the language the user writes in, or the language the user asks for, even though this file and the documents are in English.

## How to work

- Before editing, read the surrounding code and the related design documents, and follow the existing naming and idioms.
- When changing multiple files, or when the approach is not clear, make a plan before editing.
- At the start of work, check `git status --short` and preserve existing uncommitted changes.
- For a bug fix, add a regression test that fails before the fix. Verify the behavior through the public API, preferring the real implementation, then fakes, then mocks.
- When finishing work, report the changes, the verification commands run and their results, and what was not verified.

## Development process

- **How to start work**: Before starting, read review-queue.md and the open reports in the [list of bug reports](docs/reviews/README.md). Fix bugs and update reports according to section 3 of [docs/reviews/README.md](docs/reviews/README.md).
- **Code review**: When asked for a review, follow section 2 of [docs/reviews/README.md](docs/reviews/README.md) and write a report with reproduction tests. Do not change product code.
- **Decisions**: Decide design choices not written in the documents provisionally by yourself. Record decisions in [docs/decisions/](docs/decisions/README.md) and refer to them from the related commits. Do not stop work to defer a decision. A decision records why; it is not appended to: when it changes, write a new decision and mark the old one superseded ([docs/decisions/0045](docs/decisions/0045-decisions-and-design-documents.md)).
- **Things only humans can do**: When you run into licensing, publishing, checks on real devices, approval of goldens or oracles, and so on, record them in [docs/review-queue.md](docs/review-queue.md) and continue with other work.
- **Updating documents**: The design documents (`docs/design/`) are the only description of the current design; update them to match the implementation, and do not describe the design again in decisions. However, do not relax the gate rules in testing.md: only a human may. Keep the section numbers of the documents, and add new sections at the end: code and documents cite sections by number ("section 5 of testing.md"), and `scripts/check_doc_links.py` checks only paths, not sections.
- **History**: The repository was published on 2026-10-15, and from then on the git history is never rewritten. Do not amend, rebase, squash, or force push existing commits; add changes, including reverts, as new commits ([docs/decisions/0046](docs/decisions/0046-history-never-rewritten.md)). The history before publication was squashed into one commit, so earlier work is not in it. Commits since then are stable: where commits are the subject (review targets, the base of a review range such as that of the security review in review-queue.md, `.git-blame-ignore-revs`), write full SHAs and point to the commits instead of retelling their changes. Explain design rationale in place, in documents and comments, rather than pointing to the commit that introduced it. Do not commit generated reports either; the scripts write them to `build/reports/`.
- **Situations in which to stop and consult**: In the following cases, stop work and consult a human.
  - The restrictions in `config/boundaries.toml` need to be relaxed
  - sudo or changes to the system configuration are needed
  - The git history needs to be rewritten (force push, changing published history by rebase, and so on)
  - The requirements themselves (REQUIREMENTS.md) need to change

## Verification

- Building requires JDK 21 and the Android SDK. `scripts/check.sh` also requires Go, to run addlicense. The JDK is selected by `gradle/gradle-daemon-jvm.properties`, and the Android SDK is configured by the convention plugins in `build-logic/`. Use the repository's `./gradlew` for Gradle.
- Before committing, run `scripts/check.sh` and confirm that everything passes. CI runs the same script.
- `scripts/check.sh` runs the KDoc check of the public API, the check that references to documents point at existing files, the license header check, the script tests, the check that the generated character width tables are up to date, and the Gradle build (Kotlin formatting check, the module boundary check on the dependencies and the compiled classes, unit, spec, and screenshot tests, lint, public API compatibility check, and short fuzzing). See [docs/design/testing.md](docs/design/testing.md) for details.
- Format Kotlin code in ktfmt's Kotlin coding conventions style. Format with `./gradlew formatKotlin` and review the diff before committing ([docs/decisions/0023](docs/decisions/0023-code-style.md)).
- Long fuzzing and the emulator e2e are not part of check.sh. Operate the emulator with `scripts/emulator.sh setup|start|stop`.
- A tool for investigating IME behavior is in `tools/ime-probe/`. Its usage and past findings are in `docs/research/ime-*.md`. This development machine also has the AVD `vtfx-probe` with Gboard's Japanese (QWERTY) and Korean already set up.
- Write the commands run and their results accurately in the `TEST=` line of the commit message. Do not write anything that was not run.

## Things that must not be changed

- `spec/` and `tools/ime-probe/traces/` are oracles owned by humans. Do not change, delete, or rename them.
  - To add a spec test, write it in `spec-proposals/` and propose it to a human. Also run the same content as your own test.
  - Manage screenshot goldens as provisional goldens in `vtfx-compose/src/test/screenshots/` (section 5 of testing.md).
  - List spec tests that do not pass yet in the module's `src/test/resources/spec-expected-failures.txt` (section 6 of testing.md).
- Do not push, create PRs, or upload to remotes.

## Things to handle with care

The paths listed under `security_review` in `config/protected-paths.toml` are subject to human security review. You may change them, but observe the following.

- Keep changes to the minimum needed, and explain why they are needed in the commit message.
- Do not relax the restrictions in `config/boundaries.toml`. If you think they need to be relaxed, stop work and consult a human.
- When adding or updating external dependencies (libraries, Gradle plugins, GitHub Actions), write the reason in the commit message. Pin dependency versions (pin Actions by commit SHA). Update the checksums in `gradle/verification-metadata.xml` in the same change, with the command in [docs/decisions/0035](docs/decisions/0035-dependency-verification.md).

## Modules

The modules, their kinds and responsibilities, and which of them are subject to security review (those named with `gate`) are listed in section 2 of [docs/design/architecture.md](docs/design/architecture.md).

- The direction of dependencies follows `config/boundaries.toml`. The `checkBoundaries` task of the root project, part of `./gradlew build`, checks it ([docs/decisions/0044](docs/decisions/0044-boundaries-in-gradle-and-classes.md)).
- When adding a new module, add it to both `settings.gradle.kts` and `config/boundaries.toml`.
- The shared settings of each module are in the convention plugins in `build-logic/`. Do not duplicate individual settings in module build files.

## Commits

- One purpose per commit. Do not mix refactoring with behavior changes.
- The subject is `<component>: <imperative summary>` (e.g. `core: Parse CSI parameters`). Explain "why" in the body.
- `scripts/report_protected_changes.py <BASE>` shows changes to protected paths.
