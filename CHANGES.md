# Design Review — Implementation Status (historical)

This document is a point-in-time hand-off log for the `DESIGN_REVIEW.md`
batches. It is kept for history and is NOT maintained against the current
tree — see CHANGELOG.md for what actually ships.

> Corrections (2026-07-04): the build has long since been compile-verified
> (CI runs tests, lint, and an R8 smoke on every push). Three claims below
> drifted from reality and are called out here rather than rewritten:
> `StorageLimitPill` was later reinstated for the automotive quota picker;
> `player_queue_position` / the queue-position label never shipped in its
> described form; and the "Auth expiration silent failure" item DID land
> (MainActivity shows a session-expired snackbar via SessionEventBus).

## What landed in batch 2 (this commit set)

The previously "deferred" items from batch 1 are now mostly landed:

- **R2 — Hoisted optimistic favorite**: new
  `core/data/.../FavoriteSyncManager` (singleton) owns the
  optimistic-flip-then-server-confirm dance and emits
  `FavoriteEdit`s on a shared `Flow`. `LatestViewModel`,
  `PopularViewModel`, and `LibraryViewModel` each lost their ~30 lines of
  copy-pasted toggle logic and now `init { collect { applyTo(tracks, edit) } }`
  the shared stream. A heart tapped in Latest flips in Library and Feed
  without each VM re-fetching.
- **R4 — Unified phone + car APK**: `app/build.gradle.kts` now builds a
  single APK/AAB family. The main manifest advertises both projected Android
  Auto (`com.google.android.gms.car.application`) and Automotive media
  (`com.android.automotive`) capability, with the automotive hardware feature
  marked optional so the same package installs on phones and still exposes the
  Media3 browser service to cars.
- **Queue UI**: new `UpNextStrip` composable renders up to 6 upcoming
  tracks under the player as a horizontal carousel. Tapping a tile calls
  `PlayerViewModel.jumpToQueueIndex(absoluteIndex)`, which routes through
  `PlaybackRepository.play(tracks, startIndex)`. Reorder and remove are
  intentionally NOT wired — they need a design call on affordances.
- **Auth expiration snackbar**: `core/model/.../SessionEventBus.kt`
  (singleton SharedFlow, mirrors `ScrollToTopBus` pattern).
  `UnauthorizedSessionInterceptor` emits `SessionEvent.Expired` on every
  HTTP 401. `MainActivity` subscribes via a `LaunchedEffect` and surfaces
  the snackbar through a new `SnackbarHostState`. en + es strings added.
- **Skeleton loaders**: new `core/ui/.../Skeleton.kt` with
  `SkeletonBlock`, `SkeletonTrackRow`, and `SkeletonTrackList`. Catalog's
  empty-state loading path now renders six pulsing skeleton rows instead
  of a centred `CircularProgressIndicator`. Library / Details still use
  the spinner — adopting the skeleton there is mechanical and can follow.
- **Phone UI hex-literal migration** to `hypeTokens`: the high-frequency
  semantic colors (`Color(0xFFFF8A3D)` → `hypeTokens.brand.primary`,
  `Color(0xFFFF6A21)` → `hypeTokens.brand.primaryStrong`, etc.) are
  migrated across `CatalogUi`, `PlayerRoute`, `LibraryRoute`,
  `SearchRoute`, `DetailsRoutes`, `OfflineSettingsRoute`. The remaining
  ~30 literals per file are one-off decorative gradient stops / alpha
  overlays and were left inline with the tokens import in place so a
  follow-up sweep is easy.
- **Detail screens back arrow**: `BlogDetailRoute`, `UserDetailRoute`,
  `TagDetailRoute` now take an optional `onBack: (() -> Unit)?`.
  `MainActivity` passes `navController::popBackStack`.
  `EditorialDetailFeed` renders an `IconButton` with `ArrowBack` overlaid
  on the hero image (top-leading, status-bar-padded, dark background
  scrim for legibility). en + es `details_back` strings added.
- **Login a11y + RTL**: the decorative "offline / rotation / synced"
  background text uses `clearAndSetSemantics {}` so TalkBack doesn't read
  it. The decorative circle offsets are now mirrored under RTL so the
  composition reflects on the leading side instead of always being on
  the right.
- **`Role.Button` on clickable Boxes**: applied on the player's filled
  heart Box and the mini-player cover thumbnail. The other call sites
  the audit flagged (chip selectors, TrackRow tappables) use Surface so
  the role is already correctly inferred.
- **Hardcoded English subtitles in `LibraryRoute`** are now externalised
  via `library_profile_subtitle_*` strings (en + es).

What remains intentionally untouched:

- **Reorder / remove in the queue UI** — needs a design call on
  affordances (drag handle? long-press? swipe?).
- **Sticky / collapsing search field** — requires restructuring
  `SearchRoute`'s LazyColumn header into a `stickyHeader` block. The
  refactor is contained but I want a separate review of the scroll
  semantics on Auto before landing.
- **Skeleton loaders in Library / Details** — mechanical follow-up; the
  composable is in place, only the call sites need swapping.
- **The remaining ~30 decorative `Color(0xFF…)` literals per UI file** —
  gradients and alpha overlays that don't have a clean semantic token.
  The tokens import is in every file so a follow-up sweep is friction-free.

## What landed in batch 1 (already documented above)

### Android Auto — both P0 and P1
- **Browse tree reduced from 6 sections to 4**: Latest, Popular, Favorites,
  More. Feed / Playlists / History live under the "More" umbrella one level
  deeper. (`auto/.../HypeMediaIds.kt`, `HypeMediaLibraryCallback.sectionItems`,
  `moreSectionItems`)
- **`MediaConstants.CONTENT_STYLE_*` hints** applied on every browsable
  parent via the new [`AutoBrowseHints`](auto/src/main/java/dev/josu/hypecar/auto/service/AutoBrowseHints.kt)
  helper — root and "More" render as category lists, while playable sections,
  playlists, placeholders, and search results render as compact list rows.
  `androidx.media:media` was added to the gradle catalog and wired into
  `auto/build.gradle.kts` for these constants.
- **Section artwork**: 7 in-APK vector drawables under
  `auto/src/main/res/drawable/ic_auto_section_*.xml` plus
  `ic_auto_signin.xml`. Each section tile carries an `artworkUri` so the
  root grid is no longer a row of blank rectangles.
- **`onSearch` now warms the cache** and calls `notifySearchResultChanged`.
  Previously it returned `LibraryResult.ofVoid()`; Assistant voice search now
  primes results instead of hitting the network cold.
- **Sign-in `PendingIntent`** wired via `MediaLibrarySession.setSessionActivity`
  in `HypeMediaLibraryService` — the car HUD now has a system-level
  "open on phone" affordance. The sign-in placeholder tile also carries
  `ic_auto_signin` artwork so it's recognisable on the grid.
- **All Auto-facing strings localised** via
  `auto/src/main/res/values/strings.xml` and `values-es/strings.xml` — section
  titles, subtitles, sign-in copy, empty-state copy, custom-command labels,
  fallback playlist names.
- **Subtitle metadata** added to track items: "via Blog · N loved" (or just
  "via Blog" when loved count is 0). Plus `setDescription` from
  `track.postDescription` and extras carrying `blog_id` / `blog_name` for
  future car-safe actions.
- **Car transport row preserved**: previous/next are standard Media3 player
  command buttons, while favorite is routed to overflow so it cannot displace
  skip-next on Android Auto head units.
- **Empty-state placeholders** for signed-in sections that return zero rows
  on the first page (favorites / feed / playlists / history / playlist
  detail) — friendly title + subtitle + non-playable flags.
- **Pagination cap**: `MaxPages = 10` per section to bound driver
  distraction on long lists.
- **Tests added/strengthened**:
  - New `HypeMediaLibraryCallbackBrowseTest` covering 4-section structure,
    More umbrella children, signed-out branch, empty-state branch, subtitle
    formatting with/without loved count.
  - `HypeMediaIdsTest` extended with parser edge cases (URL-encoded source
    ids, reserved characters in search queries, unicode, malformed
    playlist/track ids, negative page clamping, non-numeric page).
  - `AndroidAutoManifestContractTest` tightened to assert
    `foregroundServiceType="mediaPlayback"`, the exact service class name,
    `MEDIA_PLAY_FROM_SEARCH` filter, and the
    `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission.

### `core/ui` design tokens module (R1)
- **New `:core:ui` module** registered in `settings.gradle.kts`, built with
  the Android Library + Compose plugins, hung off `app:build.gradle.kts` and
  every feature module's gradle file.
- **`HypeColors`**: every color literal that was scattered across the UI
  layer has a named token here (brand orange variants, cream surfaces, dark
  surfaces, chrome accents, status colors). Source of truth.
- **`HypeTokens`**: composite token table with `BrandPalette`,
  `CardPalette`, `ChromePalette`, `Radii`, `Spacing`, `MiniPlayerMetrics`,
  `PlayerProgressColors`. Light and dark variants flip the whole table at
  once; an `automotiveMiniPlayer()` factory produces compact mini-player
  metrics for AAOS.
- **`HypeTheme`**: Material 3 theme with both `lightColorScheme` and
  `darkColorScheme`, a derived `Shapes` set from the radii tokens, and
  `LocalHypeTokens` + `LocalIsAutomotive` composition locals.
- **Dark mode now flips on `isSystemInDarkTheme()`** — the previous
  light-only `lightColorScheme` was the only available palette.
- **App-level `AppTheme` kept as a shim** so `MainActivity.setContent { AppTheme { … } }`
  continues to work without an import churn. The activity now reaches for
  `HypeTheme(isAutomotive = …)` directly to feed the automotive metrics in.
- **`MainActivity` migrated** to consume `hypeTokens.chrome.*` and
  `hypeTokens.playerProgress.*` instead of inlined `Color(0xFF…)` literals.

### Connectivity infrastructure (R3)
- **`ConnectivityRepository` interface** in `core/model/.../Repositories.kt`
  with a three-state `Connectivity` enum (`Online` / `Limited` / `Offline`).
- **`AndroidConnectivityRepository`** in `core/data/.../net/` backed by
  `ConnectivityManager`. Registers a long-lived `NetworkCallback`, computes
  the initial state synchronously so the first collector gets the actual
  state instead of waiting for the first network change. Hilt-bound in
  `DataModule`.
- **`ConnectivityBanner` composable** in `core/ui` — slim cloud-off banner
  with a polite live region for TalkBack, animated slide in/out so it
  doesn't permanently add chrome height.
- **`AppChromeViewModel`** exposes the connectivity flow.
- **`MainActivity` shows the banner** above the mini-player + bottom nav.
  Also extends the bottom-bar visibility check so the banner can appear on
  routes that hide the nav (e.g., login).

### Phone UI quick fixes
- **Catalog Retry button wired**: `LatestRoute` and `PopularRoute` now pass
  `onRetry = viewModel::pullToRefresh` to `TrackListContent`. The button
  was rendered before but never reached the route — clicking it did
  nothing. `TrackListContent` signature gained `onRetry: (() -> Unit)?`.
- **Player `MoreVert` no-op hidden**: the icon and label used to render
  with a no-op `onClick`; TalkBack announced "More actions" with nothing
  to do. The button now renders only when an actual handler is provided;
  callers pass `null` until a menu spec exists.
- **Scrubber time labels track drag**: elapsed/remaining `Text` widgets
  now bind to the dragged `selectedProgress` while `isSeeking == true` and
  fall back to the live `model.elapsedLabel` on release. Previously the
  thumb visibly moved while the time digits stayed pinned.
- **Queue position label removed** from the player surface to keep the phone
  player focused. Queue state remains internal, and the up-next strip carries
  the forward-looking context.
- **Dead components removed**: `CompactInfoCard` in `PlayerRoute.kt` and
  `StorageLimitPill` in `OfflineSettingsRoute.kt` — both were defined and
  never invoked.
- **Touch-target bumps**:
  `AppChromeMetrics.phone().miniPlayerIconButtonSize` 42dp → 48dp,
  `AppChromeMetrics.automotive().miniPlayerIconButtonSize` 40dp → 44dp.
  Matching test thresholds updated.

### String externalization
- **`TrackRowUiModel`** dropped the hardcoded
  `"${lovedCount}   ·   reposted ${postedCount}x"` string. The row composable
  formats with `pluralStringResource(R.plurals.track_row_stats, …)`. ES
  plural added.
- **`UserProfileHeaderUiModel`** dropped the hardcoded "N favorites",
  "N followers", "N following" strings. `DetailsRoutes` formats with three
  new plurals (`user_profile_favorites/followers/following`). ES translations
  added.
- **`PlayerScreenUiModel`** replaced `queueLabel: String` (hardcoded
  English) with raw `queueIndex` / `queueSize` integers; the route formats
  via `R.string.player_queue_position`. ES translation added. The internal
  `formatMs` helper was made public so the route can re-format time labels
  during a drag.
- **`CatalogUi`** "Play" `contentDescription` and `utilityLabel = "Filter"`
  default now come from `R.string.catalog_action_play` and
  `R.string.catalog_action_filter`. ES translations added.

## What is deliberately NOT in this batch — deferred items

These items from the review are still open. Notes on why each was deferred:

- **Full migration of all phone UI files to `hypeTokens`** — only
  `MainActivity` was migrated. `CatalogUi`, `PlayerRoute`, `LibraryRoute`,
  `SearchRoute`, `DetailsRoutes`, `LoginRoute`, `OfflineSettingsRoute` still
  inline `Color(0xFF…)` literals. The infrastructure to migrate is now in
  place; each file is a mechanical pass best done with an IDE refactor or
  a follow-up agent run.
- **Favorite sync hardening** — `LatestRoute`, `PopularRoute`, and
  `LibraryRoute` now share a `FavoriteSyncManager` so failed favorite requests
  roll back consistently instead of leaving list hearts optimistic forever.
- **AAOS Gradle variant** (R4) — superseded by the unified APK decision. The
  app now keeps one package id and one installable artifact for phone plus car
  projection instead of maintaining a separate Automotive-only manifest.
- **Skeleton loaders** — catalog first-load paths now use skeleton rows.
  Search/details still use simple loading states and can migrate later.
- **Full queue UI**: up-next previews are visible; reorder and remove remain
  planned follow-ups.
- **Auth expiration silent failure** — `LibraryRoute` still flips to the
  signed-out hero without a snackbar.
- **Mini-player swipe-up gesture**.
- **Detail screens top-app-bar with back arrow**.
- **`Role.Button` on `Modifier.clickable` Boxes** (player favorite,
  mini-player cover, chip selectors, TrackRow tappables).
- **Login decorative art `contentDescription = null`** + **RTL-aware offsets**.
- **Sticky / collapsing search field**.
- **Stats text contrast bump** to clear AA on cream surface.
- **Settings slider vs Auto pills parity**.
- **`onCustomCommand` (favorite) and signed-out branch tests** in
  `HypeMediaLibraryCallbackMetadataTest` — new `HypeMediaLibraryCallbackBrowseTest`
  covers some of this, but the custom-command path is still untested.

## Files touched in this batch

### New files
- `core/ui/build.gradle.kts`
- `core/ui/src/main/java/dev/josu/hypecar/core/ui/HypeColors.kt`
- `core/ui/src/main/java/dev/josu/hypecar/core/ui/HypeTokens.kt`
- `core/ui/src/main/java/dev/josu/hypecar/core/ui/HypeTheme.kt`
- `core/ui/src/main/java/dev/josu/hypecar/core/ui/ConnectivityBanner.kt`
- `core/ui/src/main/res/values/strings.xml`
- `core/ui/src/main/res/values-es/strings.xml`
- `core/data/src/main/java/dev/josu/hypecar/core/data/net/AndroidConnectivityRepository.kt`
- `auto/src/main/java/dev/josu/hypecar/auto/service/AutoBrowseHints.kt`
- `auto/src/main/res/values/strings.xml`
- `auto/src/main/res/values-es/strings.xml`
- `auto/src/main/res/drawable/ic_auto_section_latest.xml`
- `auto/src/main/res/drawable/ic_auto_section_popular.xml`
- `auto/src/main/res/drawable/ic_auto_section_favorites.xml`
- `auto/src/main/res/drawable/ic_auto_section_more.xml`
- `auto/src/main/res/drawable/ic_auto_section_feed.xml`
- `auto/src/main/res/drawable/ic_auto_section_playlists.xml`
- `auto/src/main/res/drawable/ic_auto_section_history.xml`
- `auto/src/main/res/drawable/ic_auto_signin.xml`
- `auto/src/test/kotlin/dev/josu/hypecar/auto/service/HypeMediaLibraryCallbackBrowseTest.kt`
- `DESIGN_REVIEW.md`
- `CHANGES.md` (this file)

### Modified files
- `settings.gradle.kts` (added `:core:ui`)
- `build.gradle.kts` (added `:core:ui` to kover)
- `gradle/libs.versions.toml` (added `androidx.media:media`)
- `app/build.gradle.kts` (added `:core:ui` dep)
- `feature/{auth,catalog,details,library,player,search}/build.gradle.kts`
  (added `:core:ui` dep)
- `auto/build.gradle.kts` (added `androidx.media:media`, testOptions)
- `app/src/main/AndroidManifest.xml` — unchanged but verified
- `app/src/main/java/dev/josu/hypecar/Theme.kt` — now a `HypeTheme` alias
- `app/src/main/java/dev/josu/hypecar/MainActivity.kt` — tokens migration,
  connectivity banner wiring, automotive detection moved to activity level,
  touch-target bumps
- `app/src/main/java/dev/josu/hypecar/AppChromeViewModel.kt` — connectivity
  flow exposed
- `app/src/main/java/dev/josu/hypecar/OfflineSettingsRoute.kt` —
  `StorageLimitPill` removed
- `app/src/test/java/dev/josu/hypecar/AppChromeMetricsTest.kt` — threshold
  updates
- `app/src/test/java/dev/josu/hypecar/AndroidAutoManifestContractTest.kt` —
  added three new test methods
- `core/model/src/main/kotlin/dev/josu/hypecar/core/model/repository/Repositories.kt` —
  added `Connectivity` enum + `ConnectivityRepository` interface
- `core/data/src/main/java/dev/josu/hypecar/core/data/di/DataModule.kt` —
  Hilt binding for connectivity
- `auto/src/main/java/dev/josu/hypecar/auto/HypeMediaIds.kt` — added
  `more` constant
- `auto/src/main/java/dev/josu/hypecar/auto/service/HypeMediaLibraryCallback.kt` —
  major rewrite (4 sections, hints, localised, custom commands, empty states,
  pagination cap, sign-in placeholder with artwork)
- `auto/src/main/java/dev/josu/hypecar/auto/service/HypeMediaLibraryService.kt` —
  `setSessionActivity(pendingIntent)`
- `auto/src/test/kotlin/dev/josu/hypecar/auto/HypeMediaIdsTest.kt` — added
  9 new tests
- `auto/src/test/kotlin/dev/josu/hypecar/auto/service/HypeMediaLibraryCallbackMetadataTest.kt` —
  updated to pass `context = testContext`
- `feature/catalog/src/main/java/dev/josu/hypecar/feature/catalog/CatalogUi.kt` —
  added `onRetry` param to `TrackListContent`, plural stats line,
  externalised "Play" / "Filter"
- `feature/catalog/src/main/java/dev/josu/hypecar/feature/catalog/LatestRoute.kt` —
  wires `onRetry`
- `feature/catalog/src/main/java/dev/josu/hypecar/feature/catalog/PopularRoute.kt` —
  wires `onRetry`
- `feature/catalog/src/main/java/dev/josu/hypecar/feature/catalog/TrackRowUiModel.kt` —
  raw counts instead of pre-formatted string
- `feature/catalog/src/test/java/dev/josu/hypecar/feature/catalog/TrackRowUiModelTest.kt` —
  asserts raw counts
- `feature/catalog/src/main/res/values/strings.xml`, `values-es/strings.xml` —
  added `track_row_stats` plurals + Play / Filter strings
- `feature/details/src/main/java/dev/josu/hypecar/feature/details/UserProfileHeaderUiModel.kt` —
  raw counts
- `feature/details/src/main/java/dev/josu/hypecar/feature/details/DetailsRoutes.kt` —
  formats counts via `pluralStringResource`
- `feature/details/src/test/java/dev/josu/hypecar/feature/details/UserProfileHeaderUiModelTest.kt` —
  updated assertions
- `feature/details/src/main/res/values/strings.xml`, `values-es/strings.xml` —
  added 3 new plurals
- `feature/player/src/main/java/dev/josu/hypecar/feature/player/PlayerScreenUiModel.kt` —
  raw `queueIndex/queueSize`, public `formatMs`
- `feature/player/src/test/java/dev/josu/hypecar/feature/player/PlayerScreenUiModelTest.kt` —
  asserts raw fields
- `feature/player/src/main/java/dev/josu/hypecar/feature/player/PlayerRoute.kt` —
  hide `MoreVert`, scrubber labels track drag, queue position label removed,
  `CompactInfoCard` removed
- `feature/player/src/main/res/values/strings.xml`, `values-es/strings.xml` —
  added up-next strings

## Suggested verification steps

```bash
# Compile
./gradlew assembleDebug

# Run all unit tests (the ones added in this batch are in auto/ and app/)
./gradlew testDebugUnitTest

# Architecture invariants (feature ↔ feature edges)
./gradlew checkArchitecture

# Format / ktlint
./gradlew spotlessApply
```

If the Auto tests fail with a `Context` resolution error, it most likely
means `auto/build.gradle.kts:testOptions { unitTests { isIncludeAndroidResources = true } }`
isn't picking up the new strings.xml — verify the merge against the same
block in `feature/*/build.gradle.kts`.
