# Contributing

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
  https://www.conventionalcommits.org/en/v1.0.0/.
- Keep each commit as small as possible. Keep non-functional changes
  (`refactor`, `style`, `ci`, `chore`) in separate commits from functional
  ones. A `revert` counts as what it reverts.
- `docs` and `test` are only for a feature already on `main` that needs more
  documentation or tests, or for corrections to existing docs. New features
  and fixes carry their own docs and tests in the `feat` or `fix` commit.
  Other changes to repository files such as CLAUDE.md use `chore`; docs that
  accompany a `ci` or `build` change go into that commit.
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
