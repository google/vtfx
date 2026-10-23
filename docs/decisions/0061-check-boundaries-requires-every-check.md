# checkBoundaries requires every check to have run

- Status: Approved (2026-10-23, approved by a human in a review of the review queue)
- Date: 2026-10-22
- Related: Requirement 6.1, [0044](0044-boundaries-in-gradle-and-classes.md), sections 1 and 7 of [boundaries.md](../design/boundaries.md)

## Background

`checkBoundaries` of the root project depended on the checks of every module (`checkDependencies`, `checkClassReferences*`, `checkLibraryArtifact`) and did nothing else. A check that was disabled (`enabled = false`), skipped (`onlyIf`), stopped before its action, or left out of the task graph (`-x`) let `checkBoundaries` and the build pass, so a one-line change to a build file, which review can miss, took a module out of the check that section 1 of [boundaries.md](../design/boundaries.md) says a module cannot leave. The build files themselves are under security review (section 7), and the review that relies on that is easier when turning a check off fails the build instead of only showing in a diff.

## Decision

1. **Every check reports when it passes, and `checkBoundaries` fails on a check that did not.** The checks report their task paths to a build service of the build (`BoundaryChecks`), which lives for one build only; `checkBoundaries` knows the paths of all the checks that the plugin registered and fails, naming them, on those that did not report (section 1 of [boundaries.md](../design/boundaries.md)). The checks have no outputs, so Gradle runs them in every build that asks for them and never takes them as up to date or from a cache.
2. **The report is not a file.** An output file left from an earlier build stays when a task is later disabled, so it could not show that the check ran in this build.

## Alternatives considered

- **Inspect the task graph when it is ready** (`enabled`, the tasks in the graph): `onlyIf` conditions and actions removed or stopped are not visible from there, and the inspection depends on Gradle's internal API.
- **Output files that `checkBoundaries` reads**: stale files pass, and making them fresh would need the checks to always run anyway, which they already do without outputs.
- **Reject Groovy build scripts and `buildSrc` in the build**: a Groovy root script would not apply the plugin that checks, so the check cannot catch its own removal; `config/protected-paths.toml` lists those files for review instead.

## Impact of reversing

- Remove `BoundaryChecks`, `BoundaryCheckTask`, and `requiredChecks` from `BoundaryTasks.kt` and `vtfx.boundaries.gradle.kts`, and `CheckBoundariesTest.kt`; make the checks plain tasks again, and update section 1 of [boundaries.md](../design/boundaries.md).
