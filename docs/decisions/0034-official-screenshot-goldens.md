# Checking the official screenshot goldens

- Status: Approved (2026-10-15, approved by a human in a review of the review queue)
- Date: 2026-10-15
- Related: Requirement 7.2, sections 5 and 6 of [testing.md](../design/testing.md), `OfficialGoldensTest` in `vtfx-compose`, `vtfx.screenshot-test` in `build-logic`

## Background

Section 5 of testing.md keeps screenshot goldens in two stages: provisional goldens in `vtfx-compose/src/test/screenshots/`, which agents update, and official goldens in `spec/screenshots/`, which humans own and promote from the provisional goldens. On 2026-10-15 a human approved promoting the 17 provisional goldens, the first official goldens. Nothing checked `spec/screenshots/` yet: Roborazzi compares the rendering with one directory, the provisional goldens.

## Decision

- Promoting copies a provisional golden to `spec/screenshots/` with the same file name. The file names contain the test class and the test name, so goldens of other modules would not collide.
- `OfficialGoldensTest`, a plain JVM test in `vtfx-compose`, checks that each official golden has a provisional golden with the same pixels (size and ARGB of every pixel, read with `ImageIO`). The screenshot tests compare the rendering with the provisional goldens in the same run, so together they check the rendering against the official goldens.
- An official golden that the provisional golden no longer matches is listed in `spec-expected-failures.txt` of `vtfx-compose` as `screenshots/<file name>`, as section 6 of testing.md does for spec cases. A listed golden must differ, and every listed entry must be an official golden. The agent that changes the rendering adds the entry and an item in review-queue.md; the human who promotes the new provisional golden removes the entry.
- Provisional goldens without an official golden (new tests) are not checked by this test; they wait for promotion.
- `vtfx.screenshot-test` passes the directories to the tests as system properties and declares `spec/screenshots/` as an input, as `vtfx-core` does for `spec/`.

## Alternatives considered

- **Point Roborazzi at `spec/screenshots/`**: The rendering would be compared with the official goldens directly, but recording (`recordRoborazziDebug`) would write into `spec/`, which agents must not change, and there would be no place for goldens of new tests before promotion.
- **Compare the bytes of the files**: Simpler, but a PNG with the same pixels can be encoded differently (for example by another version of the encoder), which would fail without a visible change. Roborazzi itself compares pixels.
- **Report differences without failing**: Keeps agents free to change the rendering, but an official golden that is not a gate does not protect anything. The expected failure list already gives the same freedom, with the difference visible in a reviewed file.

## Impact of reversing

Remove `OfficialGoldensTest`, the system properties and input in `vtfx.screenshot-test`, the screenshot entries of `spec-expected-failures.txt`, and the description in sections 5 and 6 of testing.md. The official goldens can stay as a record.
