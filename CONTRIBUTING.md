# How to contribute

We'd love to accept your patches and contributions to this project.

## Before you begin

### Sign our Contributor License Agreement

Contributions to this project must be accompanied by a
[Contributor License Agreement](https://cla.developers.google.com/about) (CLA).
You (or your employer) retain the copyright to your contribution; this simply
gives us permission to use and redistribute your contributions as part of the
project.

If you or your current employer have already signed the Google CLA (even if it
was for a different project), you probably don't need to do it again.

Visit <https://cla.developers.google.com/> to see your current agreements or to
sign a new one.

### Review our community guidelines

This project follows
[Google's Open Source Community Guidelines](https://opensource.google/conduct/).

## Contribution process

### Code reviews

All submissions, including submissions by project members, require review. We
use GitHub pull requests for this purpose. Consult
[GitHub Help](https://help.github.com/articles/about-pull-requests/) for more
information on using pull requests.

### Development rules

The development rules of this project are in [AGENTS.md](AGENTS.md). In
particular:

- Run `scripts/check.sh` before sending a pull request and make sure that it
  passes. CI runs the same script. Building requires JDK 21 and the Android
  SDK, and `scripts/check.sh` also requires Go.
- Format Kotlin code with `./gradlew formatKotlin`
  ([docs/decisions/0023](docs/decisions/0023-code-style.md)).
- For a bug fix, add a regression test that fails before the fix.
- Keep one purpose per commit, and use a subject of the form
  `<component>: <imperative summary>` (e.g. `core: Parse CSI parameters`).
- `spec/` and `tools/ime-probe/traces/` are oracles owned by the maintainers.
  Propose new spec tests in `spec-proposals/` instead of changing them.
- Changes to the paths listed under `security_review` in
  [config/protected-paths.toml](config/protected-paths.toml) get a security
  review. Explain why they are needed in the commit message.
