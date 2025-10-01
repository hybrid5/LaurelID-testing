# Release Signing Scaffold

The Android build now includes a `release` signing config that is populated at build
time from environment variables or `gradle.properties`. When the credentials are
missing, the build automatically falls back to the debug keystore so CI and local
development remain unblocked.

## Configure credentials

Provide the following values either as environment variables or Gradle properties.
Values from the environment take precedence.

| Purpose | Environment variable | `gradle.properties` key |
| --- | --- | --- |
| Keystore file path | `LAURELID_RELEASE_KEYSTORE` | `laurelIdReleaseKeystore` |
| Keystore password | `LAURELID_RELEASE_KEYSTORE_PASSWORD` | `laurelIdReleaseKeystorePassword` |
| Key alias | `LAURELID_RELEASE_KEY_ALIAS` | `laurelIdReleaseKeyAlias` |
| Key password | `LAURELID_RELEASE_KEY_PASSWORD` | `laurelIdReleaseKeyPassword` |

Keep the keystore file **out of the repository** (for example in a secure secrets
bucket or on a local disk path) and set the path via the variables above.

Example `~/.gradle/gradle.properties` entry:

```
laurelIdReleaseKeystore=/Users/me/keys/laurelid.jks
laurelIdReleaseKeystorePassword=example-keystore-pass
laurelIdReleaseKeyAlias=laurelid
laurelIdReleaseKeyPassword=example-key-pass
```

## Verifying signing selection

Run a release build and inspect the task output to verify that the release keystore
is used. If any credential is missing the build falls back to the debug keystore.
