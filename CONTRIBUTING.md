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
- In Kotlin, name `Boolean` arguments, number and `null` literals, and the
  arguments of a call that takes several of the same primitive type, unless
  the code already says what each value is, as in `setAirplaneMode(true)`.
  Pull requests are reviewed on GitHub, whose diffs show no parameter hints,
  and swapped arguments of the same type still compile.
- Pin GitHub Actions to full commit SHAs with the version as a comment.
- Signing secrets belong in the `release` environment, never in repository
  secrets.
- Dependabot waits 7 days before proposing a new release (`cooldown`),
  because poisoned releases are usually withdrawn within days.

## Building

The Android project lives in `android/`; open that folder in Android Studio.
Locally, with JDK 17 and the Android SDK installed, run the unit tests and
build a release bundle and APK:

```sh
cd android
./gradlew test bundleRelease assembleRelease
```

CI does the same for every pull request and every push to `main`; download
the bundle and the APK from the workflow run's artifacts
(`churn-release-aab-unsigned` and `churn-release-apk-unsigned`, and for
`main` the signed `churn-release-aab` and `churn-release-apk`, see
"Signing"). It also checks the spelling of the Markdown files, the app's
source and F-Droid's store listing in `fastlane/` with cspell (word list in
`cspell.json`), lints the Markdown files with markdownlint-cli2
(`.markdownlint.json`) and lints `restore.sh` and the test script below with
ShellCheck (`-S warning`). After the build,
`RouterScriptsTest` has written the scripts in `Router`'s companion to
`android/app/build/router-scripts/`, as the app sends them. CI lints the
shell scripts there with ShellCheck too and checks the firewall rules
with `nft -c`, so a script added to the companion goes into the test as
well.

CI's Router scripts job then runs `restore.sh` and the free-space
overwrite as the router does: with BusyBox's shell, in a chroot with the
router's paths, stand-ins for `uci`, `gl_modem`, `nft` and GL's log
service, and a 1 GiB ext4 disk image as `/overlay`. From outside, it
checks what they did to the files, the settings and the image: among
other things, that the overwrite leaves no old version of a file in the
free space, 512 MB free and ext4's reserve as it was, also when a signal
stops it or the partition is full, and that `restore.sh` undoes Churn's
changes and runs to its end. On Linux, with BusyBox installed
(`busybox-static` on Debian and Ubuntu), run it after the build as root,
from `android/`:

```sh
sudo sh app/src/test/sh/router-scripts-test.sh app/build/router-scripts
```

CI's Reproducible APK job builds the release APK again, on another runner,
from a checkout in another folder and without Gradle's caches, and fails
unless it matches the first build byte for byte. F-Droid ships our signed
APK only if its own build of the same commit matches ours too (design.md,
section 9). So the F-Droid build job builds the APK with F-Droid's tools,
in F-Droid's build server image, as F-Droid's CI builds apps for merge
requests to its repository, and fails unless that build matches ours. It
takes F-Droid's latest image and tools, so it can also fail after a change
on F-Droid's side. On `main`, the next job copies our signature onto
F-Droid's build and verifies it with F-Droid's own code, as F-Droid does
before it ships our APK.

The unit tests run against a simulated Mudi 7. Tests on a real router are
listed in [docs/router-tests.md](docs/router-tests.md); the app runs the
first of them, its read-only checks, whenever it connects, and debug builds
have a dry run of steps 1 and 3, a removal test, which runs `restore.sh`, a
power cycle test, which shuts the router down twice, and a leak watch, which
goes online at step 4 of a swap and counts what leaves over the SIM.
[docs/release-tests.md](docs/release-tests.md) puts the tests that each
release needs in order.

For the release tests' coverage pass, the coverage build is a debug build
with JaCoCo's counters, which count the code that runs:

```sh
./gradlew assembleDebug -Pcoverage
```

Its menu has Share coverage, which adds the counts so far to a file among
the app's private files and shares that file. The counts live in the
app's process, which Android can end, so the app also adds them to the
file whenever it goes to the background and when a step that ran in the
background ends. Like the debug log, the file is plain text, so that the
apps that take the debug log take it too, with a line of Base64 each time,
and it leaves out the time of day: the app drops the session info that
JaCoCo adds, which holds when the counts began and when they were taken.
[docs/release-tests.md](docs/release-tests.md#coverage-pass) says how to
turn the files that a run shared into reports. CI's Coverage build job
builds the coverage build, checks that its APK has JaCoCo's counters, and
makes a report of the unit tests, which count too with `-Pcoverage`.

## Signing

Release bundles and APKs are signed with one key, which the variables and
secrets call the upload key, after Google Play's name for it. It signs the
APKs on GitHub releases too, so it is the app's lasting identity: Android
installs an update only if it is signed with the same key. Locally,
`bundleRelease` and `assembleRelease` sign with the key that the
environment variables `CHURN_KEYSTORE_PATH`, `CHURN_KEYSTORE_PASSWORD`,
`CHURN_KEY_ALIAS` and `CHURN_KEY_PASSWORD` supply. Without them, the bundle
and APK are unsigned. Key material is never committed.

CI builds and tests without the key. For `main`, the Sign release bundle
and APK job then signs the bundle with the JDK's `jarsigner` and the APK
with `apksigner` from the runner's Android SDK, and prints our certificate's
SHA-256, which F-Droid's metadata for Churn lists. No Gradle and no code
from the repository run in that job, so no build plugin, dependency or
test can read the key. It needs a GitHub Actions environment named
`release` with deployment branches restricted to `main`. In it these
secrets must be set (not as repository secrets, which every branch can
read):

| Secret                      | Contents                                   |
|-----------------------------|--------------------------------------------|
| `CHURN_UPLOAD_KEYSTORE_B64` | The PKCS12 upload keystore, base64-encoded |
| `CHURN_UPLOAD_PASSWORD`     | Keystore & key password                    |

Builds of pull requests and other branches are always unsigned.

Any job of a workflow run can replace the run's artifacts, but not another
job's outputs. So the Release bundle job passes the SHA-256 of the bundle
and APK to the sign job as outputs, and the sign job signs them only if they
match. In the same way, the Release workflow drafts a release only with the
APK that the sign job signed.

## Releases

1. In a pull request, raise `versionCode` and `versionName` in
   `android/app/build.gradle.kts`, and add F-Droid's changelog for the new
   version code as
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. If a
   screen that F-Droid's screenshots show has changed, take them again in
   that pull request, as described below.
2. Once it has merged, start the Release workflow on `main` from the
   repository's Actions tab. It runs the Build workflow, whose sign job
   signs the APK, and once all of its jobs have passed, F-Droid's build
   among them, drafts a GitHub release `v<versionName>` with that APK as
   `churn-<versionName>.apk`. For anyone but the repository's owner, the
   workflow skips its jobs.
3. Run the release tests in [docs/release-tests.md](docs/release-tests.md)
   with that APK.
4. Publish the draft, which creates the tag. F-Droid builds each version
   from its tag, once Churn is in F-Droid's repository.

F-Droid's listing shows the screenshots in
`fastlane/metadata/android/en-US/images/phoneScreenshots/`, named `1.png`,
`2.png` and so on, and takes them from the tag of the latest release.
Release builds keep their screens out of screenshots (design.md, section 7),
so we take them with a debug build of the pull request, on a test phone with
nothing private on it, and swap in a test card for the screens of a swap.
The screens show the router's real IMEIs and the last digits of the cards'
ICCIDs, so we cover each of these numbers with a bar in the text's color, a
little larger than the digits, which makes it plain that something is
hidden. Android may write the time and the phone's system into a
screenshot's metadata, and image editors keep it and may add their own, so
we then remove all of it:

```sh
cd fastlane/metadata/android/en-US/images/phoneScreenshots
exiftool -all= -overwrite_original *.png
```

`exiftool` then lists no EXIF, XMP or PNG text fields for the files. History
on `main` is never rewritten, so we look at each file once more before the
commit: a number left in a screenshot would stay public.

## History

On 2026-10-01 the repository was recreated and its history rewritten. Early
merge commits made on GitHub exposed the maintainer's real email address, and
many commits were unsigned, so GitHub's vigilant mode would have flagged them
as unverified. The rewrite removed the address, re-signed every commit and
dropped the merge commits and pull request references. Pull requests and
issues from before that date are gone.
