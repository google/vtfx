# Releases: version numbers, checks, changelog, and the API reference

- Status: Provisional
- Date: 2026-10-26
- Related: Requirement 6.5 and section 8 of [REQUIREMENTS.md](../REQUIREMENTS.md), [releasing.md](../releasing.md), sections 1, 7, 8, and 14 of [testing.md](../design/testing.md), [device-checks.md](../device-checks.md), "Publishing" in [review-queue.md](../review-queue.md), [0087](0087-public-api.md), [0090](0090-distribution.md), [0091](0091-security-review-and-process.md), [0092](0092-device-checks-by-agents.md)

## Background

`v0.1.0` was tagged with nothing written about how a release is made. Before vtfx is on Maven Central, a release is a tag in the repository, which apps can build instead of the default branch ([0090](0090-distribution.md)). The area decides how versions after 0.1.0 are numbered, what the default branch calls itself between releases, what must pass before a release, where the changes of a release are written, and how the API reference of a release is made available while GitHub Pages holds that of the default branch (topic 5 of [0090](0090-distribution.md)). Agents do not push or publish (AGENTS.md); a human tags and publishes.

## 1. Version numbers before 1.0

Before 1.0, a release raises the minor version when the public API changed or when apps or their users can notice changes in behavior beyond bug fixes, and the patch version when it holds only bug fixes. When to go to 1.0 stays with humans ([0087](0087-public-api.md)).

**Why.** Semantic Versioning gives 0.x no meaning ([0087](0087-public-api.md) guarantees no compatibility before 1.0), so the rule says what a reader of the version may expect: a patch release can be taken without reading the changelog.

**Alternatives considered.**
- **Raise the minor version on every release**: The version would no longer tell fixes from changes.
- **Raise the minor version only when the API dump changes**: Behavior changes such as a new policy of the gate do not show in the dump, yet apps need to know of them.

## 2. Versions between releases

1. Right after a release, a commit sets `version` in `gradle.properties` to the next minor version with `-SNAPSHOT` (after 0.2.0, `0.3.0-SNAPSHOT`). The release commit sets it to the version being released, which may be a patch version instead.
2. The source links of the API reference of a snapshot point at `main` unless `vtfx.sourceRef` names a commit (`vtfx.dokka`, topic 5 of [0090](0090-distribution.md)), since no tag of a snapshot exists.

**Why.** The reference on GitHub Pages shows the version of the commit it was built from; without a snapshot version, the default branch would call itself the last release while its API had moved past it. `-SNAPSHOT` is what Gradle and Maven call such versions, so the publishing configuration can take it as it is.

**Alternatives considered.**
- **Keep the last release's version until the next release**: The reference of the default branch would show the version of a release it no longer matches.
- **Raise the version only in the release commit, with a suffix such as `-dev` before it**: Not what Maven repositories recognize as a version under development.

**Impact of reversing.** Set `version` to the last release, and drop the case of snapshots in `vtfx.dokka`.

## 3. Checks before a release

A release needs, on the released tree, `scripts/check.sh`, long fuzzing of 30 minutes per fuzz target, and the IME and rendering e2e on the emulator to pass; the checks on real devices are recommended but not required. Section 14 of [testing.md](../design/testing.md) lists them and what passing means, and [releasing.md](../releasing.md) gives the commands. The results go in the `TEST=` line of the release commit, since generated reports are not committed.

**Why.** Long fuzzing and the e2e are kept out of `scripts/check.sh` because they take long and, for the e2e, depend on a real IME (sections 1, 7, and 8 of [testing.md](../design/testing.md)); a release is rare enough to wait for them, and they find what the gates cannot (crashes in the parsers on rare inputs, the drawing path with hardware acceleration, Gboard's actual edits). The checks on real devices depend on which devices, Android releases, and Gboard versions are at hand, so requiring them would let the lab decide whether a release can be made ([0092](0092-device-checks-by-agents.md)).

**Alternatives considered.**
- **`scripts/check.sh` alone**: What CI already runs on every commit; a release would add nothing.
- **Require the checks on real devices too**: A release would wait for devices and for results that are not deterministic.
- **Fuzz for the default 10 minutes per target**: Short for a release, which is the last chance before apps take the code.

**Impact of reversing.** Change section 14 of [testing.md](../design/testing.md) and [releasing.md](../releasing.md). Dropping a required check relaxes a rule of testing.md, which only a human may do.

## 4. The changelog

`CHANGELOG.md` at the root of the repository lists, per release with the newest first, the changes that apps or their users can notice: under `Changed`, `Fixed`, and `Security`, one line per change prefixed with the module. The commit that prepares a release writes its section from the history since the previous release; commits between releases do not touch it. The body of the GitHub release is the same section.

**Why.** Commit subjects are written for the history: most commits since 0.1.0 change only documents, scripts, or tests, and a bug fix says what the code does, not what an app sees. A file in the repository is reviewed like other changes and stays with the code, unlike the text of a GitHub release. Writing it once per release keeps it out of every commit and out of conflicts.

**Alternatives considered.**
- **Only the text of the GitHub release**: Not in the repository, not reviewed, and lost with the hosting.
- **Notes generated from the commits**: As noisy as the history.
- **An entry with every commit that changes behavior**: A rule for every commit and a file that every change edits, for a list that the release commit can write from the history.

**Impact of reversing.** Delete `CHANGELOG.md` and the step of [releasing.md](../releasing.md) that writes it.

## 5. The API reference of a release

The HTML API reference of a release, generated from its tag with `vtfx.sourceRef` set to the tag, is attached by hand to the GitHub release as a zip file. GitHub Pages keeps the reference of the default branch alone (topic 5 of [0090](0090-distribution.md)), and pushing a tag still publishes nothing by itself.

**Why.** Before vtfx is on Maven Central, apps build mostly from the default branch, whose reference Pages already serves; an app that builds a tag can still read the reference of that tag without a site per version. A site per version needs, on every deployment, the references of all releases (each deployment replaces the whole site), which suits the time when apps take released artifacts from Maven Central. Attaching by hand needs no workflow with permission to write to the repository.

**Alternatives considered.**
- **A site per version on Pages (the last release at the root, the default branch and each release under their own paths), with Dokka's versioning plugin**: The time for it is when vtfx is on Maven Central; until then it is machinery for references that few read.
- **A workflow on tags that builds the reference and attaches it**: The job that attaches would need `contents: write`; once releases are frequent, it can follow the split of the Pages workflow (a build job that can only read, and a job that runs no code from the repository).
- **No reference of a release**: Apps that build a tag could only generate it themselves.

**Impact of reversing.** Drop the step of [releasing.md](../releasing.md) that attaches the zip. When vtfx goes to Maven Central, decide again: the publishing configuration also needs a `-javadoc.jar` of each artifact, which Dokka can generate.
