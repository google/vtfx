# Distribution, license, and supported platforms

- Status: Approved
- Date: 2026-10-25
- Related: Requirement 6.1, 6.3, 6.4, 6.5, 7.1, and section 8 of [REQUIREMENTS.md](../REQUIREMENTS.md), sections 2 and 7 of [architecture.md](../design/architecture.md), sections 3 and 6 of [ime-contract.md](../design/ime-contract.md), sections 4 and 6 of [gate-policy.md](../design/gate-policy.md), section 11 of [compose-input.md](../design/compose-input.md), sections 9 and 11 of [testing.md](../design/testing.md), sections 1 and 6 of [usage.md](../usage.md), "Publishing" and "API reference on GitHub Pages" in [review-queue.md](../review-queue.md), [research/minsdk-2026-10.md](../research/minsdk-2026-10.md), [research/ime-gboard-api29.md](../research/ime-gboard-api29.md), [0083](0083-gate.md), [0089](0089-performance.md), [0085](0085-compose-interaction.md), [0087](0087-public-api.md), [0088](0088-build-boundaries-and-tests.md), [0091](0091-security-review-and-process.md)

## Background

Section 8 of the requirements asks for the distribution method and the license to be decided, and requirement 6.4 leaves minSdk to a trade-off per feature. Licensing and publishing are things only humans can do (AGENTS.md); what agents decide is the shape assumed in preparation for publishing. vtfx is published as a GitHub repository but not yet on Maven Central, so apps build it from the repository, mostly from the default branch.

## 1. Maven artifacts, group, and destination

1. Each of the nine library modules (`vtfx-core`, `vtfx-input`, `vtfx-gate`, `vtfx-gate-android`, `vtfx-session`, `vtfx-compose`, `vtfx-font`, `vtfx-font-gate`, `vtfx-font-gate-android`) is to be distributed as a Maven artifact: JVM modules as jars, Android modules as AARs. The artifactId is the module name.
2. The group and the package name are `com.google.vtfx` (section 7 of [architecture.md](../design/architecture.md)), so that the project can be released as a Google open source project. The repository is https://github.com/google/vtfx.
3. Apps need to depend only on `vtfx-compose`, which brings in the others, and JVM programs without a UI on `vtfx-session` (section 2 of [architecture.md](../design/architecture.md), section 1 of [usage.md](../usage.md)).
4. The destination is Maven Central. The namespace `com.google.vtfx` is under `com.google`, which Google manages on the Central Portal; the permission to publish to it is requested when publishing is decided.
5. The publishing configuration (`maven-publish`, the POM, signing, the Central Portal) is added when a human decides to publish to Maven Central.

**Why.** Separate artifacts keep, in what apps receive, the module structure by which the security boundary (only the gate can send to the host) is checked mechanically. Maven Central needs no additional repositories or tokens in apps, published versions cannot be changed, and artifacts are signed.

**Alternatives considered.**
- **The group `org.nya3.vtfx`**: The namespace is verified with a personal domain, which does not fit a project released by Google.
- **The group `com.google.android.vtfx`**: The project is not part of Android, and `com.google.android` is shared with other teams.
- **The group `io.github.nya3jp`**: Verifying the namespace would need only the GitHub account, but the group would no longer match the package name of the code (`com.google.vtfx`).
- **GitHub Packages**: Even public packages need a GitHub token to fetch with Maven.
- **JitPack**: Apps need to add a repository, builds happen in JitPack's environment, and there is no signing.
- **One AAR**: The app's dependency declaration would be one line, but depending on `vtfx-compose` alone is the same, and the module boundaries would disappear from the artifacts.

**Impact of reversing.** When the publishing configuration is added, the group, the artifactIds, and how the modules are divided are decided again. The scope of the public API ([0087](0087-public-api.md)) is not affected.

## 2. Versions and their alignment

1. Versions start at 0.x of Semantic Versioning ([0087](0087-public-api.md)), with the first version 0.1.0. The version is `version` in `gradle.properties`, and a release is tagged `v<version>`.
2. Apps keep the library artifacts at one version. The publishing configuration puts dependency constraints between the artifacts into the published Gradle module metadata, so that Gradle aligns them by itself, and also publishes a BOM (`vtfx-bom`) for Maven.

**Why.** The artifacts call each other's `@InternalVtfxApi` declarations, which may change in any version ([0087](0087-public-api.md)), so a resolution that mixes their versions (for example `vtfx-session` raised by another dependency of the app) can fail with `NoSuchMethodError`.

## 3. License and third-party material

1. The license is Apache License 2.0; the full text is in `LICENSE`.
2. The Unicode Character Database files, from which `scripts/generate_unicode_tables.py` generates the tables committed in `vtfx-core` and `vtfx-gate` ([0088](0088-build-boundaries-and-tests.md)), are third-party material, kept in `third_party/unicode` with their license (`LICENSE`) and source (`METADATA`). No other directory holds third-party material. The text of the Unicode License v3 is only in `third_party/unicode/LICENSE`, and the repository has no `NOTICE` file.
3. Each source file has an Apache License 2.0 header with `Copyright <year> Google LLC`, added with [addlicense](https://github.com/google/addlicense) (`addlicense -c "Google LLC" -l apache`) and checked by `scripts/check.sh` with `addlicense -check` (section 9 of [testing.md](../design/testing.md)). Files that are not Google's own source code have no header: `spec/` and `tools/ime-probe/traces/` (oracles owned by humans), `third_party/` (material Google does not own, with its own license), and the Gradle wrapper files.
4. The publishing configuration includes `LICENSE` in each artifact (`META-INF/` of the jar, the AAR) and writes the license in the POM. `vtfx-core` and `vtfx-gate`, which contain tables generated from the UCD, also include `third_party/unicode/LICENSE` (for example as `META-INF/LICENSE-unicode`).
5. The library's run-time dependencies are AndroidX Compose (foundation, ui) and the Kotlin standard library, both Apache License 2.0. esctest2 (GPL-2.0) is fetched at test time and is in neither the repository nor the artifacts (section 11 of [testing.md](../design/testing.md)). `recordings/` holds the output of real apps run on the development machine: the process list of htop is replaced when recording, and the host name may stay ([recordings/README.md](../../recordings/README.md)).

**Why.** Google's open source release process requires the headers. The Unicode License v3 requires its copyright and permission notices to be included in copies or accompanying documentation, which the license file next to the UCD files, and its copy in each artifact that contains generated tables, satisfy.

**Alternatives considered.**
- **A `NOTICE` file**: Apache License 2.0 does not require one, and having one obliges every redistributor to keep it.

## 4. minSdk 29

minSdk is 29 (Android 10) (section 7 of [architecture.md](../design/architecture.md)). Below 34, the following are accepted:

1. **IME edits on API 29 to 33** go through the paths of the contract other than `replaceText` (section 6.1 of [ime-contract.md](../design/ime-contract.md)). They are to be verified with traces recorded on those versions; until then, they are verified by the unit tests of `ImeEditor`, the Robolectric tests of `TerminalInputConnection` at SDK 29, and, on API 29, by the bytes that Gboard sent ([research/ime-gboard-api29.md](../research/ime-gboard-api29.md)).
2. **The rows are visible to every accessibility service on API 29 to 33**, not only to tools such as TalkBack; on API 34 and later the sensitive mark of [0085](0085-compose-interaction.md) works (section 11 of [compose-input.md](../design/compose-input.md)). Section 6 of [usage.md](../usage.md) says so, so that apps that show secrets can decide, for example by not supporting those versions.
3. **The clipboard on API 29 to 32** has no copy preview, and on API 29 and 30 no notice of reads. Reads stay denied by default, written content stays unmarked (not sensitive), and writes are denied by default as well (sections 4.1 and 4.3 of [gate-policy.md](../design/gate-policy.md)).

The code keeps one path wherever the platform allows it: only the two IME calls newer than 29 are guarded in `TerminalInputConnection` (`setInitialSurroundingText`, API 30, and `getSurroundingText`, API 31; sections 3 and 6.2 of [ime-contract.md](../design/ime-contract.md)). `SurroundingText` is constructed in a nested object annotated `@RequiresApi(31)`, as androidx does, so that the code that the verifier of an older runtime cannot resolve is outside `TerminalInputConnection`.

**Why.** minSdk started at 34 because the IME contract uses `InputConnection.replaceText`, added in API 34. The trade-off per level ([research/minsdk-2026-10.md](../research/minsdk-2026-10.md)):
- 26 is the lowest level that keeps personalized learning disabled (section 3 of [ime-contract.md](../design/ime-contract.md)); below 26, `java.util.Base64` also needs a second path.
- 29 also removes the fallback of the renderer, so the line cache of [0089](0089-performance.md) (`RenderNode`) and the underline metrics need no second path, and keeps clipboard reads foreground-only (section 6 of [gate-policy.md](../design/gate-policy.md)). From 29, only two IME calls need guarding.
- 31 needs no fallback in the code at all.
- 33 keeps the clipboard preview on each copy, by which [0083](0083-gate.md) lets the user notice a rewrite by OSC 52.
- 34 keeps everything. Any level below 34 loses the measured IME behavior (below 34, IMEs do not call `replaceText`, and no IME was measured there) and the sensitive mark (below 34, the libraries treat every request as coming from an accessibility tool).

Nothing in vtfx can tell an accessibility tool from another service below 34; the alternatives, not exposing the rows below 34 or guessing from the list of enabled services, would break TalkBack (requirement 6.3) or shut out other screen readers. Without the copy preview the user cannot notice a rewrite by OSC 52, so writes stay denied by default ([0083](0083-gate.md)).

**Alternatives considered.**
- **Keep 34**: No work and no gap, but devices on Android 10 to 13 cannot use vtfx.
- **26**: Also covers Android 8 and 9, which are a small share of devices. Needs a second drawing path without `RenderNode` (slower, and checked only on the emulator, since Robolectric draws without hardware acceleration) and font-independent underline metrics, and lets the app read the clipboard from the background on API 26 to 28 when the app allows reads.
- **24**: Also needs `Base64` replaced (core library desugaring was ruled out: it would make every app enable desugaring), and personalized learning cannot be disabled on API 24 and 25.
- **31**: No fallback in the code, but devices on Android 10 and 11 cannot use vtfx, and the IME and accessibility gaps below 34 remain anyway.
- **33**: Keeps the clipboard preview, with the same IME and accessibility gaps.
- **`EditorInfoCompat` for the initial surrounding text**: It would make androidx.core a direct dependency of `vtfx-compose` for one call (requirement 6.5).

**Impact of reversing.** Going back to 34: restore `minSdk = 34` in `vtfx.android-library` and `vtfx.android-application` in `build-logic`, remove the version checks in `TerminalInputConnection` and the tests at `MIN_SDK`, and update section 7 of [architecture.md](../design/architecture.md), `README.md`, sections 1 and 6 of [usage.md](../usage.md), and sections 3 and 6 of [ime-contract.md](../design/ime-contract.md). Going lower than 29 needs the fallbacks of the renderer, and a new look at the clipboard and personalized learning.

## 5. The API reference of the default branch on GitHub Pages

1. The workflow `.github/workflows/api-reference.yml` publishes the HTML API reference ([0087](0087-public-api.md)) of the default branch to GitHub Pages of the repository, at https://google.github.io/vtfx/, on every push to it; it can also be run by hand. The site holds that reference only; release tags publish nothing.
2. The source links of a reference point at the tag of the version by default, and at the commit or tag given by the Gradle property `vtfx.sourceRef` when it is set (`vtfx.dokka`). The workflow sets it to the commit it builds.
3. Before deploying, the workflow checks the generated reference with `scripts/check_api_reference.py`.
4. The workflow runs only commands that also run locally (`./gradlew -Pvtfx.sourceRef=<commit> :dokkaGenerate` and the script), so that nothing but the deployment depends on CI (section 7 of [architecture.md](../design/architecture.md)).
5. The job that builds has only `contents: read`; only the job that deploys, which runs no code from the repository, has `pages: write` and `id-token: write`. The site is deployed from the uploaded artifact, with no `gh-pages` branch.
6. Enabling Pages with GitHub Actions as the source is a repository setting that only a human can change. The `github-pages` environment allows deployments from the default branch as it is created.

**Why.** Before vtfx is on Maven Central, apps build it from the default branch, whose API has already moved past `v0.1.0`, so the reference of a release would lag behind what apps use. Setting `vtfx.sourceRef` to the built commit makes the source links match the sources the reference was built from.

**Alternatives considered.**
- **The reference of each release tag**: Matches what a release contains, but before vtfx is on Maven Central, apps use the default branch, and the reference would lag behind it.
- **The last release at the root and the default branch under `/main/`**: Each deployment replaces the whole site, so every run would have to build both, from two trees, for a release that no repository serves yet.
- **Source links to `main`**: The lines move as the branch changes, while the reference stays at the commit it was built from until the next push.
- **A `gh-pages` branch**: The build would need `contents: write` to push to it, and the branch would grow with every push.

**Impact of reversing.** To go back to the reference of each release, change the trigger of the workflow to tags and build from the tag without `vtfx.sourceRef`. To stop publishing, delete the workflow and turn Pages off; `vtfx.sourceRef` can stay, as nothing else sets it.
