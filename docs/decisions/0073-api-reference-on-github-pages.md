# The API reference on GitHub Pages

- Status: Provisional
- Date: 2026-10-24
- Related: [0016](0016-public-api.md) (the HTML API reference), [0018](0018-distribution.md) (versions and tags), [0022](0022-api-reference-content.md), section 7 of [architecture.md](../design/architecture.md), "API reference on GitHub Pages" in [review-queue.md](../review-queue.md)

## Background

`./gradlew :dokkaGenerate` writes the HTML API reference of the library modules to `build/dokka/html`, for publishing ([0016](0016-public-api.md)), but it was published nowhere: developers of apps had to build it themselves. The source links of the reference point at the tag of the version in `gradle.properties` (`v<version>`, `vtfx.dokka`), so a reference matches the sources it links to only when it is built from that tag.

## Decision

1. The workflow `.github/workflows/api-reference.yml` publishes the reference to GitHub Pages of the repository, at https://google.github.io/vtfx/.
2. It publishes on the push of a tag `v*`, built from the tag, and fails when the tag is not `v` followed by the version in `gradle.properties`. It can also be run by hand from the default branch with a tag, to publish the reference of a tag again or of a tag pushed before the workflow existed. The site holds the reference of the last published release only.
3. Before deploying, it checks the generated reference with `scripts/check_api_reference.py`.
4. It runs the same commands as a local build (`./gradlew :dokkaGenerate` and the script), so that nothing but the deployment depends on CI. It calls them directly rather than through a script of its own, because it runs them in the tree of the tag, which may predate such a script.
5. The job that builds has only `contents: read`; only the job that deploys, which runs no code from the repository, has `pages: write` and `id-token: write`. The site is deployed from the uploaded artifact, with no `gh-pages` branch.
6. Enabling Pages with GitHub Actions as the source, and allowing the `github-pages` environment to deploy from the tags `v*`, are repository settings that only a human can change.

## Alternatives considered

- **Publish from `main` on every push**: The reference would show API that no release has, while its source links point at the tag of the last release, whose lines differ.
- **A `gh-pages` branch**: The build would need `contents: write` to push to it, and the branch would grow with every release.
- **Keep the reference of every version (Dokka's versioning plugin)**: Another dependency and the previous references to keep somewhere, before there is a second release and while the API may change before 1.0 ([0016](0016-public-api.md)).
- **javadoc.io**: Serves the javadoc jars of artifacts on Maven Central, where vtfx is not published yet ([0018](0018-distribution.md)).

## Impact of reversing

Delete the workflow and turn Pages off in the repository settings. Nothing else depends on it.
