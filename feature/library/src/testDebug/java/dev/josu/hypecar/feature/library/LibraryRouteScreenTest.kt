package dev.josu.hypecar.feature.library

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.data.repository.FavoriteSyncManager
import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.AuthRepository
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.ui.HypeTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp", sdk = [34])
class LibraryRouteScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `signed-out state shows the sign-in card and Login utility`() {
        var loginTapped = false
        composeRule.setContent {
            HypeTheme {
                Surface {
                    LibraryRoute(
                        onBlogClick = {},
                        onUserClick = {},
                        onLoginClick = { loginTapped = true },
                        viewModel = LibraryViewModel(
                            authRepository = SignedOutAuth(),
                            meRepository = EmptyMe,
                            playbackRepository = NoOpPlayback,
                            favoriteSyncManager = FavoriteSyncManager(EmptyMe, NoOpPlayback),
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Library")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeRule.onNodeWithText("Open your full library")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeRule.onNodeWithText("Sign in").assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 2_000) { loginTapped }
        assertThat(loginTapped).isTrue()
    }

    @Test
    fun `signed-in favorites tab renders track titles`() {
        val me = StaticMe(favorites = listOf(track("a", "Alpha"), track("b", "Beta")))
        composeRule.setContent {
            HypeTheme {
                Surface {
                    LibraryRoute(
                        onBlogClick = {},
                        onUserClick = {},
                        onLoginClick = {},
                        viewModel = LibraryViewModel(
                            authRepository = SignedInAuth(),
                            meRepository = me,
                            playbackRepository = NoOpPlayback,
                            favoriteSyncManager = FavoriteSyncManager(me, NoOpPlayback),
                        ),
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 2_000) {
            composeRule.onAllNodesWithText("Alpha").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeRule.onNodeWithText("Beta").assertIsDisplayed()
    }

    @Test
    fun `destructive logout control has a 48dp touch target`() {
        val me = StaticMe(favorites = listOf(track("a", "Alpha")))
        composeRule.setContent {
            HypeTheme {
                LibraryRoute(
                    onBlogClick = {},
                    onUserClick = {},
                    onLoginClick = {},
                    viewModel = LibraryViewModel(
                        authRepository = SignedInAuth(),
                        meRepository = me,
                        playbackRepository = NoOpPlayback,
                        favoriteSyncManager = FavoriteSyncManager(me, NoOpPlayback),
                    ),
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 2_000) {
            composeRule.onAllNodesWithText("Alpha").fetchSemanticsNodes().isNotEmpty()
        }

        val bounds = composeRule.onNodeWithContentDescription("Log out")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

        assertThat(bounds.width.value).isAtLeast(48f)
        assertThat(bounds.height.value).isAtLeast(48f)
    }

    @Test
    fun `incomplete logout is announced politely as an error`() {
        val me = StaticMe(favorites = listOf(track("a", "Alpha")))
        composeRule.setContent {
            HypeTheme {
                LibraryRoute(
                    onBlogClick = {},
                    onUserClick = {},
                    onLoginClick = {},
                    viewModel = LibraryViewModel(
                        authRepository = IncompleteLogoutAuth(),
                        meRepository = me,
                        playbackRepository = NoOpPlayback,
                        favoriteSyncManager = FavoriteSyncManager(me, NoOpPlayback),
                    ),
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 2_000) {
            composeRule.onAllNodesWithText("Alpha").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Log out").performClick()
        composeRule.onNodeWithText("Log out", substring = false).performClick()

        val errorMessage =
            "You are signed out, but some local account data could not be cleared. " +
                "Try again before another person uses this device."
        composeRule.waitUntil(timeoutMillis = 2_000) {
            composeRule.onAllNodesWithText(errorMessage).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(errorMessage)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, errorMessage))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    private fun track(id: String, title: String) = Track(
        id = id, artist = "x", title = title, lovedCount = 0,
        postedBy = "z", postedById = 0, postedCount = 0,
        postDescription = "", datePostedEpochSeconds = 0L,
        postUrl = "", itunesUrl = "",
    )
}

private class SignedOutAuth : AuthRepository {
    override val session: Flow<AuthSession?> = MutableStateFlow(null)
    override suspend fun login(usernameOrEmail: String, password: String): Result<AuthSession> = error("not used")
    override suspend fun logout() = Unit
}

private class SignedInAuth : AuthRepository {
    override val session: Flow<AuthSession?> = MutableStateFlow(AuthSession("alice", "tok"))
    override suspend fun login(usernameOrEmail: String, password: String): Result<AuthSession> = error("not used")
    override suspend fun logout() = Unit
}

private class IncompleteLogoutAuth : AuthRepository {
    private val currentSession = MutableStateFlow<AuthSession?>(AuthSession("alice", "tok"))
    override val session: Flow<AuthSession?> = currentSession
    override suspend fun login(usernameOrEmail: String, password: String): Result<AuthSession> = error("not used")
    override suspend fun logout() {
        currentSession.value = null
        error("local cleanup failed")
    }
}

private class StaticMe(
    private val favorites: List<Track> = emptyList(),
) : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> =
        if (page == 1) favorites else emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private object EmptyMe : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
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
