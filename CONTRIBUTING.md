# Contributing

Churn's design and the reasons behind it are in
[docs/design.md](docs/design.md); the technical detail for writing its code
is in [docs/development.md](docs/development.md).

## Pull requests

- Every change reaches `main` through a pull request. Nobody pushes to
  `main`.
- Keep GitHub text that accompanies code (pull request and issue
  descriptions, comments) to a short summary. Don't repeat what the code or
  commit messages already say; the reasoning for a change belongs in its
  commit message.
- `main` requires a branch to be up to date with it before merging, so a
  pull request behind `main` must first be rebased or updated.
- Every pull request needs one approval. Changes to security-critical code
  (the swap procedure, shell and AT commands, key and host-key handling)
  also need the maintainer's own review.
- Only the maintainer merges, always with a merge commit.
- Claude, an AI assistant, contributes under its own GitHub account and
  gives the required approval after checking the diff. Its review of code it
  wrote itself isn't independent, which is why security-critical changes
  need the maintainer. Its additional rules are in [CLAUDE.md](CLAUDE.md).
- The ruleset on `main` keeps `require_last_push_approval` off. Otherwise
  Claude's approval wouldn't count on pull requests it pushed to, and the
  maintainer couldn't approve their own.

## Commits

- Use conventional commit messages (`type: description`) with `feat`,
  `fix`, `perf`, `refactor`, `style`, `test`, `docs`, `build`, `ci`,
  `chore` or `revert` as type, based on
  <https://www.conventionalcommits.org/en/v1.0.0/>.
- Keep each commit as small as possible. Keep non-functional changes
  (`refactor`, `style`, `ci`, `chore`) in separate commits from functional
  ones. A `revert` counts as what it reverts.
- `docs` and `test` are only for a feature already on `main` that needs more
  documentation or tests, or for corrections to existing docs. `docs` also
  covers documents not tied to a single feature, such as the design. New
  features and fixes carry their own docs and tests in the `feat` or `fix`
  commit. Other changes to repository files such as CLAUDE.md use `chore`;
  docs that accompany a `ci` or `build` change go into that commit.
- A follow-up fix to a commit in the same unmerged pull request is folded
  into that commit, unless it is a purely stylistic change.
- Never rewrite history that is already on `main`.

## Code and configuration

- Keep code and configuration as short as possible; don't spell out
  settings that only restate a default.
- Pin GitHub Actions to full commit SHAs with the version as a comment.
- Signing secrets belong in the `release` environment, never in repository
  secrets.
- Dependabot waits 7 days before proposing a new release (`cooldown`),
  because poisoned releases are usually withdrawn within days.

## Building

The Android project lives in `android/`; open that folder in Android Studio.
Locally, with JDK 17 and the Android SDK installed:

```sh
cd android
./gradlew bundleRelease
```

CI builds a release bundle for every pull request and every push to `main`;
download it from the workflow run's artifacts (`churn-release-aab`).

## Signing

Release bundles are signed with a Google Play upload key supplied through
the environment variables `CHURN_KEYSTORE_PATH`, `CHURN_KEYSTORE_PASSWORD`,
`CHURN_KEY_ALIAS` and `CHURN_KEY_PASSWORD`. Without them the bundle is
unsigned. Key material is never committed.

For CI, a GitHub Actions environment named `release` with deployment
branches restricted to `main` must exist. In it these secrets must be set
(not as repository secrets, which every branch can read):

| Secret                      | Contents                                   |
|-----------------------------|--------------------------------------------|
| `CHURN_UPLOAD_KEYSTORE_B64` | The PKCS12 upload keystore, base64-encoded |
| `CHURN_UPLOAD_PASSWORD`     | Keystore & key password                    |

Builds of pull requests and other branches are always unsigned.

## History

On 2026-10-01 the repository was recreated and its history rewritten. Early
merge commits made on GitHub exposed the maintainer's real email address, and
many commits were unsigned, so GitHub's vigilant mode would have flagged them
as unverified. The rewrite removed the address, re-signed every commit and
dropped the merge commits and pull request references. Pull requests and
issues from before that date are gone.
