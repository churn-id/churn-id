# Churn

Placeholder for the Churn Android app (application ID `id.churn`).
There is no functionality yet.

## Building

CI builds a release bundle on every push to `main`; download it from the
workflow run's artifacts (`churn-release-aab`).

Locally, with JDK 17 and the Android SDK installed:

```sh
./gradlew bundleRelease
```

## Signing

Release bundles are signed with a Google Play upload key supplied through
environment variables. Key material is never committed. For CI, create a
GitHub Actions environment named `release`, limit its deployment branches to
`main`, and set these secrets in it (not as repository secrets, which every
branch can read):

| Secret | Contents |
| --- | --- |
| `CHURN_UPLOAD_KEYSTORE_B64` | The upload keystore, base64-encoded |
| `CHURN_KEYSTORE_PASSWORD` | Keystore password |
| `CHURN_KEY_ALIAS` | Key alias |
| `CHURN_KEY_PASSWORD` | Key password |

Builds of pull requests and other branches are always unsigned.

Recent `keytool` versions create PKCS12 keystores, which use a single
password for the keystore and the key. In that case set
`CHURN_KEY_PASSWORD` to the same value as `CHURN_KEYSTORE_PASSWORD`.
