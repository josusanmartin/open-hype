package dev.josu.hypecar.core.model.repository

import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.model.Blog
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
import dev.josu.hypecar.core.model.LatestMode
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.PopularMode
import dev.josu.hypecar.core.model.SearchQuery
import dev.josu.hypecar.core.model.Tag
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    val session: Flow<AuthSession?>

    suspend fun login(usernameOrEmail: String, password: String): Result<AuthSession>

    suspend fun logout()
}

interface CatalogRepository {
    suspend fun latest(mode: LatestMode = LatestMode.ALL, page: Int = 1, count: Int = 20): List<Track>

    suspend fun popular(mode: PopularMode = PopularMode.NOW, page: Int = 1, count: Int = 20): List<Track>

    suspend fun track(trackId: String): Track

    suspend fun blogs(page: Int = 1, count: Int = 20): List<Blog>

    suspend fun blog(blogId: Int): Blog

    suspend fun blogTracks(blogId: Int, page: Int = 1, count: Int = 20): List<Track>

    suspend fun user(username: String): User

    suspend fun userFavorites(username: String, page: Int = 1, count: Int = 20): List<Track>

    suspend fun userFriends(username: String, page: Int = 1, count: Int = 20): List<User>

    suspend fun tags(): List<Tag>

    suspend fun tagTracks(tag: String, page: Int = 1, count: Int = 20): List<Track>
}

interface MeRepository {
    suspend fun favorites(page: Int = 1, count: Int = 20): List<Track>

    /** Returns the final loved state after the toggle, or null when the request failed. */
    suspend fun toggleFavorite(trackId: String): Boolean?

    suspend fun playlistNames(): List<Playlist>

    suspend fun playlist(playlistId: Int, page: Int = 1, count: Int = 20): List<Track>

    suspend fun feed(mode: FeedMode = FeedMode.ALL, page: Int = 1, count: Int = 20): List<FeedItem>

    suspend fun history(page: Int = 1, count: Int = 20): List<Track>
}

interface SearchRepository {
    suspend fun searchTracks(query: SearchQuery, page: Int = 1, count: Int = 20): List<Track>
}

interface HistoryRepository {
    suspend fun postListen(trackId: String, positionSeconds: Int): Boolean
}

interface PlaybackRepository {
    val queue: StateFlow<PlaybackQueue>

    suspend fun play(tracks: List<Track>, startIndex: Int = 0)

    suspend fun playFromTrack(track: Track)

    suspend fun togglePlayPause()

    suspend fun skipNext()

    suspend fun skipPrevious()

    suspend fun seekTo(positionMs: Long)

    suspend fun toggleShuffle()

    suspend fun cycleRepeatMode()

    suspend fun updateFavorite(trackId: String, isLoved: Boolean)

    /** Mark the most recent transient error as seen, hiding it from the queue state. */
    fun acknowledgePlaybackError(eventId: Long) = Unit
}

data class OfflineDownloadStatus(
    val isEnabled: Boolean = false,
    val quotaBytes: Long = 500L * 1024L * 1024L,
    val usedBytes: Long = 0L,
    val downloadedTrackCount: Int = 0,
    val isSyncing: Boolean = false,
    val lastSyncedAtEpochSeconds: Long? = null,
    val error: String? = null,
)

interface OfflineRepository {
    val status: StateFlow<OfflineDownloadStatus>

    suspend fun setEnabled(enabled: Boolean)

    suspend fun setQuotaBytes(quotaBytes: Long)

    suspend fun syncFavorites()

    suspend fun clearDownloads()

    fun cachedAudioUri(trackId: String): String?
}

/**
 * Coarse description of the device's network reachability, surfaced by
 * [ConnectivityRepository]. The UI banner displays a warning whenever the
 * value transitions away from [Online], so the user knows that any lists or
 * favorites they see are coming from the offline cache.
 */
enum class Connectivity {
    /** Fully online — at least one validated default network is available. */
    Online,

    /** Captive portal / metered / restricted — connected but degraded. */
    Limited,

    /** No network. */
    Offline,
}

interface ConnectivityRepository {
    /**
     * Hot stream of the current connectivity state. Emits the latest value
     * to new collectors immediately so a banner can render on first
     * composition without waiting for a network change.
     */
    val connectivity: StateFlow<Connectivity>
}
