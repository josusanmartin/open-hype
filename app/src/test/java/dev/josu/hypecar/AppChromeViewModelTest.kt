package dev.josu.hypecar

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.PlaybackItem
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.TrackThumbnails
import dev.josu.hypecar.core.model.repository.Connectivity
import dev.josu.hypecar.core.model.repository.ConnectivityRepository
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
class AppChromeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `miniPlayer maps playback queue to compact chrome state`() = runTest {
        val playback = RecordingPlaybackRepository()
        val viewModel = AppChromeViewModel(playback, OnlineConnectivityRepository)

        playback.queueState.value = PlaybackQueue(
            items = listOf(PlaybackItem(track())),
            currentIndex = 0,
            isPlaying = true,
            positionMs = 25,
            durationMs = 100,
        )
        advanceUntilIdle()

        assertThat(viewModel.miniPlayer.value).isEqualTo(
            MiniPlayerUiState(
                title = "Ceremony",
                artist = "ear",
                artworkUrl = "large",
                isPlaying = true,
                progressFraction = 0.25f,
            ),
        )
    }

    @Test
    fun `transport commands delegate to the playback repository`() = runTest {
        val playback = RecordingPlaybackRepository()
        val viewModel = AppChromeViewModel(playback, OnlineConnectivityRepository)

        viewModel.togglePlayPause()
        viewModel.skipNext()
        viewModel.skipPrevious()
        advanceUntilIdle()

        assertThat(playback.commands).containsExactly("togglePlayPause", "skipNext", "skipPrevious").inOrder()
    }
}

private class RecordingPlaybackRepository : PlaybackRepository {
    val queueState = MutableStateFlow(PlaybackQueue())
    override val queue: StateFlow<PlaybackQueue> = queueState
    val commands = mutableListOf<String>()

    override suspend fun play(tracks: List<Track>, startIndex: Int) {
        commands += "play"
    }
    override suspend fun playFromTrack(track: Track) {
        commands += "playFromTrack"
    }
    override suspend fun togglePlayPause() {
        commands += "togglePlayPause"
    }
    override suspend fun skipNext() {
        commands += "skipNext"
    }
    override suspend fun skipPrevious() {
        commands += "skipPrevious"
    }
    override suspend fun seekTo(positionMs: Long) {
        commands += "seekTo"
    }
    override suspend fun toggleShuffle() {
        commands += "toggleShuffle"
    }
    override suspend fun cycleRepeatMode() {
        commands += "cycleRepeatMode"
    }
    override suspend fun updateFavorite(trackId: String, isLoved: Boolean) {
        commands += "updateFavorite"
    }
}

private object OnlineConnectivityRepository : ConnectivityRepository {
    override val connectivity: StateFlow<Connectivity> = MutableStateFlow(Connectivity.Online)
}

private fun track() = Track(
    id = "39v49",
    artist = "ear",
    title = "Ceremony",
    lovedCount = 0,
    postedBy = "Blog",
    postedById = 0,
    postedCount = 0,
    postDescription = "",
    datePostedEpochSeconds = 0L,
    postUrl = "",
    itunesUrl = "",
    thumbnails = TrackThumbnails(large = "large"),
)
