package dev.josu.hypecar.feature.details

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.lifecycle.SavedStateHandle
import dev.josu.hypecar.core.data.repository.FavoriteSyncManager
import dev.josu.hypecar.core.model.Blog
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
import dev.josu.hypecar.core.model.LatestMode
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.PopularMode
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.User
import dev.josu.hypecar.core.model.repository.CatalogRepository
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.ui.HypeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp", sdk = [34])
class DetailsRoutesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `BlogDetailRoute renders blog name follower and track stats`() {
        val catalog = ScriptedDetailsCatalog(
            blog = Blog(
                id = 22246,
                name = "Destroy//Exist",
                url = "https://destroyexist.com",
                followerCount = 1234,
                trackCount = 567,
                imageUrl = null,
                imageUrlSmall = null,
            ),
            blogTracks = listOf(track("a", "Alpha"), track("b", "Beta")),
        )

        composeRule.setContent {
            HypeTheme {
                Surface {
                    BlogDetailRoute(
                        onBlogClick = {},
                        viewModel = BlogDetailViewModel(
                            savedStateHandle = SavedStateHandle(mapOf("blogId" to 22246)),
                            catalogRepository = catalog,
                            playbackRepository = DetailsScreenNoOpPlayback,
                            favoriteSyncManager = detailsScreenFavoriteSync(),
                        ),
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 2_000) {
            composeRule.onAllNodesWithText("Destroy//Exist").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Destroy//Exist").assertIsDisplayed()
        composeRule.onNodeWithText("1234 followers").assertIsDisplayed()
        composeRule.onNodeWithText("567 tracks").assertIsDisplayed()
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun `detail back affordance has a 48dp touch target`() {
        val catalog = ScriptedDetailsCatalog(
            blog = Blog(
                id = 22246,
                name = "Destroy//Exist",
                url = "https://destroyexist.com",
                followerCount = 1,
                trackCount = 1,
                imageUrl = null,
                imageUrlSmall = null,
            ),
        )
        composeRule.setContent {
            HypeTheme {
                BlogDetailRoute(
                    onBlogClick = {},
                    onBack = {},
                    viewModel = BlogDetailViewModel(
                        savedStateHandle = SavedStateHandle(mapOf("blogId" to 22246)),
                        catalogRepository = catalog,
                        playbackRepository = DetailsScreenNoOpPlayback,
                        favoriteSyncManager = detailsScreenFavoriteSync(),
                    ),
                )
            }
        }

        val bounds = composeRule.onNodeWithContentDescription("Back")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

        com.google.common.truth.Truth.assertThat(bounds.width.value).isAtLeast(48f)
        com.google.common.truth.Truth.assertThat(bounds.height.value).isAtLeast(48f)
    }

    @Test
    fun `UserDetailRoute renders username and favorites`() {
        val catalog = ScriptedDetailsCatalog(
            user = User(
                username = "alice",
                fullName = "Alice Q",
                avatarUrl = null,
                favoritesCount = 12,
                followersCount = 3,
                followingCount = 7,
            ),
            userFavorites = listOf(track("u1", "First fav")),
        )

        composeRule.setContent {
            HypeTheme {
                Surface {
                    UserDetailRoute(
                        onBlogClick = {},
                        viewModel = UserDetailViewModel(
                            savedStateHandle = SavedStateHandle(mapOf("username" to "alice")),
                            catalogRepository = catalog,
                            playbackRepository = DetailsScreenNoOpPlayback,
                            favoriteSyncManager = detailsScreenFavoriteSync(),
                        ),
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 2_000) {
            composeRule.onAllNodesWithText("First fav").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("First fav").assertIsDisplayed()
    }

    private fun track(id: String, title: String) = Track(
        id = id, artist = "x", title = title, lovedCount = 0,
        postedBy = "z", postedById = 0, postedCount = 0,
        postDescription = "", datePostedEpochSeconds = 0L,
        postUrl = "", itunesUrl = "",
    )
}

private class ScriptedDetailsCatalog(
    private val blog: Blog? = null,
    private val blogTracks: List<Track> = emptyList(),
    private val user: User? = null,
    private val userFavorites: List<Track> = emptyList(),
) : CatalogRepository {
    override suspend fun blog(blogId: Int): Blog = blog ?: error("no blog scripted")
    override suspend fun blogTracks(blogId: Int, page: Int, count: Int): List<Track> = blogTracks
    override suspend fun user(username: String): User = user ?: error("no user scripted")
    override suspend fun userFavorites(username: String, page: Int, count: Int): List<Track> = userFavorites

    override suspend fun latest(mode: LatestMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun popular(mode: PopularMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun track(trackId: String): Track = error("not used")
    override suspend fun blogs(page: Int, count: Int): List<Blog> = emptyList()
    override suspend fun userFriends(username: String, page: Int, count: Int): List<User> = emptyList()
}

private class DetailsScreenRecordingPlayback(
    private val onPlay: (List<Track>, Int) -> Unit = { _, _ -> },
) : PlaybackRepository {
    override val queue: StateFlow<PlaybackQueue> = MutableStateFlow(PlaybackQueue())
    override suspend fun play(tracks: List<Track>, startIndex: Int) {
        onPlay(tracks, startIndex)
    }
    override suspend fun playFromTrack(track: Track) = Unit
    override suspend fun togglePlayPause() = Unit
    override suspend fun skipNext() = Unit
    override suspend fun skipPrevious() = Unit
    override suspend fun seekTo(positionMs: Long) = Unit
    override suspend fun toggleShuffle() = Unit
    override suspend fun cycleRepeatMode() = Unit
    override suspend fun updateFavorite(trackId: String, isLoved: Boolean) = Unit
}

private object DetailsScreenNoOpPlayback : PlaybackRepository {
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

private fun detailsScreenFavoriteSync() = FavoriteSyncManager(DetailsScreenNoOpMe, DetailsScreenNoOpPlayback)

private object DetailsScreenNoOpMe : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}
