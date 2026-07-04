package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.MeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteSyncManagerTest {
    @Test
    fun `toggle emits a revert edit when favorite request fails`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val manager = FavoriteSyncManager(FailingMeRepository, SyncTestNoOpPlayback, CoroutineScope(testDispatcher))
        val edits = async(testDispatcher) {
            withTimeout(2_000) {
                manager.edits.take(2).toList()
            }
        }

        manager.toggle(sampleTrack(isLoved = false, lovedCount = 7))
        advanceUntilIdle()

        assertThat(edits.await()).containsExactly(
            FavoriteEdit(trackId = "39v49", isLoved = true, lovedCountDelta = 1),
            FavoriteEdit(trackId = "39v49", isLoved = false, lovedCountDelta = -1),
        ).inOrder()
    }

    @Test
    fun `toggle folds optimistic and revert states into the playback queue`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val playback = RecordingSyncPlayback()
        val manager = FavoriteSyncManager(FailingMeRepository, playback, CoroutineScope(testDispatcher))

        manager.toggle(sampleTrack(isLoved = false, lovedCount = 7))
        advanceUntilIdle()

        assertThat(playback.favoriteUpdates).containsExactly(
            "39v49" to true,
            "39v49" to false,
        ).inOrder()
    }

    @Test
    fun `publish forwards externally-managed edits to the playback queue`() = runTest {
        val playback = RecordingSyncPlayback()
        val manager = FavoriteSyncManager(
            FailingMeRepository,
            playback,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        manager.publish(FavoriteEdit(trackId = "39v49", isLoved = true, lovedCountDelta = 1))

        assertThat(playback.favoriteUpdates).containsExactly("39v49" to true)
    }
}

private class RecordingSyncPlayback : dev.josu.hypecar.core.model.repository.PlaybackRepository {
    val favoriteUpdates = mutableListOf<Pair<String, Boolean>>()
    override val queue: kotlinx.coroutines.flow.StateFlow<dev.josu.hypecar.core.model.PlaybackQueue> =
        kotlinx.coroutines.flow.MutableStateFlow(dev.josu.hypecar.core.model.PlaybackQueue())
    override suspend fun play(tracks: List<Track>, startIndex: Int) = Unit
    override suspend fun playFromTrack(track: Track) = Unit
    override suspend fun togglePlayPause() = Unit
    override suspend fun skipNext() = Unit
    override suspend fun skipPrevious() = Unit
    override suspend fun seekTo(positionMs: Long) = Unit
    override suspend fun toggleShuffle() = Unit
    override suspend fun cycleRepeatMode() = Unit
    override suspend fun updateFavorite(trackId: String, isLoved: Boolean) {
        favoriteUpdates += trackId to isLoved
    }
}

private object FailingMeRepository : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private fun sampleTrack(
    isLoved: Boolean,
    lovedCount: Int,
) = Track(
    id = "39v49",
    title = "Alpha",
    artist = "Beta",
    postedBy = "Blog",
    postedById = 1,
    postedCount = 2,
    postDescription = "Post",
    datePostedEpochSeconds = 1_700_000_000L,
    postUrl = "https://example.com/post",
    itunesUrl = "",
    isLoved = isLoved,
    lovedCount = lovedCount,
)

private object SyncTestNoOpPlayback : dev.josu.hypecar.core.model.repository.PlaybackRepository {
    override val queue: kotlinx.coroutines.flow.StateFlow<dev.josu.hypecar.core.model.PlaybackQueue> =
        kotlinx.coroutines.flow.MutableStateFlow(dev.josu.hypecar.core.model.PlaybackQueue())
    override suspend fun play(tracks: List<Track>, startIndex: Int) = Unit
    override suspend fun playFromTrack(track: Track) = Unit
    override suspend fun togglePlayPause() = Unit
    override suspend fun skipNext() = Unit
    override suspend fun skipPrevious() = Unit
    override suspend fun seekTo(positionMs: Long) = Unit
    override suspend fun toggleShuffle() = Unit
    override suspend fun cycleRepeatMode() = Unit
    override suspend fun updateFavorite(trackId: String, isLoved: Boolean) = Unit
}
