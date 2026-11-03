# spec-proposals

Spec tests that agents propose to humans for addition to [spec/](../spec/README.md), owned by agents (section 2 of [testing.md](../docs/design/testing.md)). They are written in the same format as `spec/`, and the same content runs as agents' tests: `ProposedSpecTest` in `vtfx-core` runs `core/*.vtspec`. A human promotes approved cases to `spec/` and deletes them here.

| Directory | Contents | Format |
|---|---|---|
| `core/` | Proposed spec tests of terminal emulation | Section 3 of [testing.md](../docs/design/testing.md) |

The directory stays even when no proposal is open, because the tests of `vtfx-core` declare it as an input.
