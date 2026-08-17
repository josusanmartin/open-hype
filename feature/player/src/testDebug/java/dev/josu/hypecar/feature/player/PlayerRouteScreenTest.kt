package dev.josu.hypecar.feature.player

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
import dev.josu.hypecar.core.model.PlaybackItem
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.ui.HypeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            HypeTheme {
                Surface { PlayerRoute(viewModel = newPlayerViewModel(playback, NoOpMe)) }
            }
        }

        composeRule.onNodeWithText("Nothing is playing.").assertIsDisplayed()
        composeRule.onNodeWithTag("playerSupernovaGlow").assertDoesNotExist()
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
            HypeTheme {
                Surface { PlayerRoute(viewModel = newPlayerViewModel(playback, NoOpMe)) }
            }
        }

        composeRule.onNodeWithText("Light of the Dead").assertIsDisplayed()
        composeRule.onNodeWithText("Brooklynzhen").assertIsDisplayed()
        composeRule.onNodeWithTag("playerSupernovaGlow").assertIsDisplayed()
        // Play button content description (not text). When isPlaying=false the button reads "Play".
        composeRule.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-car", sdk = [34])
    fun `automotive player omits the ambient supernova`() {
        val track = sampleTrack(id = "abc", title = "Light of the Dead", artist = "Brooklynzhen")
        val playback = StubPlayback(
            initial = PlaybackQueue(
                items = listOf(PlaybackItem(track)),
                currentIndex = 0,
                isPlaying = true,
                durationMs = 200_000,
            ),
        )
        composeRule.setContent {
            HypeTheme {
                Surface { PlayerRoute(viewModel = newPlayerViewModel(playback, NoOpMe)) }
            }
        }

        composeRule.onNodeWithTag("playerSupernovaGlow").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Collapse").assertIsDisplayed()
    }

    @Test
    fun `active queue renders compact up next preview`() {
        val now = sampleTrack("a", "Opening Track", "Artist A")
        val next = sampleTrack("b", "Queued Track", "Artist B")
        val third = sampleTrack("c", "Later Track", "Artist C")
        val playback = StubPlayback(
            initial = PlaybackQueue(
                items = listOf(PlaybackItem(now), PlaybackItem(next), PlaybackItem(third)),
                currentIndex = 0,
                isPlaying = true,
                durationMs = 100_000,
            ),
        )
        composeRule.setContent {
            HypeTheme {
                Surface { PlayerRoute(viewModel = newPlayerViewModel(playback, NoOpMe)) }
            }
        }

        composeRule.onNodeWithText("Up next").assertIsDisplayed()
        composeRule.onNodeWithText("Queued Track").assertIsDisplayed()
        composeRule.onNodeWithText("Artist B").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land", sdk = [34])
    fun `landscape phone uses a two pane player and keeps core controls visible`() {
        val now = sampleTrack("a", "Opening Track", "Artist A")
        val next = sampleTrack("b", "Queued Track", "Artist B")
        val playback = StubPlayback(
            initial = PlaybackQueue(
                items = listOf(PlaybackItem(now), PlaybackItem(next)),
                currentIndex = 0,
                isPlaying = true,
                durationMs = 100_000,
            ),
        )
        composeRule.setContent {
            HypeTheme {
                Surface { PlayerRoute(viewModel = newPlayerViewModel(playback, NoOpMe)) }
            }
        }

        composeRule.onNodeWithTag("playerLandscapeLayout").assertIsDisplayed()
        composeRule.onNodeWithText("Opening Track").assertIsDisplayed()
        composeRule.onNodeWithText("Queued Track").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test
    fun `phone scrubber keeps a 48dp interaction target`() {
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
            HypeTheme {
                Surface { PlayerRoute(viewModel = newPlayerViewModel(playback, NoOpMe)) }
            }
        }

        composeRule.onNodeWithContentDescription("Playback position").assertHeightIsAtLeast(48.dp)
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
            HypeTheme {
                Surface { PlayerRoute(viewModel = newPlayerViewModel(playback, NoOpMe)) }
            }
        }

        // Tap the bottom-deck "Next" button (has contentDescription = "Next").
        composeRule.onAllNodesWithContentDescription("Next")[0].performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { playback.skipNextCount == 1 }
        assertThat(playback.skipNextCount).isEqualTo(1)
    }

    @Test
    fun `phone player omits redundant top chrome and keeps transport visible`() {
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
            HypeTheme {
                Surface { PlayerRoute(viewModel = newPlayerViewModel(playback, NoOpMe)) }
            }
        }

        assertThat(composeRule.onAllNodesWithContentDescription("Collapse").fetchSemanticsNodes()).isEmpty()
        assertThat(composeRule.onAllNodesWithContentDescription("More actions").fetchSemanticsNodes()).isEmpty()
        assertThat(
            composeRule.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes(),
        ).isNotEmpty()
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
            HypeTheme {
                Surface { PlayerRoute(viewModel = newPlayerViewModel(playback, me)) }
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
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private class AcceptingMe(private val toggleResult: Boolean) : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = toggleResult
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private fun newPlayerViewModel(playback: PlaybackRepository, me: MeRepository) = PlayerViewModel(
    playbackRepository = playback,
    favoriteSyncManager = dev.josu.hypecar.core.data.repository.FavoriteSyncManager(
        me,
        playback,
        CoroutineScope(Dispatchers.Main.immediate),
    ),
)
