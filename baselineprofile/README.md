# Baseline Profile and Macrobenchmark harness

This module keeps release startup and the app's core navigation paths ahead-of-time compiled.
It uses the official AndroidX Baseline Profile and Macrobenchmark toolchain.

## Generate the checked-in profile

Connect an API 33+ physical device or emulator, then run:

```bash
./gradlew :app:generateBaselineProfile
```

Generation is intentionally not part of ordinary builds or CI. The generated files are saved under
`app/src/main/generated/baselineProfiles/` and are consumed by every subsequent release build.

The generator covers deterministic, credential-free paths: cold launch to Latest, a list scroll, and
navigation through Search, Settings, and back to Latest. The public track feed is network-backed, so
playback is kept out of the checked-in generator to prevent flaky or silently changing profiles. The
separate `PublicPlaybackJourneyTest` exercises Latest -> first Play -> full Player when live data is
available. It is opt-in and skips cleanly by default:

```bash
./gradlew :baselineprofile:connectedNonMinifiedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.josu.hypecar.baselineprofile.PublicPlaybackJourneyTest \
  -Pandroid.testInstrumentationRunnerArguments.liveCatalog=true
```

## Measure

Run benchmarks on a physical, non-low-battery API 29+ device for meaningful numbers:

```bash
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.josu.hypecar.baselineprofile.StartupBenchmark
```

Use `NavigationBenchmark` for scroll/navigation frame timing. Emulator results are useful only as a
functional check; they are not performance evidence.

## Verify packaging without a device

```bash
scripts/dev.sh profile-check
```

This builds the unsigned minified release APK and verifies that compiled baseline and startup profile
artifacts are packaged. It is safe on CI and does not generate a profile or start an emulator.

## References

- [Baseline Profiles overview](https://developer.android.com/topic/performance/baselineprofiles/overview)
- [Create Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile)
- [Configure Baseline Profile generation](https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles)
- [AndroidX Benchmark releases](https://developer.android.com/jetpack/androidx/releases/benchmark)
