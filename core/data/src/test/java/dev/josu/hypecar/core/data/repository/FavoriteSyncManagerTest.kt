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
        val manager = FavoriteSyncManager(FailingMeRepository, CoroutineScope(testDispatcher))
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
}

private object FailingMeRepository : MeRepository {
    override suspend fun favorites(page: Int, count: Int): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int): List<FeedItem> = emptyList()
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
