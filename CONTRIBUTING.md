# Contributing

Thanks for your interest. This is a small project — a few quick guidelines to keep contributions easy to review.

## Development setup

Requirements:
- JDK 17 (the Gradle wrapper handles everything else)
- macOS, Linux, or Windows

Clone, then verify the build:

```bash
./gradlew testDebugUnitTest lintDebug
# or, with the fast subset of the CI gates (CI additionally runs release-variant
# tests/lint, an R8 smoke build, and the Kover coverage floor):
scripts/dev.sh check
```

`scripts/dev.sh` collects the common dev tasks under one entry point — `check` (fast PR-style validation), `ci` (full pipeline locally), `format` (spotlessApply), `coverage` (Kover HTML + summary), `install` (adb installDebug), `release` (build + stage `dist/<version>/`). Run `scripts/dev.sh` with no args to see the menu.

The first Gradle run downloads Gradle 8.10.2 and AGP/Kotlin/Compose dependencies. Subsequent runs use the local cache.

For Android Auto / AAOS development, run the dev proxy in a second terminal:

```bash
python3 scripts/hypem_api_proxy.py
```

It listens only on `127.0.0.1:8787` and forwards to `https://api.hypem.com`. The debug build of the app routes through it automatically when running in an automotive emulator (`BuildConfig.ENABLE_AAOS_DEV_PROXY` is true in debug only).

## Project layout

See the **Modules** section in the README. The short version:
- `core/{model,network,data,playback}` — domain layer; pure Kotlin where possible.
- `feature/{auth,catalog,library,search,details,player}` — UI screens, each with its own ViewModel.
- `auto/` — Android Auto / AAOS `MediaLibraryService` + callback.
- `app/` — composition root, navigation, mini-player, offline settings.

## Code style

- Kotlin official style (`kotlin.code.style=official` is in `gradle.properties`).
- 4-space indent, trailing commas allowed.
- Avoid `!!` non-null assertions; the codebase intentionally has zero.
- Prefer `runSuspendCatchingPreservingCancellation` over plain `try/catch` in suspend functions — `CancellationException` must propagate so coroutine cancellation works.
- Use repository interfaces from `core:model`, not the `Default*` classes directly. This keeps tests simple (substitute fakes, not mocks).
- New user-visible strings go in `strings.xml` (per-module under `feature/<x>/src/main/res/values/`). Add the same key to `values-es/strings.xml` — full key parity with the Spanish reference locale is required, not optional.

## Tests

- Pure-JVM unit tests (`src/test/`) are the primary suite — they're fast and run on every CI build.
- Robolectric-backed tests live alongside JVM tests but use `@RunWith(RobolectricTestRunner::class)`. Used for `HypeSessionStore`, `OfflineFavoritesSyncWorker`, and the Auto callback.
- Test data fakes (e.g., `RepositoryTestFakes.kt`, `EmptyHypeApiService`) are reusable across tests in the same module.
- Coroutine tests use `StandardTestDispatcher` + `Dispatchers.setMain(...)` and `advanceUntilIdle()` — see `PlayerViewModelFavoriteTest` for the pattern.
- Aim for one happy-path + one error-path + one edge case per public method.

Run a single module's tests:

```bash
./gradlew :core:data:testDebugUnitTest
./gradlew :feature:player:testDebugUnitTest
```

## Adding a feature

1. Create or update the repository interface in `core/model/.../repository/Repositories.kt`.
2. Implement it in `core/data/.../repository/Default*.kt`. Bind via Hilt in `core/data/.../di/DataModule.kt`.
3. Add the ViewModel + Compose screen in the appropriate `feature/` module.
4. Wire navigation in `app/.../MainActivity.kt`.
5. Add unit tests for the ViewModel and any non-trivial repository logic.
6. Run `./gradlew testDebugUnitTest lintDebug` before sending a PR.

## Release process

See `RELEASE.md`. Short version: bump `versionCode` + `versionName` in `app/build.gradle.kts`, run the full pipeline, and copy the resulting artifacts to `dist/<version>/`.

## Licensing

By contributing you agree your contributions will be licensed under the MIT license (see `LICENSE`).
