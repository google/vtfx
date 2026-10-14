# Distribution (provisional) and license

- Status: Approved (2026-10-13, approved by a human in the final review. Published to Maven Central. The group was changed by a human on 2026-10-14)
- Date: 2026-10-12
- Related: Section 8 of the requirements (distribution and license), "Publishing" in [review-queue.md](../review-queue.md), [0016](0016-public-api.md)

## Background

Section 8 of the requirements asks for the distribution method and the license to be decided. AGENTS.md treats licensing and publishing as things only humans can do. What agents can decide is only the shape assumed in preparation for publishing. A human chose Apache License 2.0 as the license.

## Decision

### Distribution (provisional)

- It is assumed that the six library modules (`vtfx-core`, `vtfx-input`, `vtfx-gate`, `vtfx-gate-android`, `vtfx-session`, `vtfx-compose`) are each distributed as a Maven artifact. `vtfx-session` was added on 2026-10-14 by [0026](0026-session-module.md). JVM modules are jars, and Android modules are AARs. The group is `com.google.vtfx`, and the artifactId is the module name.
- Apps only need to depend on `vtfx-compose` (the others come transitively; [usage.md](../usage.md)). JVM programs without a UI depend on `vtfx-session`. The modules are distributed separately to keep, in the artifacts too, the structure that mechanically checks the security boundary (only the gate can send to the host) by modules.
- Versions start at 0.x of Semantic Versioning ([0016](0016-public-api.md)).
- The project is published as the GitHub repository https://github.com/google/vtfx during October 2026, with the first version 0.1.0 (2026-10-15, decided by a human). The version is `version` in `gradle.properties`, and the release is tagged `v<version>`.
- The destination is Maven Central (2026-10-13, decided by a human). The group namespace `com.google.vtfx` is under `com.google`, which Google manages on the Central Portal. The permission to publish to it is requested when publishing is decided. Apps can get it without additional repositories or tokens, published versions cannot be changed, and artifacts are signed.
- The group and package name were initially `jp.nya3.vtfx` and were changed to `org.nya3.vtfx` (2026-10-13, decided by a human), then to `com.google.vtfx` so that the project can be released as a Google open source project (2026-10-14, decided by a human).
- Publishing configuration (`maven-publish`, POM, signing, the Central Portal) is added when a human decides to publish. Uploading to Maven Central is not part of the first publication: it is set up after the repository is published (2026-10-15, decided by a human).

### License

- **Apache License 2.0** (2026-10-12, decided by a human). The full text is in `LICENSE` in the repository.
- The character width table in `vtfx-core` (`UnicodeWidthTables`) is generated from the Unicode Character Database by `scripts/generate_unicode_tables.py` and committed ([0029](0029-import-without-gradle.md)). The UCD files are third-party material, so they are kept in `third_party/unicode` with their license (`LICENSE`) and source (`METADATA`), and no other directory holds third-party material (2026-10-14, decided by a human). The Unicode License v3 requires the copyright notice and permission notice to be included in copies or accompanying documentation. Its text is only in `third_party/unicode/LICENSE`, next to the files, and the repository has no `NOTICE` file (2026-10-14, decided by a human). This replaces the earlier arrangement that copied the text into a root `NOTICE`: Apache License 2.0 does not require a `NOTICE` file, and having one obliges every redistributor to keep it.
- Each source file has an Apache License 2.0 header with `Copyright <year> Google LLC`, added with [addlicense](https://github.com/google/addlicense) (`addlicense -c "Google LLC" -l apache`), as Google's open source release process requires (2026-10-14, decided by a human; this replaces the decision of 2026-10-12 to have no headers). Files that are not Google's own source code have no header: `spec/` and `tools/ime-probe/traces/` (oracles owned by humans), `third_party/` (material that Google does not own, with its own license), and the Gradle wrapper files. `scripts/check.sh` checks that the headers are present with `addlicense -check` (2026-10-14, decided by a human).
- When adding the publishing configuration, include `LICENSE` in each artifact (`META-INF/` of the jar, the AAR), and write the license in the POM. The `vtfx-core` artifact, which contains the generated width tables, also includes `third_party/unicode/LICENSE` (for example as `META-INF/LICENSE-unicode`) to satisfy the Unicode License v3.
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
