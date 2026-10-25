# The git history is never rewritten after publication

- Status: Approved (2026-10-25, approved by a human)
- Date: 2026-10-20
- Related: [AGENTS.md](../../AGENTS.md) ("History", "Situations in which to stop and consult"), [0090](0090-distribution.md), [0088](0088-build-boundaries-and-tests.md), `.git-blame-ignore-revs`

## Background

The repository was published as https://github.com/google/vtfx on 2026-10-15. On that day a human decided that the published history is never rewritten, and that the history before publication is squashed into one commit. The rule was recorded as an item of [0090](0090-distribution.md) (distribution), but it governs how every change is committed, not how the artifacts are distributed, and AGENTS.md cites it for that. This decision moves the item out of 0018 so that it can be cited and changed on its own. It restates what the human decided and adds nothing to it; it is Provisional only because an agent wrote it.

Others depend on the published commits staying as they are: clones and forks of the repository, `.git-blame-ignore-revs` ([0088](0088-build-boundaries-and-tests.md)), and documents that name commits by their SHA (review targets, and the base of a review range in [review-queue.md](../review-queue.md)).

## Decision

1. From publication on, the git history is never rewritten: no amending, rebasing, squashing, or force pushing of existing commits. Changes, including reverts, are added as new commits.
2. The history before publication was squashed into one commit, so the work before 2026-10-15 is not in the history.
3. Because commits are never rewritten, a commit's full SHA is a stable reference. Documents that are about commits name them by full SHA instead of retelling their changes (AGENTS.md, "History").

An agent that finds that the history needs to be rewritten stops and consults a human (AGENTS.md, "Situations in which to stop and consult").

## Alternatives considered

- **Allow rewriting the history to clean it up** (squashing fixups, rewording messages): Every clone and fork would diverge from `main`, and the SHAs in `.git-blame-ignore-revs` and in the documents would point at commits that no longer exist on `main`.
- **Keep the history before publication**: A human chose to publish one squashed commit instead.

## Impact of reversing

Clones and forks would need to be reset to the rewritten `main`. The SHAs in `.git-blame-ignore-revs`, review-queue.md, and the review reports in `docs/reviews/` would need to be found again in the rewritten history, and the "History" bullet of AGENTS.md would change.
