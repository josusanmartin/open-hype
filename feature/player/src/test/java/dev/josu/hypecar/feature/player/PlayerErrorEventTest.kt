package dev.josu.hypecar.feature.player

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
import dev.josu.hypecar.core.model.PlaybackErrorEvent
import dev.josu.hypecar.core.model.PlaybackItem
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerErrorEventTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `acknowledgePlaybackError clears the matching transient error`() = runTest {
        val playback = ErrorTrackingPlayback().apply {
            emitError(PlaybackErrorEvent(eventId = 7, trackId = "abc", recoverable = true))
        }
        val vm = PlayerViewModel(playback, NoOpMeRepository)

        vm.acknowledgePlaybackError(7)
        advanceUntilIdle()

        assertThat(playback.queue.value.transientError).isNull()
        assertThat(playback.acknowledged).containsExactly(7L)
    }

    @Test
    fun `acknowledging a stale eventId leaves a fresher error in place`() = runTest {
        val playback = ErrorTrackingPlayback().apply {
            emitError(PlaybackErrorEvent(eventId = 9, trackId = "xyz", recoverable = false))
        }
        val vm = PlayerViewModel(playback, NoOpMeRepository)

        vm.acknowledgePlaybackError(7) // stale id; current is 9
        advanceUntilIdle()

        assertThat(playback.queue.value.transientError?.eventId).isEqualTo(9L)
    }
}

private class ErrorTrackingPlayback : PlaybackRepository {
    private val _queue = MutableStateFlow(PlaybackQueue(items = listOf(PlaybackItem(sampleTrack))))
    override val queue: StateFlow<PlaybackQueue> = _queue
    val acknowledged = mutableListOf<Long>()

    fun emitError(event: PlaybackErrorEvent) {
        _queue.value = _queue.value.copy(transientError = event)
    }

    override fun acknowledgePlaybackError(eventId: Long) {
        acknowledged += eventId
        val current = _queue.value.transientError ?: return
        if (current.eventId == eventId) {
            _queue.value = _queue.value.copy(transientError = null)
        }
    }

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

private object NoOpMeRepository : MeRepository {
    override suspend fun favorites(page: Int, count: Int): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private val sampleTrack = Track(
    id = "abc",
    artist = "x",
    title = "y",
    lovedCount = 0,
    postedBy = "z",
    postedById = 0,
    postedCount = 0,
    postDescription = "",
    datePostedEpochSeconds = 0L,
    postUrl = "",
    itunesUrl = "",
)
