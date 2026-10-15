# Formatting Kotlin code

- Status: Approved (2026-10-15, approved by a human in a review of the review queue)
- Date: 2026-10-14
- Related: `build-logic/src/main/kotlin/vtfx.ktfmt.gradle.kts`, `build-logic/src/main/kotlin/Ktfmt.kt`, section 9 of [testing.md](../design/testing.md)

## Background

There was no mechanism to enforce the code format. `kotlin.code.style=official` in `gradle.properties` only tells the IDE which style to use, and is not checked in the build. Indentation, line length, and wrapping relied on the practice of matching the surrounding code. 515 lines exceeded 100 columns.

A human (the owner of the project) chose to enforce formatting following Google's style, using ktfmt's `--kotlinlang-style` for Kotlin. ktfmt's `--google-style` is Google's internal style (indent 2), and changes almost every line. `--kotlinlang-style` (indent 4, 100 columns) is close to the Android Kotlin style guide Google publishes and to the existing code, and changes fewer lines.

## Decision

- Format Kotlin code with ktfmt 0.64's `--kotlinlang-style`.
- `vtfx.ktfmt` in the root project defines two tasks.
  - `formatKotlin`: formats the sources in place
  - `checkKotlinFormat`: fails, listing them, if any sources are not formatted. `check` runs it, so it is a gate in `scripts/check.sh` and CI
- The targets are the build scripts, `build-logic`, and the Kotlin sources in each module's `src`. `tools/ime-probe`, which is a separate build, and the reproduction tests saved in bug reports (`docs/reviews/`) are excluded.
- Generated sources (`UnicodeWidthTables`, `UnicodeDefaultIgnorables`) are not formatted, because ktfmt breaks tables of value pairs into one value per line. They are committed ([0029](0029-import-without-gradle.md)), so `vtfx.ktfmt` excludes them by path, and their generator checks them instead (`scripts/generate_unicode_tables.py --check` in `scripts/check.sh`).
- ktfmt is called directly with `JavaExec`, without a formatting plugin such as Spotless, so that the dependencies under review are only ktfmt and its dependencies. The plain jar is used rather than a jar with its dependencies bundled, so that each dependency is resolved and visible like other dependencies.
- ktfmt 0.64 sometimes changes expressions that chain calls on the result of a lambda (`map { ... }.let { ... }` and so on) into a different form on a second formatting. `checkKotlinFormat` would keep failing, so such expressions are rewritten into a form that is stable after one pass.
- Formatting-only commits are registered in `.git-blame-ignore-revs`, in a later commit, since a commit cannot contain its own ID. The history before publication (2026-10-15) was squashed into one commit, so the file was empty at publication. The history is never rewritten after publication, so the registered IDs last.

## Alternatives considered

- ktfmt's `--google-style`: Google's internal style (indent 2). 138 of 139 files change, a diff of about 30,000 lines. Farthest from the existing code and the IDE setting (`kotlin.code.style=official`).
- ktlint: Rules can be configured individually, but the formatting result is not as uniquely determined as with ktfmt. More configuration decisions.
- Spotless: Can call ktfmt, but the plugin and its dependencies would be added to what is under review.
- Formatting Python and shell (pyink, pylint, shfmt, shellcheck): There are few targets, and a mechanism to install pinned pip dependencies would be needed, so it was postponed.

## Impact of reversing

To change the style, change `--kotlinlang-style` in `Ktfmt.kt`, reformat everything with `./gradlew formatKotlin`, and add that commit to `.git-blame-ignore-revs`. To stop enforcing formatting, make `check` not depend on `checkKotlinFormat` in `vtfx.ktfmt.gradle.kts`, and revert the descriptions in AGENTS.md and section 9 of testing.md.
