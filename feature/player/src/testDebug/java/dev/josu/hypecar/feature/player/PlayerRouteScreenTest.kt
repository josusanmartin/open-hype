package dev.josu.hypecar.feature.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
import dev.josu.hypecar.core.model.PlaybackItem
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp", sdk = [34])
class PlayerRouteScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `idle player shows the empty-state message`() {
        val playback = StubPlayback(initial = PlaybackQueue())
        composeRule.setContent {
            MaterialTheme {
                Surface { PlayerRoute(viewModel = PlayerViewModel(playback, NoOpMe)) }
            }
        }

        composeRule.onNodeWithText("Nothing is playing.").assertIsDisplayed()
    }

    @Test
    fun `active queue renders title artist and Play action`() {
        val track = sampleTrack(id = "abc", title = "Light of the Dead", artist = "Brooklynzhen")
        val playback = StubPlayback(
            initial = PlaybackQueue(
                items = listOf(PlaybackItem(track)),
                currentIndex = 0,
                isPlaying = false,
                durationMs = 200_000,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                Surface { PlayerRoute(viewModel = PlayerViewModel(playback, NoOpMe)) }
            }
        }

        composeRule.onNodeWithText("Light of the Dead").assertIsDisplayed()
        composeRule.onNodeWithText("Brooklynzhen").assertIsDisplayed()
        // Play button content description (not text). When isPlaying=false the button reads "Play".
        composeRule.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test
    fun `tapping Next forwards skip to playback repository`() {
        val track = sampleTrack("a", "Title", "Artist")
        val playback = StubPlayback(
            initial = PlaybackQueue(
                items = listOf(PlaybackItem(track)),
                currentIndex = 0,
                isPlaying = true,
                durationMs = 100_000,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                Surface { PlayerRoute(viewModel = PlayerViewModel(playback, NoOpMe)) }
            }
        }

        // Tap the bottom-deck "Next" button (has contentDescription = "Next").
        composeRule.onAllNodesWithContentDescription("Next")[0].performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { playback.skipNextCount == 1 }
        assertThat(playback.skipNextCount).isEqualTo(1)
    }

    @Test
    fun `top bar shows Now Playing title with collapse and more actions buttons`() {
        val track = sampleTrack("a", "Title", "Artist")
        val playback = StubPlayback(
            initial = PlaybackQueue(
                items = listOf(PlaybackItem(track)),
                currentIndex = 0,
                isPlaying = true,
                durationMs = 100_000,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                Surface { PlayerRoute(viewModel = PlayerViewModel(playback, NoOpMe)) }
            }
        }

        composeRule.onNodeWithText("Now Playing").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Collapse").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("More actions").assertIsDisplayed()
    }

    @Test
    fun `favorite icon reflects state and tapping forwards an update`() {
        val track = sampleTrack("a", "Title", "Artist", isLoved = false)
        val playback = StubPlayback(
            initial = PlaybackQueue(
                items = listOf(PlaybackItem(track)),
                currentIndex = 0,
                isPlaying = true,
                durationMs = 100_000,
            ),
        )
        val me = AcceptingMe(toggleResult = true)
        composeRule.setContent {
            MaterialTheme {
                Surface { PlayerRoute(viewModel = PlayerViewModel(playback, me)) }
            }
        }

        composeRule.onNodeWithContentDescription("Favorite").assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 2_000) { playback.favoriteUpdates.isNotEmpty() }
        assertThat(playback.favoriteUpdates.first()).isEqualTo("a" to true)
    }

    private fun sampleTrack(
        id: String,
        title: String = "Title",
        artist: String = "Artist",
        isLoved: Boolean = false,
    ) = Track(
        id = id, artist = artist, title = title, lovedCount = 0,
        postedBy = "Blog", postedById = 0, postedCount = 0,
        postDescription = "", datePostedEpochSeconds = 0L,
        postUrl = "", itunesUrl = "",
        isLoved = isLoved,
    )
}

private class StubPlayback(initial: PlaybackQueue) : PlaybackRepository {
    private val _queue = MutableStateFlow(initial)
    override val queue: StateFlow<PlaybackQueue> = _queue
    var skipNextCount = 0
        private set
    val favoriteUpdates = mutableListOf<Pair<String, Boolean>>()

    override suspend fun play(tracks: List<Track>, startIndex: Int) = Unit
    override suspend fun playFromTrack(track: Track) = Unit
    override suspend fun togglePlayPause() = Unit
    override suspend fun skipNext() {
        skipNextCount += 1
    }
    override suspend fun skipPrevious() = Unit
    override suspend fun seekTo(positionMs: Long) = Unit
    override suspend fun toggleShuffle() = Unit
    override suspend fun cycleRepeatMode() = Unit
    override suspend fun updateFavorite(trackId: String, isLoved: Boolean) {
        favoriteUpdates += trackId to isLoved
        _queue.value = _queue.value.copy(
            items = _queue.value.items.map { item ->
                if (item.track.id == trackId) item.copy(track = item.track.copy(isLoved = isLoved)) else item
            },
        )
    }
}

private object NoOpMe : MeRepository {
    override suspend fun favorites(page: Int, count: Int): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private class AcceptingMe(private val toggleResult: Boolean) : MeRepository {
    override suspend fun favorites(page: Int, count: Int): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = toggleResult
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}
