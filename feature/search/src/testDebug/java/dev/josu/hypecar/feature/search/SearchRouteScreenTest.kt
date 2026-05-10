package dev.josu.hypecar.feature.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.Blog
import dev.josu.hypecar.core.model.LatestMode
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.PopularMode
import dev.josu.hypecar.core.model.SearchQuery
import dev.josu.hypecar.core.model.SearchSort
import dev.josu.hypecar.core.model.Tag
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.User
import dev.josu.hypecar.core.model.repository.CatalogRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.model.repository.SearchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp", sdk = [34])
class SearchRouteScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `idle state shows hero subtitle search field and tag chips`() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SearchRoute(
                        onTagClick = {},
                        onBlogClick = {},
                        viewModel = SearchViewModel(
                            searchRepository = EmptySearch,
                            catalogRepository = TaggedCatalog(tags = listOf(Tag("acid"), Tag("noise"))),
                            playbackRepository = NoOpPlayback,
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Search").assertIsDisplayed()
        composeRule.onNodeWithText("Search tracks").assertIsDisplayed()
        composeRule.onNodeWithText("Go").assertIsDisplayed()

        composeRule.waitUntil(timeoutMillis = 2_000) {
            composeRule.onAllNodesWithText("acid").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("acid").assertIsDisplayed()
        composeRule.onNodeWithText("noise").assertIsDisplayed()
    }

    @Test
    fun `tapping a tag chip forwards the tag name to the navigation callback`() {
        var navTo: String? = null
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SearchRoute(
                        onTagClick = { navTo = it },
                        onBlogClick = {},
                        viewModel = SearchViewModel(
                            searchRepository = EmptySearch,
                            catalogRepository = TaggedCatalog(tags = listOf(Tag("acid"))),
                            playbackRepository = NoOpPlayback,
                        ),
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 2_000) {
            composeRule.onAllNodesWithText("acid").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("acid").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { navTo != null }
        assertThat(navTo).isEqualTo("acid")
    }

    @Test
    fun `tapping Go after typing fires the search and renders results`() {
        val search = ScriptedSearch(results = listOf(track("a", "Alpha")))
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SearchRoute(
                        onTagClick = {},
                        onBlogClick = {},
                        viewModel = SearchViewModel(
                            searchRepository = search,
                            catalogRepository = TaggedCatalog(),
                            playbackRepository = NoOpPlayback,
                        ),
                    )
                }
            }
        }

        // Typing into the field triggers debounced search; the Go button
        // bypasses debounce and fires immediately, which is what we assert here.
        composeRule.onNodeWithText("Search tracks").performTextInput("alp")
        composeRule.onNodeWithText("Go").performClick()

        composeRule.waitUntil(timeoutMillis = 4_000) {
            composeRule.onAllNodesWithText("Alpha").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
        assertThat(search.calls.last()).isEqualTo("alp" to SearchSort.NEWEST)
    }

    private fun track(id: String, title: String) = Track(
        id = id, artist = "x", title = title, lovedCount = 0,
        postedBy = "z", postedById = 0, postedCount = 0,
        postDescription = "", datePostedEpochSeconds = 0L,
        postUrl = "", itunesUrl = "",
    )
}

private class TaggedCatalog(
    private val tags: List<Tag> = emptyList(),
) : CatalogRepository {
    override suspend fun tags(): List<Tag> = tags
    override suspend fun latest(mode: LatestMode, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun popular(mode: PopularMode, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun track(trackId: String): Track = error("not used")
    override suspend fun blogs(page: Int, count: Int): List<Blog> = emptyList()
    override suspend fun blog(blogId: Int): Blog = error("not used")
    override suspend fun blogTracks(blogId: Int, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun user(username: String): User = error("not used")
    override suspend fun userFavorites(username: String, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun userFriends(username: String, page: Int, count: Int): List<User> = emptyList()
    override suspend fun tagTracks(tag: String, page: Int, count: Int): List<Track> = emptyList()
}

private object EmptySearch : SearchRepository {
    override suspend fun searchTracks(query: SearchQuery, page: Int, count: Int): List<Track> = emptyList()
}

private class ScriptedSearch(private val results: List<Track>) : SearchRepository {
    val calls = mutableListOf<Pair<String, SearchSort>>()
    override suspend fun searchTracks(query: SearchQuery, page: Int, count: Int): List<Track> {
        calls += query.value to query.sort
        return results
    }
}

private object NoOpPlayback : PlaybackRepository {
    override val queue: StateFlow<PlaybackQueue> = MutableStateFlow(PlaybackQueue())
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
