# Churn

Placeholder for the Churn Android app (application ID `id.churn`).
There is no functionality yet.

## Building

CI builds a release bundle on every push to `main`; download it from the
workflow run's artifacts (`churn-release-aab`).

The Android project lives in `android/`; open that folder in Android Studio.
Locally, with JDK 17 and the Android SDK installed:

```sh
cd android
./gradlew bundleRelease
```

## Signing

Release bundles are signed with a Google Play upload key supplied through
environment variables. Key material is never committed. For CI, a GitHub
Actions environment named `release` with deployment branches restricted to
`main` must exist. In it these secrets must be set (not as repository
secrets, which every branch can read):

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
