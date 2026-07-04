package dev.josu.hypecar.auto.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.josu.hypecar.auto.HypeMediaIds
import dev.josu.hypecar.auto.R
import dev.josu.hypecar.core.data.repository.FavoriteEdit
import dev.josu.hypecar.core.data.repository.FavoriteSyncManager
import dev.josu.hypecar.core.model.MediaItemExtras
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.SearchQuery
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.AuthRepository
import dev.josu.hypecar.core.model.repository.CatalogRepository
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.OfflineRepository
import dev.josu.hypecar.core.model.repository.SearchRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Drives the Android Auto / AAOS browse tree and Now Playing custom-layout.
 *
 * Design choices that aren't obvious from the call sites:
 *
 *  - **Top-level structure: 4 sections, not 6.** Google's Android Auto guidance
 *    recommends ≤ 4 primary tabs on the HUD. We expose Latest, Popular,
 *    Favorites, and a "More" umbrella; Feed/Playlists/History live one level
 *    deeper under More. Each top-level tile carries its own drawable so the
 *    car renders an editorial grid instead of blank rectangles.
 *
 *  - **Content-style hints** (see [AutoBrowseHints]) are applied to every
 *    browsable parent so car hosts render compact, glanceable track rows
 *    instead of falling back to oversized artwork grids or default heuristics.
 *
 *  - **Localization.** All Auto-facing copy is resolved via [Context.getString]
 *    against `auto/src/main/res/values/strings.xml` (and `values-es/`); nothing
 *    is hardcoded in this class.
 *
 *  - **Sign-in flow.** [HypeMediaLibraryService] sets a session-level
 *    `PendingIntent` so the car HUD can surface "open on phone". The browse
 *    placeholder shown for authenticated sections also carries the
 *    `ic_auto_signin` artwork so the tile is recognisable.
 *
 *  - **Pagination cap.** Total scrollback per section is bounded so the car
 *    doesn't end up showing thousands of rows on a long trip. See [MaxPages].
 *
 *  - **Custom Now Playing actions.** Favorite/Unfavorite is the only custom
 *    action exposed to the car host. Previous/play-next remain standard player
 *    commands; favorite is advertised through media button preferences because
 *    projected Android Auto renders that list for the player surface. The car
 *    custom layout stays empty to avoid duplicate custom actions on hosts that
 *    merge both lists.
 *
 *  - **Local section artwork.** Root section icons are embedded as PNG bytes
 *    rather than `android.resource://` vector URIs. Some projected Android Auto
 *    hosts tint local vector artwork as monochrome action icons, which turns
 *    colorful section logos into white squares.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class HypeMediaLibraryCallback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogRepository: CatalogRepository,
    private val meRepository: MeRepository,
    private val searchRepository: SearchRepository,
    private val offlineRepository: OfflineRepository,
    private val authRepository: AuthRepository,
    private val favoriteSyncManager: FavoriteSyncManager,
    private val okHttpClient: okhttp3.OkHttpClient,
) : MediaLibrarySession.Callback {
    private companion object {
        const val DefaultPageSize = 20
        const val MaxPageSize = 30

        /** Hard cap on how many pages a single section can scroll on Auto, to bound distraction. */
        const val MaxPages = 10

        const val ActionToggleFavorite = "dev.josu.hypecar.auto.action.TOGGLE_FAVORITE"

        // Shared with the phone playback engine (core/model MediaItemExtras)
        // so items queued from either surface carry the same metadata keys.
        const val ExtraIsLoved = MediaItemExtras.IsLoved
        const val ExtraBlogId = MediaItemExtras.BlogId
        const val ExtraBlogName = MediaItemExtras.BlogName
        const val ExtraLovedCount = MediaItemExtras.LovedCount

        const val InlineArtworkMaxSizePx = 512
        const val InlineArtworkJpegQuality = 86
        const val InlineArtworkCacheBytes = 4 * 1024 * 1024
    }

    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drawableArtworkCache = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()
    private val inlineArtworkCache = object : LruCache<String, ByteArray>(InlineArtworkCacheBytes) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val favoriteStateOverrides = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Tracks the favorite state for the currently-playing track so the icon flips after a tap. */
    private val likedNowPlaying = java.util.concurrent.atomic.AtomicBoolean(false)
    private val favoriteToggleGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val loved = favoriteStateFor(mediaItem)
            favoriteToggleGeneration.incrementAndGet()
            likedNowPlaying.set(loved)
            currentSession?.let { session ->
                session.updateAllNowPlayingButtons(loved)
            }
        }
    }
    private var currentSession: MediaLibrarySession? = null

    init {
        // Fold favorite edits from every other surface (list screens, the
        // player screen) into this session so the car heart and the per-track
        // overrides never drift from what the user did on the phone.
        callbackScope.launch {
            favoriteSyncManager.edits.collect { edit ->
                rememberFavoriteState(edit.trackId, edit.isLoved)
                withContext(Dispatchers.Main.immediate) {
                    val session = currentSession ?: return@withContext
                    val playingId = session.player.currentMediaItem
                        ?.let { HypeMediaIds.parseTrackId(it.mediaId) }
                    if (playingId == edit.trackId && likedNowPlaying.get() != edit.isLoved) {
                        likedNowPlaying.set(edit.isLoved)
                        session.updateAllNowPlayingButtons(edit.isLoved)
                    }
                }
            }
        }
        // A signed-out user has no favorites; stale overrides from the previous
        // account must not shadow the fresh metadata of the next one.
        callbackScope.launch {
            authRepository.session.collect { session ->
                if (session == null) favoriteStateOverrides.clear()
            }
        }
    }

    private fun previousButton(): CommandButton =
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName(context.getString(R.string.auto_action_previous))
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
            .setSlots(CommandButton.SLOT_BACK)
            .build()

    private fun nextButton(): CommandButton =
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName(context.getString(R.string.auto_action_next))
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()

    private fun favoriteButtonBuilder(filled: Boolean): CommandButton.Builder =
        CommandButton.Builder(if (filled) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(context.getString(if (filled) R.string.auto_action_unfavorite else R.string.auto_action_favorite))
            .setSessionCommand(SessionCommand(ActionToggleFavorite, Bundle.EMPTY))

    @Suppress("UNUSED_PARAMETER")
    private fun buildNowPlayingLayout(loved: Boolean): List<CommandButton> = emptyList()

    private fun buildNotificationCustomLayout(loved: Boolean): List<CommandButton> = listOf(
        favoriteButtonBuilder(loved)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private fun buildMediaButtonPreferences(loved: Boolean): List<CommandButton> = listOf(
        previousButton(),
        nextButton(),
        // In Media3 1.5+ non-empty media button preferences supersede the
        // custom layout on the notification controller, so the heart must
        // live here or it never renders on the phone notification.
        favoriteButtonBuilder(loved)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private fun buildCarMediaButtonPreferences(loved: Boolean): List<CommandButton> = listOf(
        previousButton(),
        favoriteButtonBuilder(loved)
            .setSlots(
                CommandButton.SLOT_BACK_SECONDARY,
                CommandButton.SLOT_FORWARD_SECONDARY,
                CommandButton.SLOT_OVERFLOW,
            )
            .build(),
        nextButton(),
    )

    private fun MediaLibrarySession.updateNowPlayingButtons(
        controller: MediaSession.ControllerInfo?,
        loved: Boolean,
    ) {
        if (controller != null) {
            val customLayout = if (controller == mediaNotificationControllerInfo) {
                buildNotificationCustomLayout(loved)
            } else {
                buildNowPlayingLayout(loved)
            }
            val mediaButtons = if (controller == mediaNotificationControllerInfo) {
                buildMediaButtonPreferences(loved)
            } else {
                buildCarMediaButtonPreferences(loved)
            }
            setCustomLayout(controller, customLayout)
            setMediaButtonPreferences(controller, mediaButtons)
        } else {
            setCustomLayout(buildNowPlayingLayout(loved))
            setMediaButtonPreferences(buildCarMediaButtonPreferences(loved))
        }
    }

    private fun MediaLibrarySession.updateAllNowPlayingButtons(loved: Boolean) {
        setCustomLayout(buildNowPlayingLayout(loved))
        setMediaButtonPreferences(buildCarMediaButtonPreferences(loved))
        connectedControllers.forEach { controller ->
            updateNowPlayingButtons(controller, loved)
        }
    }

    private fun favoriteStateFor(mediaItem: MediaItem?): Boolean {
        if (mediaItem == null) {
            return false
        }
        val fromMetadata = mediaItem.mediaMetadata.extras?.getBoolean(ExtraIsLoved, false) ?: false
        val trackId = HypeMediaIds.parseTrackId(mediaItem.mediaId) ?: return fromMetadata
        val override = favoriteStateOverrides[trackId] ?: return fromMetadata
        if (override == fromMetadata) {
            // Fresh metadata agrees — the override served its purpose. Dropping
            // it lets future server-side changes (unfavorite on the website,
            // another device) win again instead of being shadowed forever.
            favoriteStateOverrides.remove(trackId)
        }
        return override
    }

    private fun MediaSession.applyFavoriteState(trackId: String, loved: Boolean) {
        likedNowPlaying.set(loved)
        rememberFavoriteState(trackId, loved)
        if (this is MediaLibrarySession) {
            updateAllNowPlayingButtons(loved)
        }
    }

    private fun rememberFavoriteState(trackId: String, loved: Boolean) {
        favoriteStateOverrides[trackId] = loved
    }

    /**
     * Controllers allowed to invoke account-mutating custom commands (the
     * favorite toggle). The service must stay exported for Auto/Assistant, so
     * any co-installed app can connect and control playback — but only known
     * system surfaces (and this app itself) may silently change the user's
     * Hype Machine favorites.
     */
    private val trustedControllerPackages = setOf(
        "com.google.android.projection.gearhead", // projected Android Auto
        "com.google.android.googlequicksearchbox", // Assistant
        "com.android.systemui",
        "com.android.bluetooth",
        "com.android.car.media", // AAOS Media Center
        "com.android.car.carlauncher",
    )

    private fun MediaSession.ControllerInfo.isTrustedForAccountActions(session: MediaSession): Boolean =
        packageName == context.packageName ||
            packageName in trustedControllerPackages ||
            (session is MediaLibrarySession && this == session.mediaNotificationControllerInfo)

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ConnectionResult {
        if (currentSession == null && session is MediaLibrarySession) {
            currentSession = session
            session.player.addListener(playerListener)
            // The shared player may already be mid-song (started from the
            // phone before the car connected); seed the heart from it instead
            // of advertising "unfilled" until the next track change. onConnect
            // runs on the application thread, so the player read is safe.
            likedNowPlaying.set(favoriteStateFor(session.player.currentMediaItem))
        }
        val sessionCommands = ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
            .apply {
                if (controller.isTrustedForAccountActions(session)) {
                    add(SessionCommand(ActionToggleFavorite, Bundle.EMPTY))
                }
            }
            .build()
        return ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(ConnectionResult.DEFAULT_PLAYER_COMMANDS)
            .setCustomLayout(
                ImmutableList.copyOf(
                    if (session is MediaLibrarySession && controller == session.mediaNotificationControllerInfo) {
                        buildNotificationCustomLayout(likedNowPlaying.get())
                    } else {
                        buildNowPlayingLayout(likedNowPlaying.get())
                    },
                ),
            )
            .setMediaButtonPreferences(
                ImmutableList.copyOf(
                    if (session is MediaLibrarySession && controller == session.mediaNotificationControllerInfo) {
                        buildMediaButtonPreferences(likedNowPlaying.get())
                    } else {
                        buildCarMediaButtonPreferences(likedNowPlaying.get())
                    },
                ),
            )
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> = when (customCommand.customAction) {
        ActionToggleFavorite -> handleToggleFavorite(session, controller)
        else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
    }

    private fun handleToggleFavorite(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<SessionResult> {
        // onCustomCommand runs on the application thread — capture everything
        // the IO continuation needs from the player here, up front.
        val current = session.player.currentMediaItem
            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        val currentMediaId = current.mediaId
        val trackId = HypeMediaIds.parseTrackId(currentMediaId)
            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        val original = likedNowPlaying.get()
        val optimistic = !original
        val generation = favoriteToggleGeneration.incrementAndGet()
        session.applyFavoriteState(trackId, optimistic)
        return callbackScope.future {
            // Broadcast so phone lists and the player screen flip immediately.
            favoriteSyncManager.publish(
                FavoriteEdit(trackId, optimistic, if (optimistic) 1 else -1),
            )
            val result = resolveAutoFavoriteToggle(
                meRepository = meRepository,
                trackId = trackId,
                originalLoved = original,
            )
            val resolved = result.confirmedLoved
            // Always reconcile the per-track truth — even if the player moved
            // on mid-flight, a failed toggle must not leave the optimistic
            // value pinned in the overrides.
            rememberFavoriteState(trackId, resolved)
            if (resolved != optimistic) {
                favoriteSyncManager.publish(
                    FavoriteEdit(trackId, resolved, if (optimistic) -1 else 1),
                )
                // Player and session must only be touched on the application
                // thread; Media3 throws on cross-thread access.
                withContext(Dispatchers.Main.immediate) {
                    if (
                        favoriteToggleGeneration.get() == generation &&
                        session.player.currentMediaItem?.mediaId == currentMediaId
                    ) {
                        session.applyFavoriteState(trackId, resolved)
                    }
                }
            }
            result.sessionResult
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            LibraryResult.ofItem(
                rootItem(),
                // Legacy-stub hosts (projected AA, AAOS Media Center) read
                // content-style hints from the ROOT extras, not from children
                // results — echoing the client's params back here left the
                // grid/list styling invisible to the primary hosts.
                paramsWithHintsFor(HypeMediaIds.root, params),
            ),
        )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        callbackScope.future {
            loadChildrenResultSuspend(parentId, page, pageSize, params)
        }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        callbackScope.future {
            loadItemResultSuspend(mediaId)
        }

    /**
     * Warms the search cache and notifies the controller that
     * `search:<query>` children are ready. The car then fetches them via
     * [onGetSearchResult] which serves the cached result list.
     *
     * Without this implementation (the previous code returned `LibraryResult.ofVoid()`),
     * Assistant voice search would silently delay until [onGetSearchResult]
     * was reached cold.
     */
    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = callbackScope.future {
        val firstPage = runSuspendCatchingPreservingCancellation {
            searchRepository.searchTracks(SearchQuery(query), page = 1, count = DefaultPageSize).size
        }.getOrDefault(0)
        // Hosts trust this count for paging. A full first page means more
        // results likely exist, so report the pageable ceiling instead of
        // capping every search at the first 20.
        val reportedCount = if (firstPage >= DefaultPageSize) DefaultPageSize * MaxPages else firstPage
        session.notifySearchResultChanged(browser, query, reportedCount, params)
        LibraryResult.ofVoid(params)
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        callbackScope.future {
            LibraryResult.ofItemList(
                ImmutableList.copyOf(
                    loadSearchResultsSuspend(query, page, pageSize),
                ),
                searchParamsWithHints(params),
            )
        }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> =
        callbackScope.future { resolveMediaItemsSuspend(mediaItems) }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> =
        callbackScope.future { resolveMediaItemsWithStartPositionSuspend(mediaItems, startIndex, startPositionMs) }

    /**
     * System UI's "recently played" resumption tile and Bluetooth play after
     * process death land here; Media3's default returns a failed future and
     * nothing plays. Rebuild a queue from the local listening history.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaItemsWithStartPosition> = callbackScope.future {
        val recent = runSuspendCatchingPreservingCancellation {
            meRepository.history(page = 1, count = MaxPageSize)
        }.getOrDefault(emptyList())
        check(recent.isNotEmpty()) { "No local playback history to resume from" }
        val items = recent
            .map { it.toPlayableItem(HypeMediaIds.history, sourcePage = 0, sourcePageSize = MaxPageSize) }
            .toMutableList()
            .also { it[0] = withInlineArtwork(it[0]) }
        MediaItemsWithStartPosition(items, 0, 0L)
    }

    private suspend fun loadChildrenSuspend(parentId: String, page: Int, pageSize: Int): List<MediaItem> =
        runSuspendCatchingPreservingCancellation {
            loadChildrenInternalSuspend(parentId, page, pageSize)
        }.getOrDefault(emptyList())

    private suspend fun resolveMediaItemsSuspend(mediaItems: List<MediaItem>): List<MediaItem> =
        mediaItems.map { mediaItem ->
            if (mediaItem.localConfiguration != null) {
                mediaItem
            } else {
                loadPlayableTrackItem(mediaItem.mediaId) ?: mediaItem
            }
        }

    private suspend fun resolveMediaItemsWithStartPositionSuspend(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): MediaItemsWithStartPosition {
        // Assistant voice search ("Play X on Hype Machine") arrives as a
        // single URI-less item with an empty mediaId and the spoken query in
        // requestMetadata — resolve it to a real queue instead of handing
        // ExoPlayer an unplayable stub. An empty query ("Play Hype Machine")
        // falls back to the Popular section.
        mediaItems.singleOrNull()?.let { selectedItem ->
            if (selectedItem.mediaId.isBlank() && selectedItem.localConfiguration == null) {
                val voiceQueue = loadVoiceRequestQueue(selectedItem.requestMetadata.searchQuery)
                if (voiceQueue.isNotEmpty()) {
                    val withArtwork = voiceQueue.toMutableList().also {
                        it[0] = withInlineArtwork(it[0])
                    }
                    return MediaItemsWithStartPosition(withArtwork, 0, startPositionMs)
                }
            }
        }
        mediaItems.singleOrNull()?.let { selectedItem ->
            val selectedTrackId = HypeMediaIds.parseTrackId(selectedItem.mediaId)
            val sourceId = HypeMediaIds.parseTrackSourceId(selectedItem.mediaId)
            val sourcePage = HypeMediaIds.parseTrackSourcePage(selectedItem.mediaId)
            // Rebuild with the same page size the host browsed with — a
            // different size shifts page boundaries and the tapped track
            // wouldn't be found on its page, degrading to a one-track queue.
            val sourcePageSize = HypeMediaIds.parseTrackSourcePageSize(selectedItem.mediaId)
                .takeIf { it > 0 } ?: MaxPageSize
            if (selectedTrackId != null && sourceId != null) {
                val sourceQueue = loadChildrenSuspend(sourceId, page = sourcePage, pageSize = sourcePageSize)
                val selectedIndex = sourceQueue.indexOfFirst { item ->
                    HypeMediaIds.parseTrackId(item.mediaId) == selectedTrackId
                }
                if (selectedIndex >= 0) {
                    val withArtwork = sourceQueue.toMutableList().also {
                        it[selectedIndex] = withInlineArtwork(it[selectedIndex])
                    }
                    return MediaItemsWithStartPosition(withArtwork, selectedIndex, startPositionMs)
                }
            }
        }

        val resolvedItems = resolveMediaItemsSuspend(mediaItems).toMutableList()
        val resolvedStartIndex = if (resolvedItems.isEmpty()) {
            0
        } else {
            startIndex.coerceIn(0, resolvedItems.lastIndex)
        }
        if (resolvedItems.isNotEmpty()) {
            // Prefetch artwork only for the item that's about to play; the rest
            // stay URI-only and the session's BitmapLoader fetches them when
            // they become the current item.
            resolvedItems[resolvedStartIndex] = withInlineArtwork(resolvedItems[resolvedStartIndex])
        }
        return MediaItemsWithStartPosition(resolvedItems, resolvedStartIndex, startPositionMs)
    }

    /**
     * Pre-fetches the artwork URI via OkHttp and embeds optimized bytes inline
     * as `MediaMetadata.artworkData` so AAOS Now Playing does not have to do its
     * own HTTPS fetch. The bytes are downsampled and cached because Android Auto
     * hosts pass metadata over Binder; full-resolution artwork makes player
     * open/track changes noticeably slower on real head units.
     */
    private suspend fun withInlineArtwork(item: MediaItem): MediaItem {
        val uri = item.mediaMetadata.artworkUri ?: return item
        val cacheKey = uri.toString()
        val bytes = inlineArtworkCache.get(cacheKey) ?: runSuspendCatchingPreservingCancellation {
            val request = okhttp3.Request.Builder().url(cacheKey).build()
            okHttpClient.newCall(request).execute().use { resp ->
                val source = resp.body?.bytes() ?: return@use null
                optimizedInlineArtworkData(source)
            }
        }.getOrNull()?.also { inlineArtworkCache.put(cacheKey, it) } ?: return item
        val updated = item.mediaMetadata.buildUpon()
            .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        return item.buildUpon().setMediaMetadata(updated).build()
    }

    private fun optimizedInlineArtworkData(source: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return source
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, InlineArtworkMaxSizePx)
        }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: return source
        var scaled: Bitmap? = null
        return try {
            val maxSide = maxOf(decoded.width, decoded.height)
            val bitmap = if (maxSide > InlineArtworkMaxSizePx) {
                val scale = InlineArtworkMaxSizePx.toFloat() / maxSide
                scaled = Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).roundToInt().coerceAtLeast(1),
                    (decoded.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
                scaled
            } else {
                decoded
            } ?: decoded
            ByteArrayOutputStream().use { output ->
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, InlineArtworkJpegQuality, output)) {
                    output.toByteArray()
                } else {
                    source
                }
            }
        } finally {
            scaled?.recycle()
            decoded.recycle()
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var inSampleSize = 1
        val maxSide = maxOf(width, height)
        while (maxSide / inSampleSize > maxSize * 2) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    // Reflection-driven tests call this by name; see the note on loadChildren.
    @Suppress("unused")
    private fun withInlineArtworkForTests(item: MediaItem): MediaItem =
        kotlinx.coroutines.runBlocking { withInlineArtwork(item) }

    private suspend fun loadPlaylistItem(mediaId: String): MediaItem? =
        HypeMediaIds.parsePlaylistId(mediaId)?.let { playlistId ->
            val title = runSuspendCatchingPreservingCancellation {
                meRepository.playlistNames().firstOrNull { it.id == playlistId }?.name
            }.getOrNull() ?: context.getString(R.string.auto_playlist_fallback_name, playlistId)
            browsableItem(HypeMediaIds.playlist(playlistId), title)
        }

    private suspend fun loadPlayableTrackItem(mediaId: String): MediaItem? =
        mediaId.resolvableTrackId()?.let { trackId ->
            runSuspendCatchingPreservingCancellation {
                catalogRepository.track(trackId).toPlayableItem()
            }.getOrNull()
        }

    /** Builds the playback queue for a voice request: search results, or Popular when no query was spoken. */
    private suspend fun loadVoiceRequestQueue(searchQuery: String?): List<MediaItem> =
        if (searchQuery.isNullOrBlank()) {
            runSuspendCatchingPreservingCancellation {
                catalogRepository.popular(page = 1, count = MaxPageSize)
                    .map { it.toPlayableItem(HypeMediaIds.popular, sourcePage = 0) }
            }.getOrDefault(emptyList())
        } else {
            loadSearchResultsSuspend(searchQuery, page = 0, pageSize = MaxPageSize)
        }

    private suspend fun loadSearchResultsSuspend(query: String, page: Int, pageSize: Int): List<MediaItem> =
        runSuspendCatchingPreservingCancellation {
            val sourceId = HypeMediaIds.search(query)
            searchRepository.searchTracks(
                SearchQuery(query),
                page = page.toApiPage(),
                count = pageSize.sanitizedPageSize(),
            ).map { it.toPlayableItem(sourceId) }
        }.getOrDefault(emptyList())

    /**
     * Like [LibraryParams] for search but with our content-style hint applied
     * so the car renders search results as compact track rows (artwork + title +
     * artist) rather than a host-specific grid/default layout.
     */
    private fun searchParamsWithHints(
        original: MediaLibraryService.LibraryParams?,
    ): MediaLibraryService.LibraryParams =
        MediaLibraryService.LibraryParams.Builder()
            .setExtras(
                Bundle().apply {
                    putAll(AutoBrowseHints.parentHints(AutoBrowseHints.ChildStyle.LIST_BROWSABLE))
                    original?.extras?.let { putAll(it) }
                },
            )
            .build()

    private suspend fun loadChildrenResultSuspend(
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): LibraryResult<ImmutableList<MediaItem>> {
        // Hard cap on per-section paging to bound driver distraction.
        if (page >= MaxPages) {
            return LibraryResult.ofItemList(ImmutableList.of(), params)
        }
        return runSuspendCatchingPreservingCancellation {
            ImmutableList.copyOf(loadChildrenInternalSuspend(parentId, page, pageSize))
        }.fold(
            onSuccess = { items ->
                LibraryResult.ofItemList(items, paramsWithHintsFor(parentId, params))
            },
            onFailure = { LibraryResult.ofError(SessionError.ERROR_IO) },
        )
    }

    /**
     * Stamps the right content-style hints onto the [LibraryParams] for a given
     * browse node, so the car's renderer knows whether to show grid tiles or
     * compact list rows. Root and More → category list. Playlists and playable
     * track sections → compact list rows. Search results → compact rows
     * (applied separately).
     */
    private fun paramsWithHintsFor(
        parentId: String,
        original: MediaLibraryService.LibraryParams?,
    ): MediaLibraryService.LibraryParams {
        val childStyle = when (parentId) {
            HypeMediaIds.root -> AutoBrowseHints.ChildStyle.CATEGORY_LIST
            HypeMediaIds.more -> AutoBrowseHints.ChildStyle.CATEGORY_LIST
            else -> AutoBrowseHints.ChildStyle.LIST_BROWSABLE
        }
        return MediaLibraryService.LibraryParams.Builder()
            .setExtras(
                Bundle().apply {
                    putAll(AutoBrowseHints.parentHints(childStyle))
                    original?.extras?.let { putAll(it) }
                },
            )
            .build()
    }

    private suspend fun loadItemResultSuspend(mediaId: String): LibraryResult<MediaItem> =
        runSuspendCatchingPreservingCancellation { loadItemInternalSuspend(mediaId) }
            .fold(
                onSuccess = { item ->
                    if (item == null) {
                        LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    } else {
                        LibraryResult.ofItem(item, null)
                    }
                },
                onFailure = { LibraryResult.ofError(SessionError.ERROR_IO) },
            )

    private suspend fun loadChildrenInternalSuspend(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val count = pageSize.sanitizedPageSize()
        val apiPage = page.toApiPage()
        val sourcePage = page.coerceAtLeast(0)
        return when (parentId) {
            HypeMediaIds.root -> sectionItems()
            HypeMediaIds.more -> moreSectionItems()
            HypeMediaIds.latest -> catalogRepository.latest(page = apiPage, count = count).map { it.toPlayableItem(parentId, sourcePage, count) }
            HypeMediaIds.popular -> catalogRepository.popular(page = apiPage, count = count).map { it.toPlayableItem(parentId, sourcePage, count) }
            HypeMediaIds.favorites -> requireSession(parentId) {
                val items = meRepository.favorites(page = apiPage, count = count).map { it.toPlayableItem(parentId, sourcePage, count) }
                items.ifEmptyOnFirstPage(page) { emptyStateItem(parentId, R.string.auto_empty_favorites_title, R.string.auto_empty_favorites_subtitle) }
            }
            HypeMediaIds.feed -> requireSession(parentId) {
                val items = meRepository.feed(page = apiPage, count = count).map { it.track.toPlayableItem(parentId, sourcePage, count) }
                items.ifEmptyOnFirstPage(page) { emptyStateItem(parentId, R.string.auto_empty_feed_title, R.string.auto_empty_feed_subtitle) }
            }
            HypeMediaIds.playlists -> requireSession(parentId) {
                // playlistNames() is unpaged; slice it ourselves so paginating
                // hosts don't receive the whole list duplicated on every page.
                val items = meRepository.playlistNames()
                    .drop(sourcePage * count)
                    .take(count)
                    .map { it.toBrowsableItem() }
                items.ifEmptyOnFirstPage(page) { emptyStateItem(parentId, R.string.auto_empty_playlists_title, R.string.auto_empty_playlists_subtitle) }
            }
            HypeMediaIds.history -> {
                val items = meRepository.history(page = apiPage, count = count).map { it.toPlayableItem(parentId, sourcePage, count) }
                items.ifEmptyOnFirstPage(page) { emptyStateItem(parentId, R.string.auto_empty_history_title, R.string.auto_empty_history_subtitle) }
            }
            else -> HypeMediaIds.parsePlaylistId(parentId)?.let { playlistId ->
                requireSession(parentId) {
                    val items = meRepository.playlist(playlistId, page = apiPage, count = count).map { it.toPlayableItem(parentId, sourcePage, count) }
                    items.ifEmptyOnFirstPage(page) { emptyStateItem(parentId, R.string.auto_empty_generic_title, null) }
                }
            } ?: HypeMediaIds.parseSearchQuery(parentId)?.let { query ->
                searchRepository.searchTracks(
                    SearchQuery(query),
                    page = apiPage,
                    count = count,
                ).map { it.toPlayableItem(parentId, sourcePage, count) }
            } ?: emptyList()
        }
    }

    /** Wraps a load with a single placeholder when the result is empty on the first page. */
    private fun List<MediaItem>.ifEmptyOnFirstPage(
        page: Int,
        builder: () -> MediaItem,
    ): List<MediaItem> = if (isEmpty() && page <= 0) listOf(builder()) else this

    /**
     * Returns the result of [loadSignedIn] if a session exists; otherwise a single
     * non-playable placeholder MediaItem so the templated UI shows a friendly
     * "Sign in on the phone first" message instead of the system error screen.
     */
    private suspend fun requireSession(
        parentId: String,
        loadSignedIn: suspend () -> List<MediaItem>,
    ): List<MediaItem> {
        val session = authRepository.session.first()
        return if (session == null) {
            listOf(signInPromptItem(parentId))
        } else {
            runSuspendCatchingPreservingCancellation {
                loadSignedIn()
            }.getOrElse { error ->
                if (error.isUnauthorizedResponse()) {
                    listOf(signInPromptItem(parentId))
                } else {
                    listOf(privateSectionUnavailableItem(parentId))
                }
            }
        }
    }

    private fun Throwable.isUnauthorizedResponse(): Boolean {
        val code = runCatching {
            javaClass.methods.firstOrNull { method ->
                method.name == "code" && method.parameterTypes.isEmpty()
            }?.invoke(this) as? Int
        }.getOrNull()
        return code == 401 ||
            message?.contains("401") == true ||
            cause?.isUnauthorizedResponse() == true
    }

    private fun signInPromptItem(parentId: String): MediaItem =
        MediaItem.Builder()
            .setMediaId("$parentId:signin")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.auto_signin_title))
                    .setSubtitle(context.getString(R.string.auto_signin_subtitle))
                    .setLocalArtwork(R.drawable.ic_auto_signin)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_NEWS)
                    // AAOS filters inert items (neither browsable nor playable)
                    // out of lists, which turns placeholders into the generic
                    // "Media isn't available" error. Mark placeholders as
                    // browsable informational rows so the car can render them.
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setExtras(AutoBrowseHints.placeholderHints())
                    .build(),
            )
            .build()

    private fun privateSectionUnavailableItem(parentId: String): MediaItem =
        MediaItem.Builder()
            .setMediaId("$parentId:unavailable")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.auto_private_unavailable_title))
                    .setSubtitle(context.getString(R.string.auto_private_unavailable_subtitle))
                    .setLocalArtwork(R.drawable.ic_auto_signin)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_NEWS)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setExtras(AutoBrowseHints.placeholderHints())
                    .build(),
            )
            .build()

    private fun emptyStateItem(
        parentId: String,
        titleResId: Int,
        subtitleResId: Int?,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId("$parentId:empty")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(titleResId))
                    .apply { if (subtitleResId != null) setSubtitle(context.getString(subtitleResId)) }
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setExtras(AutoBrowseHints.placeholderHints())
                    .build(),
            )
            .build()

    private suspend fun loadItemInternalSuspend(mediaId: String): MediaItem? = when {
        mediaId == HypeMediaIds.root -> rootItem()
        mediaId == HypeMediaIds.latest ||
            mediaId == HypeMediaIds.popular ||
            mediaId == HypeMediaIds.favorites ||
            mediaId == HypeMediaIds.feed ||
            mediaId == HypeMediaIds.playlists ||
            mediaId == HypeMediaIds.history ||
            mediaId == HypeMediaIds.more
        -> sectionItem(mediaId)
        // Placeholder rows (sign-in prompt, empty state) are browsable so the
        // car renders them; resolving them back to themselves keeps a tap from
        // surfacing the system's generic "media unavailable" error.
        mediaId.endsWith(":signin") -> signInPromptItem(mediaId.removeSuffix(":signin"))
        mediaId.endsWith(":unavailable") -> privateSectionUnavailableItem(mediaId.removeSuffix(":unavailable"))
        mediaId.endsWith(":empty") -> emptyStateItem(mediaId.removeSuffix(":empty"), R.string.auto_empty_generic_title, null)
        else -> loadPlaylistItem(mediaId) ?: loadPlayableTrackItem(mediaId)
    }

    // Synchronous wrappers retained because the reflection-driven tests in
    // HypeMediaLibraryCallbackMetadataTest call them by name.
    private fun loadChildren(parentId: String, pageSize: Int): List<MediaItem> =
        kotlinx.coroutines.runBlocking { loadChildrenSuspend(parentId, page = 0, pageSize = pageSize) }

    private fun loadItem(mediaId: String): MediaItem? =
        kotlinx.coroutines.runBlocking {
            runSuspendCatchingPreservingCancellation { loadItemInternalSuspend(mediaId) }.getOrNull()
        }

    private fun resolveMediaItems(mediaItems: List<MediaItem>): List<MediaItem> =
        kotlinx.coroutines.runBlocking { resolveMediaItemsSuspend(mediaItems) }

    private fun resolveMediaItemsWithStartPosition(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): MediaItemsWithStartPosition =
        kotlinx.coroutines.runBlocking { resolveMediaItemsWithStartPositionSuspend(mediaItems, startIndex, startPositionMs) }

    private fun Int.sanitizedPageSize(): Int =
        when {
            this <= 0 -> DefaultPageSize
            this > MaxPageSize -> MaxPageSize
            else -> this
        }

    private fun Int.toApiPage(): Int = coerceAtLeast(0) + 1

    private fun String.resolvableTrackId(): String? =
        HypeMediaIds.parseTrackId(this)
            ?: takeUnless {
                it.isBlank() ||
                    it == HypeMediaIds.root ||
                    it.startsWith("section:") ||
                    it.startsWith("playlist:")
            }

    /**
     * Returns the four primary top-level sections shown at the Android Auto
     * root. The full list of categories (Feed, Playlists, History) is reachable
     * one level deeper via [HypeMediaIds.more].
     */
    private fun sectionItems(): List<MediaItem> = listOf(
        sectionTile(HypeMediaIds.latest, R.string.auto_section_latest_title, R.string.auto_section_latest_subtitle, R.drawable.ic_auto_section_latest),
        sectionTile(HypeMediaIds.popular, R.string.auto_section_popular_title, R.string.auto_section_popular_subtitle, R.drawable.ic_auto_section_popular),
        sectionTile(HypeMediaIds.favorites, R.string.auto_section_favorites_title, R.string.auto_section_favorites_subtitle, R.drawable.ic_auto_section_favorites),
        sectionTile(HypeMediaIds.more, R.string.auto_section_more_title, R.string.auto_section_more_subtitle, R.drawable.ic_auto_section_more),
    )

    private fun moreSectionItems(): List<MediaItem> = listOf(
        sectionTile(HypeMediaIds.feed, R.string.auto_section_feed_title, R.string.auto_section_feed_subtitle, R.drawable.ic_auto_section_feed),
        sectionTile(HypeMediaIds.playlists, R.string.auto_section_playlists_title, R.string.auto_section_playlists_subtitle, R.drawable.ic_auto_section_playlists),
        sectionTile(HypeMediaIds.history, R.string.auto_section_history_title, R.string.auto_section_history_subtitle, R.drawable.ic_auto_section_history),
    )

    private fun rootItem(): MediaItem =
        sectionTile(HypeMediaIds.root, R.string.auto_root_title, subtitleResId = null, artworkResId = null)

    /** Builds a single top-level section tile with localised title/subtitle and an in-APK drawable. */
    private fun sectionTile(
        mediaId: String,
        titleResId: Int,
        subtitleResId: Int?,
        artworkResId: Int?,
    ): MediaItem = browsableItem(
        mediaId = mediaId,
        title = context.getString(titleResId),
        subtitle = subtitleResId?.let(context::getString),
        artworkResId = artworkResId,
        applySelfCategoryHint = true,
    )

    private fun sectionItem(mediaId: String): MediaItem {
        val titleSubtitleArt = when (mediaId) {
            HypeMediaIds.root -> Triple(R.string.auto_root_title, null, null)
            HypeMediaIds.latest -> Triple(R.string.auto_section_latest_title, R.string.auto_section_latest_subtitle, R.drawable.ic_auto_section_latest)
            HypeMediaIds.popular -> Triple(R.string.auto_section_popular_title, R.string.auto_section_popular_subtitle, R.drawable.ic_auto_section_popular)
            HypeMediaIds.favorites -> Triple(R.string.auto_section_favorites_title, R.string.auto_section_favorites_subtitle, R.drawable.ic_auto_section_favorites)
            HypeMediaIds.feed -> Triple(R.string.auto_section_feed_title, R.string.auto_section_feed_subtitle, R.drawable.ic_auto_section_feed)
            HypeMediaIds.playlists -> Triple(R.string.auto_section_playlists_title, R.string.auto_section_playlists_subtitle, R.drawable.ic_auto_section_playlists)
            HypeMediaIds.history -> Triple(R.string.auto_section_history_title, R.string.auto_section_history_subtitle, R.drawable.ic_auto_section_history)
            HypeMediaIds.more -> Triple(R.string.auto_section_more_title, R.string.auto_section_more_subtitle, R.drawable.ic_auto_section_more)
            else -> Triple(R.string.auto_root_title, null, null)
        }
        return sectionTile(mediaId, titleSubtitleArt.first, titleSubtitleArt.second, titleSubtitleArt.third)
    }

    private fun Playlist.toBrowsableItem(): MediaItem = browsableItem(
        mediaId = HypeMediaIds.playlist(id),
        title = name,
    )

    /** Builds a playable [MediaItem] from a [Track] with title/artist/album/subtitle metadata. */
    private fun Track.toPlayableItem(sourceId: String? = null, sourcePage: Int = 0, sourcePageSize: Int = 0): MediaItem = MediaItem.Builder()
        .setMediaId(
            sourceId?.let { HypeMediaIds.track(id, it, sourcePage = sourcePage, sourcePageSize = sourcePageSize) }
                ?: HypeMediaIds.track(id),
        )
        .setUri(offlineRepository.cachedAudioUri(id) ?: streamUrl())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(postedBy)
                .setSubtitle(artist)
                .setDisplayTitle(title)
                .setDescription(postDescription.takeIf { it.isNotBlank() })
                .setArtworkUri(bestThumbnail()?.let(android.net.Uri::parse))
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                // Carry secondary Hype metadata in extras so the visible car
                // row can stay title + artist while commands still have the
                // state they need.
                .setExtras(
                    Bundle().apply {
                        putBoolean(ExtraIsLoved, isLoved)
                        putInt(ExtraBlogId, postedById)
                        putString(ExtraBlogName, postedBy)
                        putInt(ExtraLovedCount, lovedCount)
                    },
                )
                .build(),
        )
        .build()

    /**
     * Builds a browsable [MediaItem]. The two-argument form is preserved
     * exactly (`browsableItem(String, String)`) because reflection-driven
     * tests still call it by name.
     */
    private fun browsableItem(mediaId: String, title: String): MediaItem = browsableItem(
        mediaId = mediaId,
        title = title,
        subtitle = null,
        artworkResId = null,
        applySelfCategoryHint = false,
    )

    private fun browsableItem(
        mediaId: String,
        title: String,
        subtitle: String?,
        artworkResId: Int?,
        applySelfCategoryHint: Boolean,
    ): MediaItem {
        val mediaType = when (mediaId) {
            HypeMediaIds.root -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            HypeMediaIds.favorites -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            HypeMediaIds.playlists -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            HypeMediaIds.history -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            HypeMediaIds.more -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            else -> MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
        }
        val builder = MediaMetadata.Builder()
            .setTitle(title)
            .setMediaType(mediaType)
            .setIsBrowsable(true)
            .setIsPlayable(false)
        if (subtitle != null) builder.setSubtitle(subtitle)
        builder.setLocalArtwork(artworkResId)
        if (applySelfCategoryHint) builder.setExtras(AutoBrowseHints.selfHintCategory())
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(builder.build())
            .build()
    }

    /** Builds a `android.resource://...` URI for an in-APK drawable, suitable for [MediaMetadata.setArtworkUri]. */
    private fun drawableResUri(resId: Int): Uri =
        Uri.Builder()
            .scheme("android.resource")
            .authority(context.packageName)
            .appendPath(resId.toString())
            .build()

    private fun MediaMetadata.Builder.setLocalArtwork(resId: Int?): MediaMetadata.Builder {
        if (resId == null) return this
        val data = drawableArtworkData(resId)
        if (data != null) {
            setArtworkData(data, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        } else {
            setArtworkUri(drawableResUri(resId))
        }
        return this
    }

    private fun drawableArtworkData(resId: Int): ByteArray? =
        drawableArtworkCache[resId] ?: renderDrawableArtwork(resId)?.also { bytes ->
            drawableArtworkCache[resId] = bytes
        }

    private fun renderDrawableArtwork(resId: Int): ByteArray? {
        val drawable = context.getDrawable(resId) ?: return null
        val sizePx = (96f * context.resources.displayMetrics.density)
            .roundToInt()
            .coerceAtLeast(96)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return ByteArrayOutputStream().use { output ->
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                output.toByteArray()
            } else {
                null
            }
        }
    }

    fun close() {
        currentSession?.player?.removeListener(playerListener)
        currentSession = null
        callbackScope.cancel()
    }
}

internal data class AutoFavoriteToggleResult(
    val confirmedLoved: Boolean,
    val sessionResult: SessionResult,
)

@UnstableApi
internal suspend fun resolveAutoFavoriteToggle(
    meRepository: MeRepository,
    trackId: String,
    originalLoved: Boolean,
): AutoFavoriteToggleResult {
    val confirmed = runSuspendCatchingPreservingCancellation {
        meRepository.toggleFavorite(trackId)
    }.getOrNull()
    return if (confirmed == null) {
        AutoFavoriteToggleResult(
            confirmedLoved = originalLoved,
            sessionResult = SessionResult(SessionError.ERROR_IO),
        )
    } else {
        AutoFavoriteToggleResult(
            confirmedLoved = confirmed,
            sessionResult = SessionResult(SessionResult.RESULT_SUCCESS),
        )
    }
}
