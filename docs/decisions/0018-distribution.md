# Distribution (provisional) and license

- Status: Approved (2026-10-13, approved by a human in the final review. The group was changed by a human on 2026-10-14); the item on the git history superseded by [0046](0046-history-never-rewritten.md)
- Date: 2026-10-12
- Related: Section 8 of the requirements (distribution and license), "Publishing" in [review-queue.md](../review-queue.md), [0016](0016-public-api.md)

## Background

Section 8 of the requirements asks for the distribution method and the license to be decided. AGENTS.md treats licensing and publishing as things only humans can do. What agents can decide is only the shape assumed in preparation for publishing. A human chose Apache License 2.0 as the license.

## Decision

### Distribution (provisional)

- It is assumed that each of the nine library modules (`vtfx-core`, `vtfx-input`, `vtfx-gate`, `vtfx-gate-android`, `vtfx-session`, `vtfx-compose`, `vtfx-font`, `vtfx-font-gate`, `vtfx-font-gate-android`) is distributed as a Maven artifact. JVM modules are jars, and Android modules are AARs. The group is `com.google.vtfx`, and the artifactId is the module name.
- Apps only need to depend on `vtfx-compose` (the others come transitively; [usage.md](../usage.md)). JVM programs without a UI depend on `vtfx-session`. The modules are distributed separately to keep, in the artifacts too, the structure that mechanically checks the security boundary (only the gate can send to the host) by modules.
- Versions start at 0.x of Semantic Versioning ([0016](0016-public-api.md)). The project is published as the GitHub repository https://github.com/google/vtfx, with the first version 0.1.0 (decided by a human). The version is `version` in `gradle.properties`, and a release is tagged `v<version>`. What remains to be done for publishing is in "Publishing" in [review-queue.md](../review-queue.md).
- The git history after publication: superseded by [0046](0046-history-never-rewritten.md).
- The destination is Maven Central (decided by a human). The group namespace `com.google.vtfx` is under `com.google`, which Google manages on the Central Portal. The permission to publish to it is requested when publishing is decided. Apps can get it without additional repositories or tokens, published versions cannot be changed, and artifacts are signed.
- The group and the package name are `com.google.vtfx`, so that the project can be released as a Google open source project (decided by a human).
- Publishing configuration (`maven-publish`, POM, signing, the Central Portal) is added when a human decides to publish. Uploading to Maven Central was not part of the publication of the repository: it is set up afterwards (decided by a human).
- The library artifacts are kept at one version in apps (decided by a human). The artifacts call each other's `@InternalVtfxApi` declarations, which may change in any version ([0016](0016-public-api.md)), so a Gradle resolution that mixes their versions (for example `vtfx-session` raised by another dependency of the app) can fail with `NoSuchMethodError`. The publishing configuration puts dependency constraints between the artifacts into the published Gradle module metadata, so that Gradle aligns them by itself, and also publishes a BOM (`vtfx-bom`) for Maven.

### License

- **Apache License 2.0** (decided by a human). The full text is in `LICENSE` in the repository.
- The tables generated from the Unicode Character Database by `scripts/generate_unicode_tables.py` are committed in `vtfx-core` and `vtfx-gate` ([0029](0029-import-without-gradle.md)). The UCD files are third-party material, so they are kept in `third_party/unicode` with their license (`LICENSE`) and source (`METADATA`), and no other directory holds third-party material (decided by a human). The Unicode License v3 requires the copyright notice and permission notice to be included in copies or accompanying documentation. Its text is only in `third_party/unicode/LICENSE`, next to the files, and the repository has no `NOTICE` file (decided by a human): Apache License 2.0 does not require a `NOTICE` file, and having one obliges every redistributor to keep it.
- Each source file has an Apache License 2.0 header with `Copyright <year> Google LLC`, added with [addlicense](https://github.com/google/addlicense) (`addlicense -c "Google LLC" -l apache`), as Google's open source release process requires (decided by a human). Files that are not Google's own source code have no header: `spec/` and `tools/ime-probe/traces/` (oracles owned by humans), `third_party/` (material that Google does not own, with its own license), and the Gradle wrapper files. `scripts/check.sh` checks that the headers are present with `addlicense -check`.
- When adding the publishing configuration, include `LICENSE` in each artifact (`META-INF/` of the jar, the AAR), and write the license in the POM. The artifacts that contain tables generated from the UCD (`vtfx-core` and `vtfx-gate`) also include `third_party/unicode/LICENSE` (for example as `META-INF/LICENSE-unicode`) to satisfy the Unicode License v3.
- Licenses of related dependencies:
  - The library's run-time dependencies are AndroidX Compose (foundation, ui) and the Kotlin standard library, both Apache License 2.0.
  - esctest2 (GPL-2.0) is not included in the repository and is fetched at test time (section 11 of [testing.md](../design/testing.md)). It is not included in the distributed artifacts either.
  - `recordings/` contains the output of real apps run on the development machine (the process list of htop is replaced when recording, and a human decided that the host name may stay; [recordings/README.md](../../recordings/README.md)).

## Alternatives considered

- **Keep the group `org.nya3.vtfx`**: The namespace is verified with a personal domain, which does not fit a project released by Google.
- **Make the group `com.google.android.vtfx`**: The project is not part of Android, and `com.google.android` is shared with other teams.
- **Make the group `io.github.nya3jp`**: Verifying the namespace would need only the GitHub account, but it would no longer match the package name of the code (`com.google.vtfx`).
- **GitHub Packages**: Even public packages need a GitHub token to fetch with Maven.
- **JitPack**: Apps need to add a repository, builds happen in JitPack's environment, and there is no signing.
- **Distribute as one AAR**: The app's dependency declaration would be one line, but depending on `vtfx-compose` alone is the same. The module boundaries would disappear from the artifacts.

## Impact of reversing

When adding the publishing configuration, decide again on the group, the artifactIds, and how the modules are divided. There is no impact on the scope of the public API (0016).
