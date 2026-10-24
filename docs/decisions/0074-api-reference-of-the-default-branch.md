# The API reference of the default branch on GitHub Pages

- Status: Provisional
- Date: 2026-10-24
- Related: supersedes [0073](0073-api-reference-on-github-pages.md), [0016](0016-public-api.md) (the HTML API reference), [0018](0018-distribution.md) (versions and tags), section 7 of [architecture.md](../design/architecture.md), "API reference on GitHub Pages" in [review-queue.md](../review-queue.md)

## Background

[0073](0073-api-reference-on-github-pages.md) published the reference of each release tag, because its source links pointed at the tag of the version in `gradle.properties`. vtfx is not on Maven Central yet ([0018](0018-distribution.md)), so apps build it from the repository, mostly from the default branch, whose API has already moved past `v0.1.0`. A human chose to publish the reference of the default branch instead (2026-10-24).

## Decision

1. The workflow `.github/workflows/api-reference.yml` publishes the reference of the default branch to GitHub Pages of the repository, at https://google.github.io/vtfx/, on every push to it. It can also be run by hand. The site holds that reference only; release tags publish nothing.
2. The source links of a reference point at the tag of the version by default, and at the commit or tag given by the Gradle property `vtfx.sourceRef` when it is set (`vtfx.dokka`). The workflow sets it to the commit it builds, so that the links match the sources the reference was built from.
3. Before deploying, the workflow checks the generated reference with `scripts/check_api_reference.py`.
4. The workflow runs only commands that also run locally (`./gradlew -Pvtfx.sourceRef=<commit> :dokkaGenerate` and the script), so that nothing but the deployment depends on CI.
5. The job that builds has only `contents: read`; only the job that deploys, which runs no code from the repository, has `pages: write` and `id-token: write`. The site is deployed from the uploaded artifact, with no `gh-pages` branch.
6. Enabling Pages with GitHub Actions as the source is a repository setting that only a human can change. The `github-pages` environment allows deployments from the default branch as it is created.

## Alternatives considered

- **The reference of each release tag ([0073](0073-api-reference-on-github-pages.md))**: Matches what a release contains, but before vtfx is on Maven Central, apps use the default branch, and the reference would lag behind it.
- **The last release at the root and the default branch under `/main/`**: Each deployment replaces the whole site, so every run would have to build both, from two trees, for a release that no repository serves yet.
- **Source links to `main`**: The lines move as the branch changes, while the reference stays at the commit it was built from until the next push.
- **A `gh-pages` branch**: The build would need `contents: write` to push to it, and the branch would grow with every push.

## Impact of reversing

To go back to the reference of each release, change the trigger of the workflow to tags and build from the tag without `vtfx.sourceRef` ([0073](0073-api-reference-on-github-pages.md)). To stop publishing, delete the workflow and turn Pages off; `vtfx.sourceRef` can stay, as nothing else sets it.
