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
./gradlew clean testDebugUnitTest lintDebug -PrequireReleaseSigning=true bundleRelease
```

The signed bundle is written to:

```text
app/build/outputs/bundle/release/app-release.aab
```

Tagged GitHub releases also attach a `SHA256SUMS` file covering the published
APK and AAB. Verify a downloaded artifact with `sha256sum -c SHA256SUMS` before
distributing it elsewhere.

The required `-PrequireReleaseSigning=true` guard fails packaging before producing APK/AAB outputs when any signing variable is missing. Plain release tasks are allowed only for local unsigned R8 verification. Use debug builds for installable local verification.

## Store Review

- Use neutral wording: this is an unofficial open-source Hype Machine client.
- Complete Play Data Safety based on the actual app behavior: account username/token storage, network requests to Hype Machine, and playback metadata.
- Do not claim affiliation with Hype Machine.
- Upload this bundle only to the mobile/projected-Android-Auto track. A Play AAOS release requires a separate automotive application module and artifact; the current unified bundle is not eligible for that track.
- Re-run `scripts/dev.sh ci`, then `./gradlew -PrequireReleaseSigning=true assembleRelease bundleRelease` before uploading.
