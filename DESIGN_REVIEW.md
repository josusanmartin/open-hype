# Hype Car — Design Review

**Date:** 2026-05-11
**Scope:** Phone UI (Jetpack Compose) and Android Auto experience.
**Out of scope (by request):** repository/data layer architecture, build/Gradle layout, networking internals.

> Historical review snapshot. Several issues below have since been fixed during the release-hardening pass; use the current code, tests, and `CHANGES.md` as the source of truth before treating an item as open.

---

## Executive summary

The app has a clear identity — warm cream + orange editorial aesthetic, opinionated typography, thoughtful playback plumbing — and a number of unusually careful technical choices (single shared `ExoPlayer`, OkHttp-backed bitmap loader for AAOS trust-store quirks, source-page-aware queue resolution on the car). The biggest gaps are not bugs but **systemic**: the declared design system is mostly dead because most surfaces inline raw color/typography values, and the Android Auto module is missing the Media3 content-style hints, sign-in intent, and ≤4 top-level structure that Google's Car App guidelines call out as first-class.

If I had to pick five things to ship this quarter, in order:

1. Build a real `core/ui` token module and migrate hex/sp/dp literals to it (also unlocks dark mode and dynamic color).
2. Add Android Auto `CONTENT_STYLE_*` hints, section artwork, and a sign-in `PendingIntent`/`setSessionActivity`.
3. Move from 6 top-level Auto sections to ≤4 (the AAOS guideline) — combine or demote two.
4. Wire the catalog `Retry` button, kill the dead `MoreVert` button, hook the player scrubber time label to the drag.
5. Add a single offline/connectivity banner that the catalog, library, and player can all subscribe to.

---

## What's working well — keep doing this

These are explicit strengths I want to make sure don't get refactored away.

- **Layered automotive detection** (`MainActivity.kt:162-169`, `CatalogUi.kt:1000-1007`, `PlayerRoute.kt:293-301`) and dedicated `CatalogLayoutMetrics.automotive()` / `phone()` factories — the right abstraction even if the locations are scattered.
- **Edge-to-edge** is set up correctly (`MainActivity.kt:91-99`) and the hero header reads `WindowInsets.statusBars` rather than guessing (`CatalogUi.kt:692-737`).
- **Pull-to-refresh + infinite scroll + optimistic favorite with server rollback** in the catalog is solid (`CatalogUi.kt:370-382`, `LatestRoute.kt:66-96`).
- **Player accessibility**: `progressBarRangeInfo` + `setProgress` semantics on the scrubber, dismiss action, `Role.Image`, custom actions (`PlayerRoute.kt:534-545, 751-767`) — by far the strongest a11y surface in the app.
- **`pluralStringResource`** used correctly in `DetailsRoutes.kt:154-155` for follower/track counts.
- **Spanish translations exist** for the strings that *are* externalized (good baseline).
- **Single shared `ExoPlayer` + single `MediaLibrarySession`** between phone and car (`HypePlaybackManager.kt:36`, `HypeMediaLibraryService.kt:33-37`) — avoids the classic double-session race.
- **`MediaLibraryPlaybackServiceStarter.kt:14-22`** correctly uses `startService` and lets Media3 own foreground promotion — comment is preserved for a reason.
- **`OkHttpBitmapLoader` + `CacheBitmapLoader`** work around the AAOS_API_35 trust-store bug — exactly the kind of issue most apps don't notice until users do.
- **`OkHttpBitmapLoader`** performs bounded, cancellable HTTPS artwork fetches for Media3; queue construction and playback resumption keep only the artwork URI so starting audio never waits for an image download.
- **Source-page-aware queue resolution** (`HypeMediaLibraryCallback.kt:221-256`) — tap a track on page 3, re-fetch page 3, and play the queue as the user sees it. This is unusually thoughtful and worth keeping.
- **Favorite custom action requests a secondary action slot with overflow fallback** so it doesn't push skip-next off the car transport bar while still surfacing the heart on supported head units (`HypeMediaLibraryCallback.kt:53-79`).
- **`setHandleAudioBecomingNoisy(true)` + audio attributes** set correctly for Bluetooth/AVRCP (`HypePlaybackManager.kt:97-101`).

---

## Phone UI — issues

### P0 — Critical (ship-blockers or visible bugs)

**1. The design system is declared but dead.**
`Theme.kt:12-24` defines a single `lightColorScheme` (warm cream + orange + near-black), but ~180 `Color(0xFF…)` literals are scattered across nine UI files (PlayerRoute 27, OfflineSettingsRoute 50, LibraryRoute 16, CatalogUi 30, SearchRoute 14, LoginRoute 16, MainActivity 10, Theme 11, DetailsRoutes 6). `MaterialTheme.colorScheme` is referenced almost exclusively for `error` (`CatalogUi.kt:314`) and `surfaceVariant` placeholder (`CatalogUi.kt:476`). The declared palette is essentially decoration. Concrete symptoms: at least **seven different "brand orange" hexes** (`0xFFFF8A3D`, `0xFFFF6A21`, `0xFFD55A20`, `0xFFFF934A`, `0xFFC85F27`, `0xFFFFB07B`, `0xFFFFC4A2`) used interchangeably; `0xFF151211` "dark card surface" appears in five files. Fix: introduce a `core/ui` module with `Brand`/`Surface`/`Accent` token objects and migrate hex literals. Then dark mode and dynamic color become possible.

**2. No dark mode, no dynamic color.**
Zero hits for `darkColorScheme`, `isSystemInDarkTheme`, or `dynamicColor`. `MainActivity.kt:101` always wraps in `AppTheme { MainApp() }`. For a music app where users frequently launch at night or in low light, this is a real gap. Trivial once #1 is fixed.

**3. Catalog `Retry` button is dead in practice.**
`CatalogUi.kt:306-326` renders the error message and a retry button — but `LatestRoute.kt:191-212` and `PopularRoute` never pass `onRetry` to `TrackListBody`. The error UI shows the text without the action. Users are stuck with pull-to-refresh as the only recovery.

**4. `MoreVert` button in the player is a visible no-op.**
`PlayerRoute.kt:461` has `onMore = { /* room for an overflow menu in a future pass */ }` with `contentDescription = "More actions"`. The button is rendered, tappable, screen-reader-announced — and does nothing. Either hide it behind a feature flag or wire it.

**5. There is no offline / connectivity indicator anywhere.**
`DefaultOfflineRepository` exists but the UI never surfaces "you're offline, this list is stale." The catalog/library/details screens show stale cache silently. Only the player snackbar surfaces transient playback errors. For a car app, this is a sharp edge — users will see Favorites and not know whether it's authoritative.

**6. Auth expiration is silent.**
When `UnauthorizedSessionInterceptor` clears the session, `LibraryRoute` quietly flips to the signed-out hero — no snackbar, no toast, no "your session expired" copy. Users see Favorites go empty and assume data loss.

**7. Search/Settings tab tap-on-self is a dead key.**
`ScrollToTopBus.request(route)` fires on same-tab nav tap (`MainActivity.kt:253-256`) but only `latest`, `popular`, and `library` subscribe. Search and Settings ignore it — the user pattern of "tap the tab I'm on to scroll up" breaks on those two routes.

**8. Hardcoded English bypassing the resource system.**
ES translations exist for 7 string XML files, but these surfaces never reach them:
- `LibraryRoute.kt:446-449` — "@username · saved rotation" etc.
- `UserProfileHeaderUiModel.kt:13-17` — "N favorites/followers/following" (also no pluralization).
- `PlayerScreenUiModel.kt:39` — "Queue position N / M".
- `TrackRowUiModel.kt:21` — "reposted Nx".
- `CatalogUi.kt:610, 643, 660` — "Play", "Filter" defaults.
- `LoginRoute.kt:255` — decorative "offline / rotation / synced" text.
- `OfflineSettingsRoute.kt:239` — "v…" version label.

### P1 — High impact

**9. Optimistic favorite logic is copy-pasted three times.**
`LatestRoute.kt:66-96`, `PopularRoute.kt:55-83`, `LibraryRoute.kt:124-152` are byte-for-byte the same. Three independent ViewModels can drift out of sync on a single user action against the same track. Hoist into `MeRepository` (or a `FavoriteSyncDelegate`).

**10. Per-module `*LayoutMetrics` is good in concept, fragmented in practice.**
`AppChromeMetrics`, `CatalogLayoutMetrics`, `PlayerLayoutMetrics` overlap on corner radii, padding tokens, control sizes. None of them speak to `MaterialTheme.shapes` (which is unset). Promote to a shared `core/ui/DesignTokens` so the catalog/player/details all index into the same token table.

**11. Queue UI is missing despite a model for it.**
`PlayerScreenUiModel.queueLabel` is computed but never rendered. There is no up-next list, no reorder, no remove, no "what's next" affordance. For an editorial firehose like Hype Machine — where the queue is the experience — this is a notable feature gap, not just polish.

**12. Player scrubber time label doesn't update during drag.**
`PlayerScrubber` (`PlayerRoute.kt:734-810`) is otherwise excellent (accessibility semantics, 44dp touch target). But the elapsed/remaining text below uses the live `model.progressFraction`, not `selectedProgress`, so the user sees the thumb move while the time digits stay still until release. Bind the text to the dragged value.

**13. No skeleton loaders — single `CircularProgressIndicator` everywhere.**
List-heavy screens (catalog, library, details) all show a centered spinner on first load. Adds perceived latency. Compose Skeletons (shimmer rows) take ~30 lines once tokens exist.

**14. Mini-player has no swipe-up to open.**
Only the chevron icon (and tapping anywhere) opens the player. The pattern users expect — vertical swipe up to expand — is wired in the *full* player for dismiss but not for entry.

**15. Touch targets below 48dp Material guideline.**
Mini-player icon buttons are 42dp on phone, 40dp on Auto (both `MiniPlayerBarScreen`). Chip selectors in `EditorialHeroHeader` (`CatalogUi.kt:806`) are ~32dp tall. TrackRow play `Surface` is ~34dp tall. Bumping these will marginally tighten the layout but eliminates the most common a11y complaint.

**16. Favorite Box is `Modifier.clickable` without `Role.Button`.**
Phone player's heart (`PlayerRoute.kt:642-660`), mini-player cover thumbnail (`MainActivity.kt:401`), chip selectors (`CatalogUi.kt:806`), TrackRow favorite (`494-510`) — all bare `Box.clickable`. No ripple, no role, no `MinimumInteractiveComponentSize`. TalkBack announces them as "double-tap to activate" with no role.

**17. Dead components.**
`CompactInfoCard` (`PlayerRoute.kt:1009-1037`) and `StorageLimitPill` (`OfflineSettingsRoute.kt:545-580`) are defined and never invoked. Delete or wire.

**18. Detail screens (Blog / User / Tag) have no top-app-bar back arrow.**
Phone gesture-back works, but a dedicated detail screen with a hero header reads as "missing chrome" without an explicit back affordance. Three-button-nav users in particular will feel it.

### P2 — Polish

- `Color(0xFF6B5B53)` body stats on `Color(0xFFF9F4EE)` cream is ≈4.0:1 — borderline AA failure for body text. Audit per the new tokens.
- `LoginRoute.kt:60` uses `remember` for password instead of `rememberSaveable`. Security-defensible, but inconsistent with the username field which IS saveable.
- `LoginRoute.kt:255` decorative "offline / rotation / synced" labels have no `contentDescription = null` semantics — TalkBack will announce them as if they're meaningful UI.
- Featured first card hero subtitle in detail routes falls back to `state.tracks.firstOrNull()?.bestThumbnail()` (`DetailsRoutes.kt:159`) — so the same artwork appears in the hero AND the featured card. Double-vision. Use a distinct asset or `surfaceVariant` placeholder.
- TrackRow has multiple independent tappables on one row (cover, title column, source chip, play, favorite). TalkBack sweep order is OK but the play action is not aggregated into a row-level semantic. Consider `Modifier.semantics(mergeDescendants = true) { customActions = listOf(play, favorite) }`.
- `MaterialTheme.shapes` is not set; corner radii are inlined (6, 10, 12, 14, 16, 18, 22, 24, 26, 30dp, `999.dp`). Add `Shapes` tokens.
- `titleLarge.copy(fontWeight = FontWeight.Bold)` appears in `MainActivity.kt:414`, `LibraryRoute.kt:441, 547` — titleLarge is already SemiBold. Pick one.
- Search input lives in the `LazyColumn` header — disappears on scroll. Consider a `stickyHeader` or a collapsing-top-app-bar pattern.
- Login background art (`LoginRoute.kt:222-277`) uses raw `offset(x = …)` which doesn't mirror in RTL.
- Settings has a slider (100–2048 MB) on phone and discrete pills (250/500/1024/2048) on Auto — the two surfaces disagree on continuous vs stepped. Pick one and reuse.

---

## Android Auto — issues

### P0 — Critical (visible to drivers, or guideline violations)

**A1. No `MediaConstants.CONTENT_STYLE_*` hints anywhere.**
Grep across the repo for `CONTENT_STYLE`, `BROWSABLE_HINT`, `PLAYABLE_HINT`, `GRID_ITEM_HINT_VALUE`, `LIST_ITEM_HINT_VALUE` returns zero hits. The car falls back to default rendering for every node. Tracks-with-art get inferred as grid in Media3, but playlist-name nodes have no explicit `CONTENT_STYLE_LIST_ITEM_HINT_VALUE`, and the six section tiles at root have no `CONTENT_STYLE_CATEGORY_LIST_ITEM_HINT_VALUE` to ensure they render as section chips rather than full tiles. This is the single biggest "looks unpolished on the HUD" gap.

**A2. Six top-level sections — guideline is ≤ 4.**
`sectionItems()` (`HypeMediaLibraryCallback.kt:439-446`) returns Latest, Popular, Favorites, Feed, Playlists, History. On most car HUDs this overflows into a "More" menu and loses glanceability. Recommendations:
- Merge **Latest + Popular** into a single "Discover" with a sub-toggle (one click in).
- Move **History** behind a top-row "Recent" header or a Now Playing affordance, not as a peer section.
- Keep **Favorites, Feed, Playlists** as the user's primary sections.

**A3. No sign-in `PendingIntent` / `setSessionActivity` — sign-in tile is a dead end.**
`requireSession()` (`HypeMediaLibraryCallback.kt:364-374`) returns a non-browsable, non-playable `signInPromptItem` titled "Sign in on the phone first." Tapping it does nothing on the car. Google's recommended pattern is to return `LibraryResult.RESULT_ERROR_PERMISSION_DENIED` (or set `BrowserRoot` error extras) with a resolution `PendingIntent` so the car can prompt "look at your phone" and your phone-side `MainActivity` deep-links to the login screen. Zero hits for `setSessionActivity`, `PendingIntent`, `ERROR_RESOLUTION_ACTION_INTENT` anywhere in the codebase.

**A4. `onSearch` is a no-op.**
`HypeMediaLibraryCallback.kt:167-172` returns `LibraryResult.ofVoid()` unconditionally. `onGetSearchResult` (lines 174-189) does work, so Assistant utterances ("Hey Google, play X on Hype Machine") technically resolve, but they hit the network cold every time. Implement `onSearch` to warm the cache and call `notifyChildrenChanged(controller, "search:$query", count, null)` so the result list is ready when `onGetSearchResult` is called.

**A5. Section tiles now embed bitmap artwork, but need real-car regression coverage.**
`HypeMediaLibraryCallback.kt` renders bundled section drawables to PNG artwork bytes before handing them to the media template. This avoids projected Android Auto hosts tinting vector resource URIs into white square tiles. Keep this covered with real-head-unit screenshots because emulator rendering still differs from Hyundai/Kia projection.

**A6. All Auto-facing strings are hardcoded.**
"Latest", "Popular", "Favorites", "Feed", "Playlists", "History", "Sign in on the phone first" subtitle, "Favorite"/"Unfavorite" command labels, fallback "Playlist $id" — all inlined as Kotlin string literals. The phone has `values-es/strings.xml` but Auto strings never reach it. A user with Spanish system locale gets a Spanish phone app and an English car HUD.

### P1 — High impact

**A7. No subtitle data on track media items.**
`HypeMediaLibraryCallback.kt:462-466` sets title, artist, album=postedBy (blog), artworkUri. No `setSubtitle(...)`, no `setDescription`/`setDisplayDescription`, no `setGenre`, no `setReleaseYear`. Cars that surface a third metadata line on the HUD will be blank. The Hype Machine "loved by N" count is invisible on Auto — the editorial signal is lost.

**A8. Custom commands are minimal — only Favorite/Unfavorite.**
The browse and Now Playing custom-layout slots can carry more. Candidates: "Skip blog" (skip all tracks from the same `postedBy`), "Mark as loved" (a stronger signal than favorite — multi-track), "Play more from this blog" (queue the blog's stream as a continuation). Each maps cleanly onto existing repository calls.

**A9. No AAOS (Android Automotive OS) variant.**
`Glob auto/src/main/AndroidManifest.xml` returns "No files found." The `auto` module is currently a plain Kotlin library — no automotive feature declaration, no `<uses-feature android:name="android.hardware.type.automotive" />`, no AAOS-only flavor. Today this app is **phone-projected Android Auto only**. Shipping to the AAOS Play Store would need a distinct flavor (no launcher activity, only the media service, automotive `<uses-feature>`).

**A10. Empty section handling collapses to a blank list.**
`HypeMediaLibraryCallback.kt:207` (`loadChildrenSuspend`) collapses any exception to `emptyList()`. New accounts with 0 favorites see an empty section — no friendly empty-state row. Return a single non-playable item like "No favorites yet — open Hype on your phone to start" with `MEDIA_TYPE_FOLDER_MIXED` and `isPlayable=false`.

**A11. The signed-out and custom-command code paths are untested.**
The Auto callback suites now cover signed-out placeholders, metadata, controller admission, spoofed account-mutation identity, authoritative stream resolution, paged search, and bounded/cancellable artwork fetching. The remaining high-value gaps are the asynchronous Favorite toggle rollback and the player listener during real track transitions.

**A12. `HypeMediaIdsTest` is missing parser edge cases.**
URL-encoded reserved characters (`&`, `=`, `?`, `%`, unicode), malformed input, `parsePlaylistId` (implemented but uncovered), and `parseTrackSourcePage` with negative-value clamping. Easy add and these IDs are the contract between car and phone.

**A13. Pagination is unbounded.**
`DefaultPageSize = 20, MaxPageSize = 30` (`HypeMediaLibraryCallback.kt:51-52, 421-428`) — fine — but there's no cap on total scrollback. On long lists in a moving car, infinite scroll is a distraction-safety concern. Consider capping at e.g. 200 items per section.

### P2 — Polish

- `MEDIA_PLAY_FROM_SEARCH` intent filter is declared (`AndroidManifest.xml:65`) but Media3's `Callback` uses `onSearch` as the canonical hook. Verify the Assistant path on a physical HMU — your test coverage doesn't include it.
- `AndroidAutoManifestContractTest` doesn't verify `foregroundServiceType="mediaPlayback"`, that the service target class is correct, or that `MEDIA_PLAY_FROM_SEARCH` exists in the filter. Add assertions.
- Sign-in placeholder subtitle ("Your favorites, feed and playlists need a Hype Machine session.") is ~60 characters — fits most car rows but may truncate on the smallest HMUs. Shorter copy welcome.
- Service comment-as-contract on `MediaLibraryPlaybackServiceStarter.kt:14-22` is great — promote it to module-level KDoc so it survives refactors.

---

## Suggested refactors (bigger swings)

These are larger but unlock the items above.

**R1. Introduce `core/ui` with explicit design tokens.**
A single module that exports:
- `BrandColors` (orange variants, cream surfaces, dark card surface, error)
- `ChromeColors` (mini-player, nav bar, status bar scrims)
- `Radii` (xs/sm/md/lg/pill)
- `Spacing` (4/8/12/16/24/32 — concrete tokens, no raw dp)
- `Typography` extensions (you already have a good base in `Theme.kt:26-77`; just stop overriding inline)
- `AutoTokens` (a parallel set sized for car HUDs, replacing the scattered `automotive()` factories)

Migrate `MainActivity`, `CatalogUi`, `PlayerRoute`, `LibraryRoute`, `SearchRoute`, `DetailsRoutes`, `LoginRoute`, `OfflineSettingsRoute` to consume from this module. After migration, adding `darkColorScheme` is a 20-line patch.

**R2. Hoist optimistic-favorite logic into `MeRepository` or a delegate.**
`LatestRoute`, `PopularRoute`, and `LibraryRoute` should call `meRepository.toggleFavoriteOptimistic(trackId)` and observe a single state flow. Eliminates the 3× copy and gives the player a single source of truth so the heart icon doesn't drift.

**R3. Build a `ConnectivityIndicator` composable + `ConnectivityRepository`.**
One observable Flow<Connectivity> consumed by a top-of-screen banner. Phone Top app bar and car HUD subtitle can both subscribe. Closes the "is this stale?" gap on every list screen.

**R4. Promote `auto/` to a proper Android Auto module with manifest, content-style hints, and an AAOS flavor.**
- Add `auto/src/main/AndroidManifest.xml` with `<uses-feature android:name="android.hardware.type.automotive" android:required="false" />`.
- Add a `productFlavors { automotive { } }` block in app/build.gradle.kts so an AAOS app variant can be built without the launcher activity.
- Centralize `MediaConstants` content-style hints in an `AutoBrowseHints.kt` helper.
- Localize Auto strings via `R.string.auto_section_*` and read them via `Application.getString(...)` in the callback (Media3's callback runs with a Context).

**R5. Pull the catalog `Retry` callback through.**
`LatestRoute`, `PopularRoute`, and (for parity) `LibraryRoute`/`SearchRoute` should pass `onRetry = viewModel::reload` to `TrackListBody`. This is small, but it's the single biggest "I see a broken state" UX win.

---

## File reference appendix

**Phone UI**
- Theme & nav: `app/src/main/java/dev/josu/hypecar/Theme.kt`, `app/src/main/java/dev/josu/hypecar/MainActivity.kt`, `app/src/main/java/dev/josu/hypecar/MiniPlayerUiState.kt`, `app/src/main/java/dev/josu/hypecar/AppChromeViewModel.kt`
- Auth: `feature/auth/src/main/java/dev/josu/hypecar/feature/auth/LoginRoute.kt`
- Catalog: `feature/catalog/src/main/java/dev/josu/hypecar/feature/catalog/CatalogUi.kt`, `TrackRowUiModel.kt`
- Library: `feature/library/src/main/java/dev/josu/hypecar/feature/library/LibraryRoute.kt`
- Search: `feature/search/src/main/java/dev/josu/hypecar/feature/search/SearchRoute.kt`
- Details: `feature/details/src/main/java/dev/josu/hypecar/feature/details/DetailsRoutes.kt`, `UserProfileHeaderUiModel.kt`
- Player: `feature/player/src/main/java/dev/josu/hypecar/feature/player/PlayerRoute.kt`, `PlayerScreenUiModel.kt`, `PlayerSwipeDecision.kt`
- Settings: `app/src/main/java/dev/josu/hypecar/OfflineSettingsRoute.kt`

**Android Auto**
- Media IDs: `auto/src/main/java/dev/josu/hypecar/auto/HypeMediaIds.kt`
- Callback / browse tree: `auto/src/main/java/dev/josu/hypecar/auto/service/HypeMediaLibraryCallback.kt`
- Service: `auto/src/main/java/dev/josu/hypecar/auto/service/HypeMediaLibraryService.kt`
- Service starter: `app/src/main/java/dev/josu/hypecar/MediaLibraryPlaybackServiceStarter.kt`
- Playback engine: `core/playback/.../HypePlaybackManager.kt`
- Bitmap loader: `auto/src/main/java/dev/josu/hypecar/auto/service/OkHttpBitmapLoader.kt`
- Manifest entries: `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/automotive_app_desc.xml`
- Tests: `app/src/test/java/dev/josu/hypecar/AndroidAutoManifestContractTest.kt`, `auto/src/test/.../HypeMediaIdsTest.kt`, `auto/src/test/.../HypeMediaLibraryCallbackMetadataTest.kt`
