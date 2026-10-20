# Dependency verification

- Status: Approved (2026-10-15, decided by a human in a review of the review queue)
- Date: 2026-10-15
- Related: Requirement 6.1, section 5 of [architecture.md](../design/architecture.md), "Things to handle with care" in [AGENTS.md](../../AGENTS.md), `gradle/verification-metadata.xml`, `config/protected-paths.toml`

## Background

A security review (2026-10-15) found that the artifacts the build downloads are not checked. Dependencies are pinned by version, the Gradle distribution by `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`, and GitHub Actions by commit SHA, but an artifact that changed on a repository (Maven Central, Google Maven, the Gradle Plugin Portal), or on the way from it, would be used without notice. Requirement 6.1 puts dependencies in the review scope but does not ask for verification.

The artifacts in question are the tools and libraries that the published vtfx artifacts are built from: AGP, the Kotlin compiler and Gradle plugin, the Compose compiler plugin, Dokka, ktfmt, the AndroidX and Compose libraries, and the test dependencies (JUnit, Robolectric, Roborazzi, Jazzer, ...). A human decided to add Gradle dependency verification with SHA-256 checksums.

## Decision

- `gradle/verification-metadata.xml` lists the SHA-256 checksum of every artifact and metadata file (POM, Gradle module) that the build resolves. `verify-metadata` is on and `verify-signatures` is off. Gradle fails the build when an artifact is missing from the file or its checksum differs.
- The file covers the build of this repository, including the included build `build-logic` and its plugins: Gradle applies the root build's file to the whole build. It was generated from the tasks of `scripts/check.sh` (`build`), the API reference (`:dokkaGenerate`), and the instrumented test APKs (`assembleAndroidTest`), so that the check, publishing, and the emulator tests resolve only listed artifacts.
- It applies only to builds of this repository. Apps that depend on the published artifacts, and composite builds that include vtfx as an included build, use their own verification settings (or none).
- The separate build in `tools/ime-probe` is not covered. Gradle looks for the file in that build's own `gradle/` directory, which does not exist. The probe is a research tool: it is not part of the published artifacts, and neither the check nor CI builds it.
- Sources and javadoc jars (`*-sources.jar`, `*-javadoc.jar`) are trusted without a checksum. Only IDEs download them, to show sources and documentation, and no build classpath contains them; listing them would make every IDE sync depend on which IDE features were used when the file was generated. There are no other trusted artifacts.
- Only checksums for Linux are listed where an artifact differs by platform (for example `aapt2`), since the check and CI run on Linux. A build on another platform fails verification until the entries for that platform are added with the update command below.
- The file has the license header of [0018](0018-distribution.md) as an XML comment, since `scripts/check.sh` checks every tracked file. Gradle keeps the comment when it updates the file.
- When a dependency is added or updated, the metadata is updated in the same change with:

  ```sh
  GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --no-configuration-cache \
      --write-verification-metadata sha256 build :dokkaGenerate assembleAndroidTest
  ```

  The empty Gradle user home makes Gradle download every file again: with a warm cache, Gradle reuses the metadata it already parsed and leaves out parent POMs and BOMs, and a build with an empty cache, such as CI, then fails verification. The configuration cache is turned off so that every configuration, including the plugins, is resolved again. Gradle adds the entries for new artifacts and keeps the existing ones, the configuration (including the trusted artifacts above), and the license header; to drop the entries of artifacts no longer used, empty the `<components>` element first and run the command again. A task outside these (for example the emulator tests) that fails verification gets its entries the same way, with that task added to the command. Review the diff: each new checksum is trusted from the moment it is written. The file is under `gradle/`, which `config/protected-paths.toml` lists for security review.

## Alternatives considered

- **PGP signatures as well** (`--write-verification-metadata pgp,sha256`): Signatures tie an artifact to its publisher's key, so an update of a dependency could be checked without trusting the first download. But not every artifact is signed, so some would still need checksums or trust rules, the keys need a key ring checked into the repository or fetched from key servers, and keys themselves need judging whom to trust. Checksums already detect a changed artifact, which is what the review found missing. Signatures can be added later without removing the checksums.
- **Add verification at publication**: The published artifacts are the ones the verification protects, so it could start with publishing to Maven Central. But the artifacts built and tested before then are built from the same tools, and the cost per dependency change is the same before and after publication.
- **Not at all**: No file to update with each dependency change, but a changed artifact on a repository would go unnoticed into the build and the published artifacts. Pinning versions does not detect that, because a repository can serve different contents under the same version.

## Impact of reversing

Delete `gradle/verification-metadata.xml`, the sentence about updating it in AGENTS.md, and its row in section 5 of architecture.md. Nothing else depends on it.
