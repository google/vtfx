# Making a release

The steps of a release, for version `X.Y.Z`. Why each step is there is in [decisions/0093](decisions/0093-releases.md). Agents prepare the commits (sections 1 to 3 and 5); only a human tags, pushes, and publishes (section 4, AGENTS.md).

## 1. Before a release

1. No report is open in section 4 of [reviews/README.md](reviews/README.md), or a human has decided that the release does not wait for the open ones.
2. The changes to the protected paths since the last security review have been reviewed: `scripts/report_protected_changes.py <BASE>`, with the base of "Changes to protected paths" in [review-queue.md](review-queue.md), shows nothing that a human has not reviewed.
3. The version follows topic 1 of [decisions/0093](decisions/0093-releases.md): the minor version when the public API (the differences in `api/` since the previous tag) or behavior that apps can notice changed, otherwise the patch version.

## 2. Checks

Run the checks of section 14 of [testing.md](design/testing.md) on the tree to be released, or on a commit that differs from it only in documents and in `version`. The long fuzzing takes about 30 minutes per fuzz target; it can run in a separate worktree (`git worktree add --detach ../vtfx-fuzz <commit>`, with `local.properties` copied) while the e2e runs.

```bash
scripts/check.sh
scripts/fuzz.sh 30m
scripts/emulator.sh start
scripts/e2e_ime.py
scripts/e2e_render.py
scripts/emulator.sh stop
```

Recommended, with a device reached over adb ([device-checks.md](device-checks.md)):

```bash
scripts/device_checks.py --serial SERIAL
scripts/e2e_ime.py --serial SERIAL
```

A failure is fixed in its own commits, following the usual rules, and the checks run again.

## 3. The release commit

1. Write the section of `X.Y.Z` in `CHANGELOG.md` from `git log vPREVIOUS..HEAD`: the changes that apps or their users can notice, under `Changed`, `Fixed`, and `Security`, one line per change prefixed with the module (topic 4 of [decisions/0093](decisions/0093-releases.md)). It may go in a commit of its own before the release commit, under the heading `## X.Y.Z - Unreleased`.
2. In one commit with the subject `build: Release X.Y.Z`, set `version=X.Y.Z` in `gradle.properties` and replace `Unreleased` in the heading with the date (`YYYY-MM-DD`). Its `TEST=` line lists each check of section 2 with its result, and names the commit the checks ran on if it is not the release commit.

## 4. Tag and publish (a human)

1. Tag the release commit with an annotated tag, and push it with the default branch:

   ```bash
   git tag -a vX.Y.Z -m "vtfx X.Y.Z" <release commit>
   git push origin main vX.Y.Z
   ```

2. Generate the API reference of the tag in a clean worktree, check it, and pack it:

   ```bash
   git worktree add --detach ../vtfx-vX.Y.Z vX.Y.Z
   cp local.properties ../vtfx-vX.Y.Z/
   cd ../vtfx-vX.Y.Z
   ./gradlew -Pvtfx.sourceRef=vX.Y.Z :dokkaGenerate
   python3 -I scripts/check_api_reference.py build/dokka/html
   cp -r build/dokka/html build/vtfx-X.Y.Z-api-reference
   (cd build && zip -qr vtfx-X.Y.Z-api-reference.zip vtfx-X.Y.Z-api-reference)
   ```

3. Create a GitHub release from the tag `vX.Y.Z`, titled `vtfx X.Y.Z`, with the section of `X.Y.Z` in `CHANGELOG.md` as its text, and attach `build/vtfx-X.Y.Z-api-reference.zip`.

GitHub Pages keeps serving the reference of the default branch; the push of the default branch updates it (topic 5 of [decisions/0090](decisions/0090-distribution.md)).

## 5. After the release

In a commit with the subject `build: Develop X.(Y+1).0`, set `version` in `gradle.properties` to the next minor version with `-SNAPSHOT` (topic 2 of [decisions/0093](decisions/0093-releases.md)). It can be made before the tag is pushed, since the tag names the release commit.
