# Changelog

All notable changes are recorded here. Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project does not yet semver-tag deps internally so all versions stay in the `0.x` lane.

Pre-built artifacts for each release ship in `dist/<version>/`.

## [0.21.0]

User-driven UX overhaul — the first release that responds to direct visual feedback and feature requests rather than internal QA polish.

- **Player UI redesign** to match a reference mock — phone variant now has:
  - A `NowPlayingTopBar` at the top: ▽ chevron-down · "Now Playing" · ⋮ overflow.
  - A `GlowingArtwork` Composable that wraps the cover art in a soft warm radial gradient (peach → transparent), giving the cover the signature "glowing" appearance.
  - The favorite heart moved into a translucent circle on the artist row.
  - Background simplified to near-black; the artwork glow does the warmth.
  - Automotive variant intentionally unchanged — the compact AAOS layout has different ergonomic constraints.
- **OfflineSettings redesign** — single-screen, no-scroll layout:
  - Discrete `250 MB / 500 MB / 1 GB / 2 GB` quota pills replaced with a `Slider` (100 MB minimum, 2 GB maximum, 50 MB step). Current quota label moves to the row header alongside "Storage limit".
  - All vertical spacing tightened (sections collapsed from 26 dp to 10–12 dp; card padding from 18 dp → 14 dp; type scaled down a tier).
  - "Save your favorites for offline playback" → "Make your favorites available for offline playback" (matches the user's preferred copy).
  - **Version footer** at the bottom (`v0.21.0`, derived from `BuildConfig.VERSION_NAME`).
- **TrackRow gains a like/unlike toggle** under the cover art — small filled-or-outlined heart, peach when active. Wired through `TrackListBody` → `TrackListContent` → `LatestRoute` / `PopularRoute` / `LibraryRoute` → ViewModel-level `toggleFavorite(track)` with optimistic UI + server-confirmed rollback.
- **Pagination on Latest / Popular / Library** — when the LazyColumn reaches the last 4 items, the corresponding ViewModel fetches the next page (count = 30) and appends. A loading spinner item renders at the tail while the fetch is in flight. State carries `nextPage` / `hasMore` / `loadingMore`.
- **Pull-to-refresh on Latest / Popular / Library** — Material 3 `PullToRefreshBox` wraps `TrackListBody`. ViewModels expose `pullToRefresh()` distinct from `refresh()` so the refreshing indicator isn't shown for the initial load.
- **Bottom-nav tab reselect → scroll to top** — `ScrollToTopBus` (object singleton in `core:model`) emits the route key when the user taps the already-selected tab. Each route's Composable subscribes via `LaunchedEffect` and calls `listState.animateScrollToItem(0)`. No nav-graph re-creation, no fetch.
- **i18n** — Spanish strings added for the new `player_top_bar_title` / `player_more_actions` and the updated settings copy.

Build is green: `./gradlew :app:assembleRelease :app:bundleRelease` succeeds, dist/0.21.0/ staged. **Tests not yet updated** for the new VM constructor signatures (`MeRepository` added to `LatestViewModel`, `PopularViewModel`); they'll need a follow-up pass to recompile.

## [0.20.0]

- **GitHub issue + PR templates** — `.github/ISSUE_TEMPLATE/{bug_report,feature_request}.yml` (form-style with field validation), `.github/ISSUE_TEMPLATE/config.yml` (disables blank issues, points security reports to private advisories), `.github/PULL_REQUEST_TEMPLATE.md` (concise What/Why/Verification with `scripts/dev.sh` checkboxes).
- **SECURITY.md** — coordinated-disclosure policy. In/out-of-scope, response-time targets (5/10/30 days), what we'll/won't do, and a defense-in-depth summary documenting the security invariants the codebase already enforces (Keystore-encrypted token, host-scoped interceptor, etc).
- **README badges** — build, tests, coverage, license, min/target SDK status badges in the README header. Pure SVG shields, no third-party services.
- **Project docs table** added to the README so newcomers can find CONTRIBUTING / CHANGELOG / SECURITY / RELEASE / LICENSE in one place.
- Build still green. 197 debug / 168 release tests, 0 failures, 62.3% coverage, all gates pass.

## [0.19.0]

- **`scripts/dev.sh`** — single dev-experience entry point for the most common tasks: `check` (PR-style fast gates), `ci` (full pipeline locally), `format` (spotlessApply), `coverage` (Kover HTML + summary), `install` (`adb installDebug`), `release` (build + stage `dist/<version>/` automatically). The release subcommand parses `versionName` from `app/build.gradle.kts` so bumping the version is the only manual step before staging.
- **CONTRIBUTING.md** updated to reference the script as the canonical local-CI entry.
- This release was packaged using `scripts/dev.sh release` itself — eats its own dogfood.
- Build still green: 197 debug / 168 release tests, 0 failures, 0 lint, 62.3% line coverage; all gates (`spotlessCheck`, `checkArchitecture`, `koverVerify`) pass.

## [0.18.0]

- **Kover verification thresholds** — `koverVerify` now fails if aggregate line coverage drops below 60% or instruction coverage below 55%. Wired into CI alongside the existing report generation, so a coverage regression breaks the `test-and-lint` job rather than passing silently.
- **Architecture smoke test** (`./gradlew checkArchitecture`) — walks every `feature/*/build.gradle.kts` and fails if any module declares a dependency on a sibling `feature/*` module other than `feature:catalog` (the shared design-system feature with `TrackListBody`/`TrackRow`/etc). Currently zero violations. Wired into CI before the test+lint steps so violations surface fast.
- **More `HypeMediaLibraryCallback` callback paths covered** (6 cases) — `loadItem` on a section id, `loadItem` on a known playlist id (resolves the playlist name), `loadItem` on an unknown playlist id (falls back to "Playlist N"), `loadChildren` on a `search:...` parentId returns search results, `loadChildren` on a `playlist:N` parentId returns the playlist tracks, `loadChildren` on the `section:playlists` parentId returns browsable playlist items.
- **Coverage**: 62.0% → **62.3% lines**, 60.6% → **61.0% instructions**, 38.1% → **38.8% branches**.
  - `auto/service` 42.4% → **46.8%**
- **Tests**: 191 → 197 (debug); 162 → 168 (release).

## [0.17.0]

- **Spotless + ktlint** added at the root build with `subprojects { plugins.apply("com.diffplug.spotless") }`. Kotlin sources, Gradle Kotlin scripts, and Markdown / `.gitignore` files are all formatted on `./gradlew spotlessApply`. Initial run reformatted ~25 files (whitespace + import grouping + brace placement). Wired `spotlessCheck` into the GitHub Actions workflow as a fast-fail step before tests + lint.
- **CHANGELOG.md extracted** from the README. README's Releases section is now a 5-line pointer to the changelog instead of a 150-line wall of per-version notes; the dist/ artifacts are still listed.
- Build pipeline still green: 191 debug / 162 release tests / 0 failures, 0 lint issues, **62.0% line coverage** (Spotless reformat shifted the counter by ~20 lines, no behavioral change).

## [0.16.0]

- **`HypePlaybackManagerTest`** (7 cases) — first direct test of the ExoPlayer-backed playback manager via Robolectric. Covers `play(emptyList())` clearing the queue, `play(tracks)` indexing items into the StateFlow queue, `updateFavorite` reflecting the change in `queue.value.current.track` (with loved-count delta), `updateFavorite` ignoring unknown trackIds, `cycleRepeatMode` walking OFF→ALL→ONE→OFF, `toggleShuffle` flipping the queue flag, and `acknowledgePlaybackError` being a no-op when no error is pending. Uses `ShadowLooper.idleMainLooper()` to flush ExoPlayer's listener callbacks.
- **More `HypeMediaLibraryCallback` callback tests** (4 cases) — fallback path when source-page lookup returns nothing, `resolveMediaItems` passes through items that already have `localConfiguration`, `resolveMediaItemsWithStartPosition` clamps an invalid `startIndex` to `[0, lastIndex]`, `loadChildren` on an unknown parentId returns empty.
- **Coverage**: 59.7% → **62.2% lines**, 59.1% → **60.7% instructions**, 36.2% → **38.1% branches**.
  - `core/playback` 0% → **57.8%** — the deferred ExoPlayer gap is now closed.
  - `auto/service` 39.2% → **42.4%**
- **Tests**: 180 → 191 (debug); 151 → 162 (release — playback + auto tests run on both variants since they aren't UI tests).

## [0.15.0]

- **`MoreMapperTests`** (15 cases) covers BlogDto / UserDto / TagDto / GetTokenResponseDto / FavoritesCountDto edge cases that `TrackDtoMapperTest` didn't reach — blank-string normalization, nullable defaulting, nested-DTO mapping, presence-flag detection (`featured`, `following`, `isLoved`), thumbnail field flow-through.
- **`MiniPlayerBarScreenTest`** (4 cases) — paused state shows Play, playing state shows Pause, transport buttons (Previous / Play / Next / Open player) all forward to their respective callbacks, and the same affordances render under both `AppChromeMetrics.phone()` and `.automotive()`. Required widening `MiniPlayerBar` from `private` → `internal` for test access; semantics of the composable are unchanged.
- **Coverage**: 57.3% → **59.7% lines**, 56.8% → **59.1% instructions**, 34.5% → **36.2% branches**.
  - `core/network/dto` 47.1% → **88.2%**
  - `app/dev/josu/hypecar` 39.7% → **50.1%**
- **Tests**: 176 → 180 (debug); release stays at 151.

## [0.14.0]

- **Last two feature modules covered** by Compose UI tests — `feature/details` (Blog/User/Tag routes) and `feature/player`. Same Robolectric + ui-test-junit4 + `testDebug/` source-set pattern as 0.12/0.13.
- **`DetailsRoutesScreenTest`** (3) — `BlogDetailRoute` renders blog name + follower/track counts + first track; `UserDetailRoute` renders username + favorites; `TagDetailRoute` renders tag heading + "Tagged cuts" stat + forwards `play(idx)` correctly.
- **`PlayerRouteScreenTest`** (4) — idle "Nothing is playing." state; active queue renders title/artist/Play action; tapping Next forwards to `playbackRepository.skipNext()`; tapping the Favorite icon forwards `updateFavorite(trackId, true)`.
- **Coverage**: 47.9% → **57.3% lines**, 45.4% → **56.8% instructions**, 28.8% → **34.5% branches**.
  - `feature/details` 33.8% → **96.4%**
  - `feature/player` 21.8% → **74.4%**
- **One name-collision pitfall**: `testDebug` and `test` source sets are merged for compilation, so `private` classes/objects with the same name in the same package collide across the two source sets. Fix is just to rename (e.g., `DetailsScreenNoOpPlayback` instead of `NoOpPlayback`).
- **Tests**: 169 → 176 (debug); release stays at 151.

## [0.13.0]

- **3 more modules of Compose UI tests** — `feature/catalog`, `feature/library`, `feature/search` all gained the same Robolectric + ui-test-junit4 setup as 0.12.0's auth/app, with tests in `src/testDebug/`.
- **`TrackListBodyScreenTest`** (5) — covers loading spinner, error state with Retry button + callback, empty state with localized fallback, track row rendering + `onTrackClick(index)` wiring, and the localized "Unknown artist/track" placeholders for blank-field tracks.
- **`LibraryRouteScreenTest`** (2) — signed-out Library renders sign-in card with working Sign-in tap; signed-in Library renders favorites list.
- **`SearchRouteScreenTest`** (3) — idle hero + tag chips, tag chip click forwards tag name, Go button fires immediate search and renders results.
- **Coverage**: 36.1% → **47.9% lines**, 31.7% → **45.4% instructions**, 20.1% → **28.8% branches**.
  - `feature/library` 17.9% → **80.2%**
  - `feature/search` 26.3% → **96.0%**
  - `feature/catalog` 24.5% → **66.8%**
- **One known limitation surfaced**: Compose UI tests don't drive coroutine `delay()` via the test scheduler. `composeRule.mainClock.advanceTimeBy(N)` only advances the recomposition frame clock, not coroutine virtual time. Workaround used here: bypass the debounce path via the Go button. Time-based debounce semantics stay covered by `SearchViewModelTest` at the unit level.
- **Tests**: 159 → 169 (debug); release stays at 151 (screen tests live in `testDebug` only).

## [0.12.0]

- **Compose UI screen tests via Robolectric** — `androidx.compose.ui:ui-test-junit4` test rig added to `feature/auth` and `app`. Tests live in the `src/testDebug/` source set so the `ui-test-manifest` activity declaration only loads in the debug variant (release-variant unit tests stay clean).
- **`LoginRouteScreenTest`** (5 cases) — title/blurb/fields/button rendering, button enabled-disabled state vs field content, Show/Hide password toggle, success → `onLoggedIn` callback, failure → friendly error text.
- **`OfflineSettingsRouteScreenTest`** (3 cases) — header + storage-limit pills + main actions render, "Clear cached data" opens AlertDialog, Cancel dismisses it without calling repository, Clear confirms and calls `clearDownloads()`.
- **Pinned Robolectric SDK = 34** via `@Config(sdk = [34])` since Robolectric 4.12.2's bundled shadows top out at API 34 while the app targets 35.
- **Coverage**: 27.8% → **36.1% lines**, 22.4% → **31.7% instructions**. `feature/auth` jumped from 13.5% → **97.4%** (the LoginRoute composable is now exercised end-to-end). `app` from 10.8% → **39.7%** (OfflineSettingsRoute composable + dialog covered). Total tests: 151 (release) / 159 (debug).
- The pattern is now reproducible — adding `feature/library`, `feature/search`, etc. screen tests is a copy-paste of the same setup.

## [0.11.0]

- **Kover code coverage** wired across every module. `./gradlew koverHtmlReport` writes an aggregated report to `build/reports/kover/html/index.html`; `./gradlew koverXmlReport` produces the machine-readable form. Hilt-generated factories, `BuildConfig`, Compose `ComposableSingletons`, and androidx packages are excluded so the numbers reflect *our* code.
- **Headline coverage**: 27.8% lines, 22.4% instructions, 17.6% branches. Strong on logic packages (`core/network` 97.5%, `core/model` 88.9%, `core/data/repository` 79.6%, `core/data/local/entity` 87.9%), thin on Compose UI screens and `HypePlaybackManager` (the deferred ExoPlayer test) — both expected.
- **CI integration**: the `test-and-lint` GitHub Actions job now runs `koverHtmlReport koverXmlReport`, prints a coverage summary to the build log, and uploads the HTML report as a 14-day artifact for every push and PR.
- **README** Tech-stack section updated to mention Kover + CI.

## [0.10.0]

- **`DefaultOfflineRepositoryTest`** (5 cases) — full end-to-end orchestration. Robolectric Context + real DataStore + real filesystem + a `MockWebServer` for the audio download host. A custom `StreamUrlRewriter` interceptor swaps `https://hypem.com/serve/...` requests for the local `MockWebServer` URL so the production `track.streamUrl()` path is exercised unchanged. Verifies: `setEnabled(true)` publishes status; `syncFavorites()` writes records and downloads audio; `audioUnavailable` tracks are skipped (no HTTP call made); `clearDownloads()` removes both files and records; `cachedAudioUri()` returns null when offline mode is disabled.
- **`renovate.json`** — automated dependency PRs. Weekly Monday early-morning schedule, grouped bumps for `androidx.compose`, `androidx.media3`, `dagger/hilt`, `kotlinx.coroutines`. Major-version updates labeled and never auto-merged. Uses semantic-commits + a dependency dashboard issue.
- **Tests:** 146 → 151.

## [0.9.0]

- **`UnauthorizedSessionInterceptor` extracted** from the inline lambda in `DataModule` into its own named class in `core/data/.../repository/`. Takes a `SessionGateway` plus an optional `apiHost` (defaults to `api.hypem.com`). `SessionGateway.invalidate()` is now part of the interface (default no-op) so the interceptor is fully testable against any gateway implementation.
- **`UnauthorizedSessionInterceptorTest`** (4 cases) — drives a real OkHttp client through `MockWebServer`: 401 from the configured host fires `gateway.invalidate()`, 200 doesn't, 401 from a *different* host doesn't, three sequential 401s fire three invalidates (the gateway debounces, the interceptor doesn't).
- **GitHub Actions CI workflow** at `.github/workflows/build.yml` — two jobs:
  - `test-and-lint`: `testDebugUnitTest testReleaseUnitTest lintDebug lintRelease` on every push and PR; uploads test/lint reports as artifacts on failure.
  - `package`: assembles release APK + AAB on `main`-branch pushes; uploads as 30-day artifacts. Both jobs use Temurin JDK 17 with Gradle wrapper + caches keyed off `libs.versions.toml`.
- **Tests:** 142 → 146.

## [0.8.0]

- **Robolectric arrives in `core/data`** — pulled in `org.robolectric:robolectric` + `androidx.test:core` + `androidx.work:work-testing` so DataStore- and WorkManager-backed code can be exercised on the JVM with a real `Context`.
- **`HypeSessionStoreTest`** (6 cases) covers the session-storage surface: `currentToken` start state, `save` → `session` flow + `currentToken`, `clear` wipes both, cipher is invoked with plaintext (no plaintext leakage), `invalidate` is a no-op when empty, `invalidate` clears active session asynchronously. Each test constructs a single store instance — DataStore guarantees one active instance per file per process; the test framework respects that.
- **`OfflineFavoritesSyncWorkerTest`** (3 cases) drives `OfflineFavoritesSyncWorker.doWork()` through `TestListenableWorkerBuilder`: success when sync completes clean, retry when repository status carries an error, retry when sync throws.
- **LICENSE (MIT)** and **CONTRIBUTING.md** added — formal open-source housekeeping. CONTRIBUTING covers dev setup, project layout, code style, testing patterns, and release flow.
- **Tests:** 133 → 142.

## [0.7.0]

Pure test coverage pass — no production behavior changes; closes the remaining ViewModel/integration gaps from the audit.

- **`PopularViewModelTest`** (4) — init refresh, mode switch (NOW → LAST_WEEK), no-op on same index, error → state.
- **`DetailsViewModelsTest`** (5) — `BlogDetailViewModel` (init load + error path), `UserDetailViewModel` (init load), `TagDetailViewModel` (init load + play forwarding). All three exercised via real `SavedStateHandle(mapOf(...))` instances rather than mocks.
- **`HypeMediaLibraryCallbackMetadataTest`** extended (1) — verifies B2 fix end-to-end: a media id with `?src=section:latest&pg=2` causes `loadChildrenInternalSuspend` to call `catalogRepository.latest(page = 3, ...)` (not page 1), and the resolved queue is the page-2 slice with `startIndex` pointing at the originally selected track.
- **`PlayerErrorEventTest`** (2) — `PlayerViewModel.acknowledgePlaybackError` clears matching events, ignores stale eventIds when a fresher error has already arrived.
- **Tests:** 121 → 133.

## [0.6.0]

- **Spanish locale shipped** (`values-es/strings.xml` for app + 6 feature modules) — pipeline smoke test that proves any locale can be added by dropping a `values-xx/` next to existing resources.
- **`OfflineSettingsUiModel` is data-only** — pre-formatted English label fields (`quotaLabel`, `usedLabel`, `downloadedLabel`, `statusLabel`) replaced with raw `quotaBytes`, `usedBytes`, `downloadedTrackCount`, and a new `OfflineSyncStatus` enum (`SYNCING` / `SYNCED` / `WAITING` / `OFF`). Composable helpers `formatBytesLabel` / `usedLabel` / `quotaQuotaLabel` / `downloadedCountLabel` / `syncStatusLabel` resolve via `stringResource` at the UI layer. Test suite updated to assert on raw fields + the new enum.
- **Mapper "Unknown" placeholders moved out of `core/network`** — `TrackDto.toModel()` now writes empty strings for missing artist/title/blog. `TrackRow` substitutes `stringResource(R.string.catalog_unknown_*)` at render time so the placeholder is localized (Spanish: "Artista desconocido" / "Pista desconocida" / "Blog desconocido").
- **A3 — playback error UX surfaced** — `PlaybackQueue.transientError: PlaybackErrorEvent?` (eventId + trackId + recoverable flag). `HypePlaybackManager.onPlayerError` emits an event when it auto-skips or stops; `PlayerRoute` shows a Material 3 Snackbar ("Couldn't play that track. Skipping." / "Couldn't play that track.") with a Dismiss action and calls `acknowledgePlaybackError(eventId)` to clear it. New `PlaybackRepository.acknowledgePlaybackError(eventId): Unit` method (default no-op for old implementations).
- **Tests:** 108 → 121. New `OfflineSettingsViewModelTest` (5 — setEnabled / setQuota / syncNow / clearDownloads forwarding + status pass-through). New `LatestViewModelTest` (5 — init refresh, mode switch, no-op when same index, error → state, play(idx) forwards correct slice). Existing `OfflineSettingsUiModelTest` rewritten (4 cases) to assert on the new enum-based shape.

## [0.5.0]

- **Full string-resource extraction** — every user-visible string in the in-tree screens now resolves through `strings.xml`. New resource files: `feature/auth/res/values/strings.xml` (8 strings), `feature/library/res/values/strings.xml` (15), `feature/search/res/values/strings.xml` (9), `feature/details/res/values/strings.xml` (7), `feature/player/res/values/strings.xml` (15), `feature/catalog/res/values/strings.xml` extended (6), and `app/res/values/strings.xml` extended with the offline-settings copy block (~20). Total: 7 modules now ship localizable resources, not just `app_name`. Translators can drop a `values-xx/` next to any of them.
- **Tests:** 99 → 108. New `SearchViewModelTest` (4 — debounce window, rapid-change coalescing, empty-query clears state, sort change triggers immediate refetch). New `LibraryViewModelTest` (5 — signed-out skips API, signed-in pulls favorites, tab switch swaps source, history works without session, errors land in state without leaving loading=true).
- The `TrackListBody` `emptyMessage` parameter is now nullable; supplying `null` falls back to the localized `catalog_empty_default` string.

## [0.4.0]

- **B2 Auto deep-page bug fixed:** `HypeMediaIds.track(id, sourceId, sourcePage)` now encodes the Media3 source page in the media id (`track:{id}?src={sourceId}&pg={page}`). When an Auto user taps a track from a deep-paged source, `resolveMediaItemsWithStartPositionSuspend` refetches the *correct* page, so the listener gets the surrounding queue context instead of a single isolated track.
- **B9 favorite-aware quota eviction:** `OfflineEvictionPlanner` (extracted from `DefaultOfflineRepository` into a pure-logic class) prefers evicting records the user no longer favorites first, then falls back to oldest-first. Stale records are dropped eagerly even when within quota — no more cache flapping where un-favorited tracks survive at the expense of newer favorites.
- **U10 swipe-dismiss a11y:** Player root now exposes `Modifier.semantics { dismiss(label = "Close player") }` + a custom accessibility action, so TalkBack users can close the player without the swipe gesture.
- **U1 partial localization:** Bottom-nav labels (`Latest` / `Popular` / `Library` / `Search` / `Settings`), mini-player content descriptions (`Previous` / `Pause` / `Play` / `Next` / `Open player`), offline-cache confirm dialog, and catalog Retry button moved to `strings.xml` (app + new `feature/catalog/res/values/strings.xml`).
- **Tests:** 88 → 99. New: `OfflineEvictionPlannerTest` (5 — eager stale eviction, mixed quota+stale, oldest-first fallback, empty input, all-stale wipe), `SessionInvalidationTest` (3 — null guard, basic clear, repeated calls), `HypeMediaIdsTest` extended (3 — paged round-trip, default page=0, naked id has no source).

## [0.3.0]

- **UX:** Mode/sort chips now use curated display strings (`Last week`, `Most favorited`, `Only remixes`…) via a new `displayLabel` field on `LatestMode` / `PopularMode` / `FeedMode` / `SearchSort` — no more `LATEST_NEW`-style enum-name leakage.
- **UX:** Search is now debounced at 350 ms — typing auto-triggers a query without tapping Go. Empty query clears results immediately.
- **UX:** Phone transport buttons (mini-player + player play/pause/skip/shuffle/repeat/favorite) emit `HapticFeedbackType.TextHandleMove` ticks. Skipped on Auto.
- **Performance:** `HypePlaybackManager` per-second progress tick now updates only `positionMs`/`durationMs`/`isPlaying` (was rebuilding the full queue list 60×/min).
- **Network:** OkHttp `Cache(10 MB)` enabled with a `max-age=60` rewrite for `GET api.hypem.com` responses — repeated screen visits within the minute hit the disk cache instead of network.
- **Database:** `Room.exportSchema = true` and KSP `room.schemaLocation` configured; `core/data/schemas/dev.josu.hypecar.core.data.local.HypeDatabase/1.json` is now versioned for future migration safety.
- **Robustness:** `TrackDao.byIds` calls now go through `byIdsChunked` — splits into 500-row chunks, eliminating the SQLite IN-clause limit risk for large id lists. (3 new tests.)
- **Tests:** 85 → 88.

## [0.2.0]

- **Bug fixes:** history pagination now respects `page`; offline sync skips `audioUnavailable` tracks, pages through favorites instead of single-shot 80, and aborts cleanly when offline mode is disabled mid-sync.
- **Security:** `HypeApiInterceptor` only trusts `10.0.2.2`/`localhost` when the dev-proxy build flag is on (release builds reject loopback hosts entirely). Unauthorized 401s from `api.hypem.com` now invalidate the stored session.
- **Network errors:** new `ApiError` sealed type maps `HttpException`/`IOException`/`SerializationException` to friendly user-facing messages. Login surfaces "Username or password is incorrect" instead of a serialization stack trace.
- **Android Auto:** `runBlocking` on a 2-thread executor replaced with `kotlinx-coroutines-guava` `future {}`. IO failures now return `LibraryResult.ofError(SessionError.ERROR_IO)` so Auto can show retryable error states. Browsable / playable items now declare `MediaMetadata.MEDIA_TYPE_*` for grid icons.
- **UX:** dead "Forgot password?" / "Create account" UI removed from login; `Username` survives process death (`rememberSaveable`); IME action submits login. Player scrubber gets accessibility semantics (`Role.Image` + `progressBarRangeInfo` + `setProgress`). "Clear cached data" now requires a confirmation dialog. Track-list error rows include a Retry button.
- **Build hygiene:** stale `core/{model,network}/bin` mirrors deleted; `**/bin`, `/tmp`, `.DS_Store`, `/.superpowers` added to `.gitignore`. JVM toolchain unified across all modules (JDK 17 source/target).
- **Tests:** 60 → 85 unit tests. New coverage for `ApiError` mapping, `HypeApiInterceptor` token scoping (incl. dev-proxy flag), `DefaultAuthRepository`, `DefaultCatalogRepository` cache-on-error fallback, `DefaultSearchRepository`, `DefaultHistoryRepository`, `MeRepository.history` pagination, `OfflineSyncAccumulator` dedup/quota, `LoginViewModel` (success / 401 / network), `ResilientDns` fallback chain.

