# Release Checklist

This app should be distributed as an Android App Bundle.

## Signing

Provide the release keystore through Gradle properties or environment variables. Do not commit the keystore or passwords.

```bash
export HYPE_RELEASE_STORE_FILE=/absolute/path/to/release.keystore
export HYPE_RELEASE_STORE_PASSWORD='...'
export HYPE_RELEASE_KEY_ALIAS='...'
export HYPE_RELEASE_KEY_PASSWORD='...'
```

For GitHub Actions, prefer storing the keystore file as a base64 secret named `HYPE_RELEASE_KEYSTORE_BASE64`; the workflow restores it to a temporary file and exports `HYPE_RELEASE_STORE_FILE` automatically. The password and alias secrets use the same names as the environment variables above. If those secrets are not configured, CI still runs tests/lint and skips signed artifact packaging with a notice.

Then build the Play Store bundle:

```bash
./gradlew clean testDebugUnitTest lintDebug bundleRelease
```

The signed bundle is written to:

```text
app/build/outputs/bundle/release/app-release.aab
```

If the signing variables are not set locally, Gradle fails release packaging before producing APK/AAB outputs. Use debug builds for local verification.

## Store Review

- Use neutral wording: this is an unofficial open-source Hype Machine client.
- Complete Play Data Safety based on the actual app behavior: account username/token storage, network requests to Hype Machine, and playback metadata.
- Do not claim affiliation with Hype Machine.
- Re-run `./gradlew spotlessCheck checkArchitecture testDebugUnitTest testReleaseUnitTest lintDebug lintRelease koverVerify assembleRelease bundleRelease` before uploading.
