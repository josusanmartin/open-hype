package dev.josu.hypecar.auto.service

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.josu.hypecar.auto.HypeMediaIds
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.SearchQuery
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.CatalogRepository
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.OfflineRepository
import dev.josu.hypecar.core.model.repository.SearchRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@androidx.annotation.OptIn(UnstableApi::class)
class HypeMediaLibraryCallback @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val meRepository: MeRepository,
    private val searchRepository: SearchRepository,
    private val offlineRepository: OfflineRepository,
) : MediaLibrarySession.Callback {
    private companion object {
        const val DefaultPageSize = 20
        const val MaxPageSize = 30
    }

    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            LibraryResult.ofItem(
                browsableItem(
                    mediaId = HypeMediaIds.root,
                    title = "Open Hype",
                ),
                params,
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

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = Futures.immediateFuture(LibraryResult.ofVoid())

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
                params,
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

    private suspend fun loadChildrenSuspend(parentId: String, page: Int, pageSize: Int): List<MediaItem> =
        runSuspendCatchingPreservingCancellation {
            val count = pageSize.sanitizedPageSize()
            val apiPage = page.toApiPage()
            when (parentId) {
                HypeMediaIds.root -> sectionItems()
                HypeMediaIds.latest -> catalogRepository.latest(page = apiPage, count = count).map { it.toPlayableItem(parentId) }
                HypeMediaIds.popular -> catalogRepository.popular(page = apiPage, count = count).map { it.toPlayableItem(parentId) }
                HypeMediaIds.favorites -> meRepository.favorites(page = apiPage, count = count).map { it.toPlayableItem(parentId) }
                HypeMediaIds.feed -> meRepository.feed(page = apiPage, count = count).map { it.track.toPlayableItem(parentId) }
                HypeMediaIds.playlists -> meRepository.playlistNames().map { it.toBrowsableItem() }
                HypeMediaIds.history -> meRepository.history(page = apiPage, count = count).map { it.toPlayableItem(parentId) }
                else -> HypeMediaIds.parsePlaylistId(parentId)?.let { playlistId ->
                    meRepository.playlist(playlistId, page = apiPage, count = count).map { it.toPlayableItem(parentId) }
                } ?: HypeMediaIds.parseSearchQuery(parentId)?.let { query ->
                    searchRepository.searchTracks(
                        SearchQuery(query),
                        page = apiPage,
                        count = count,
                    ).map { it.toPlayableItem(parentId) }
                } ?: emptyList()
            }
        }.getOrDefault(emptyList())

    private suspend fun loadItemSuspend(mediaId: String): MediaItem? =
        runSuspendCatchingPreservingCancellation {
            when (mediaId) {
                HypeMediaIds.root -> browsableItem(HypeMediaIds.root, "Open Hype")
                HypeMediaIds.latest -> browsableItem(HypeMediaIds.latest, "Latest")
                HypeMediaIds.popular -> browsableItem(HypeMediaIds.popular, "Popular")
                HypeMediaIds.favorites -> browsableItem(HypeMediaIds.favorites, "Favorites")
                HypeMediaIds.feed -> browsableItem(HypeMediaIds.feed, "Feed")
                HypeMediaIds.playlists -> browsableItem(HypeMediaIds.playlists, "Playlists")
                HypeMediaIds.history -> browsableItem(HypeMediaIds.history, "History")
                else -> loadPlaylistItem(mediaId) ?: loadPlayableTrackItem(mediaId)
            }
        }.getOrNull()

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
        mediaItems.singleOrNull()?.let { selectedItem ->
            val selectedTrackId = HypeMediaIds.parseTrackId(selectedItem.mediaId)
            val sourceId = HypeMediaIds.parseTrackSourceId(selectedItem.mediaId)
            val sourcePage = HypeMediaIds.parseTrackSourcePage(selectedItem.mediaId)
            if (selectedTrackId != null && sourceId != null) {
                val sourceQueue = loadChildrenSuspend(sourceId, page = sourcePage, pageSize = MaxPageSize)
                val selectedIndex = sourceQueue.indexOfFirst { item ->
                    HypeMediaIds.parseTrackId(item.mediaId) == selectedTrackId
                }
                if (selectedIndex >= 0) {
                    return MediaItemsWithStartPosition(sourceQueue, selectedIndex, startPositionMs)
                }
            }
        }

        val resolvedItems = resolveMediaItemsSuspend(mediaItems)
        val resolvedStartIndex = if (resolvedItems.isEmpty()) {
            0
        } else {
            startIndex.coerceIn(0, resolvedItems.lastIndex)
        }
        return MediaItemsWithStartPosition(resolvedItems, resolvedStartIndex, startPositionMs)
    }

    private suspend fun loadPlaylistItem(mediaId: String): MediaItem? =
        HypeMediaIds.parsePlaylistId(mediaId)?.let { playlistId ->
            val title = runSuspendCatchingPreservingCancellation {
                meRepository.playlistNames().firstOrNull { it.id == playlistId }?.name
            }.getOrNull() ?: "Playlist $playlistId"
            browsableItem(mediaId, title)
        }

    private suspend fun loadPlayableTrackItem(mediaId: String): MediaItem? =
        mediaId.resolvableTrackId()?.let { trackId ->
            runSuspendCatchingPreservingCancellation {
                catalogRepository.track(trackId).toPlayableItem()
            }.getOrNull()
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

    private suspend fun loadChildrenResultSuspend(
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): LibraryResult<ImmutableList<MediaItem>> =
        runSuspendCatchingPreservingCancellation {
            ImmutableList.copyOf(loadChildrenInternalSuspend(parentId, page, pageSize))
        }.fold(
            onSuccess = { LibraryResult.ofItemList(it, params) },
            onFailure = { LibraryResult.ofError(SessionError.ERROR_IO) },
        )

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
            HypeMediaIds.latest -> catalogRepository.latest(page = apiPage, count = count).map { it.toPlayableItem(parentId, sourcePage) }
            HypeMediaIds.popular -> catalogRepository.popular(page = apiPage, count = count).map { it.toPlayableItem(parentId, sourcePage) }
            HypeMediaIds.favorites -> meRepository.favorites(page = apiPage, count = count).map { it.toPlayableItem(parentId, sourcePage) }
            HypeMediaIds.feed -> meRepository.feed(page = apiPage, count = count).map { it.track.toPlayableItem(parentId, sourcePage) }
            HypeMediaIds.playlists -> meRepository.playlistNames().map { it.toBrowsableItem() }
            HypeMediaIds.history -> meRepository.history(page = apiPage, count = count).map { it.toPlayableItem(parentId, sourcePage) }
            else -> HypeMediaIds.parsePlaylistId(parentId)?.let { playlistId ->
                meRepository.playlist(playlistId, page = apiPage, count = count).map { it.toPlayableItem(parentId, sourcePage) }
            } ?: HypeMediaIds.parseSearchQuery(parentId)?.let { query ->
                searchRepository.searchTracks(
                    SearchQuery(query),
                    page = apiPage,
                    count = count,
                ).map { it.toPlayableItem(parentId, sourcePage) }
            } ?: emptyList()
        }
    }

    private suspend fun loadItemInternalSuspend(mediaId: String): MediaItem? = when (mediaId) {
        HypeMediaIds.root -> browsableItem(HypeMediaIds.root, "Open Hype")
        HypeMediaIds.latest -> browsableItem(HypeMediaIds.latest, "Latest")
        HypeMediaIds.popular -> browsableItem(HypeMediaIds.popular, "Popular")
        HypeMediaIds.favorites -> browsableItem(HypeMediaIds.favorites, "Favorites")
        HypeMediaIds.feed -> browsableItem(HypeMediaIds.feed, "Feed")
        HypeMediaIds.playlists -> browsableItem(HypeMediaIds.playlists, "Playlists")
        HypeMediaIds.history -> browsableItem(HypeMediaIds.history, "History")
        else -> loadPlaylistItem(mediaId) ?: loadPlayableTrackItem(mediaId)
    }

    private fun loadChildren(parentId: String, pageSize: Int): List<MediaItem> =
        runBlocking { loadChildrenSuspend(parentId, page = 0, pageSize = pageSize) }

    private fun loadItem(mediaId: String): MediaItem? =
        runBlocking { loadItemSuspend(mediaId) }

    private fun resolveMediaItems(mediaItems: List<MediaItem>): List<MediaItem> =
        runBlocking { resolveMediaItemsSuspend(mediaItems) }

    private fun resolveMediaItemsWithStartPosition(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): MediaItemsWithStartPosition =
        runBlocking { resolveMediaItemsWithStartPositionSuspend(mediaItems, startIndex, startPositionMs) }

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

    private fun sectionItems(): List<MediaItem> = listOf(
        browsableItem(HypeMediaIds.latest, "Latest"),
        browsableItem(HypeMediaIds.popular, "Popular"),
        browsableItem(HypeMediaIds.favorites, "Favorites"),
        browsableItem(HypeMediaIds.feed, "Feed"),
        browsableItem(HypeMediaIds.playlists, "Playlists"),
        browsableItem(HypeMediaIds.history, "History"),
    )

    private fun Playlist.toBrowsableItem(): MediaItem = browsableItem(
        mediaId = HypeMediaIds.playlist(id),
        title = name,
    )

    private fun Track.toPlayableItem(sourceId: String? = null, sourcePage: Int = 0): MediaItem =
        MediaItem.Builder()
            .setMediaId(
                sourceId?.let { HypeMediaIds.track(id, it, sourcePage = sourcePage) }
                    ?: HypeMediaIds.track(id),
            )
            .setUri(offlineRepository.cachedAudioUri(id) ?: streamUrl())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(postedBy)
                    .setArtworkUri(bestThumbnail()?.let(android.net.Uri::parse))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()

    private fun browsableItem(mediaId: String, title: String): MediaItem {
        val mediaType = when (mediaId) {
            HypeMediaIds.root -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            HypeMediaIds.favorites -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            HypeMediaIds.playlists -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            HypeMediaIds.history -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            else -> MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setMediaType(mediaType)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build(),
            )
            .build()
    }

    fun close() {
        callbackScope.cancel()
    }
}
