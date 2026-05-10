package dev.josu.hypecar.feature.library

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.AuthRepository
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `signed-out state stays empty for tabs that need a session`() = runTest {
        val auth = StubAuthRepository(MutableStateFlow(null))
        val me = ScriptedMeRepository(favorites = listOf(track("nope")))
        val vm = LibraryViewModel(auth, me, NoOpPlaybackRepository)

        advanceUntilIdle()

        assertThat(vm.state.value.session).isNull()
        assertThat(vm.state.value.tracks).isEmpty()
        assertThat(me.favoritesCalls).isEqualTo(0)
    }

    @Test
    fun `signed-in state pulls favorites by default`() = runTest {
        val sessionFlow = MutableStateFlow<AuthSession?>(AuthSession("alice", "tok"))
        val me = ScriptedMeRepository(favorites = listOf(track("a"), track("b")))
        val vm = LibraryViewModel(StubAuthRepository(sessionFlow), me, NoOpPlaybackRepository)

        advanceUntilIdle()

        assertThat(vm.state.value.tracks.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(vm.state.value.loading).isFalse()
        assertThat(me.favoritesCalls).isEqualTo(1)
    }

    @Test
    fun `selectTab switches to feed and refreshes`() = runTest {
        val sessionFlow = MutableStateFlow<AuthSession?>(AuthSession("alice", "tok"))
        val me = ScriptedMeRepository(
            favorites = listOf(track("fav1")),
            feed = listOf(FeedItem(track("feed1"))),
        )
        val vm = LibraryViewModel(StubAuthRepository(sessionFlow), me, NoOpPlaybackRepository)
        advanceUntilIdle()

        vm.selectTab(LibraryTab.FEED.ordinal)
        advanceUntilIdle()

        assertThat(vm.state.value.selectedTab).isEqualTo(LibraryTab.FEED)
        assertThat(vm.state.value.tracks.map { it.id }).containsExactly("feed1")
    }

    @Test
    fun `history tab works without a session`() = runTest {
        val sessionFlow = MutableStateFlow<AuthSession?>(null)
        val me = ScriptedMeRepository(history = listOf(track("h1"), track("h2")))
        val vm = LibraryViewModel(StubAuthRepository(sessionFlow), me, NoOpPlaybackRepository)
        advanceUntilIdle()

        vm.selectTab(LibraryTab.HISTORY.ordinal)
        advanceUntilIdle()

        assertThat(vm.state.value.tracks.map { it.id }).containsExactly("h1", "h2").inOrder()
    }

    @Test
    fun `errors during refresh land in state and clear loading`() = runTest {
        val me = ScriptedMeRepository(favoritesError = RuntimeException("boom"))
        val vm = LibraryViewModel(
            StubAuthRepository(MutableStateFlow(AuthSession("alice", "tok"))),
            me,
            NoOpPlaybackRepository,
        )

        advanceUntilIdle()

        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.error).isEqualTo("boom")
    }

    @Test
    fun `favorites loadMore appends next page and bumps cursor`() = runTest {
        val page1 = (1..30).map { track("p1-$it") }
        val page2 = (1..30).map { track("p2-$it") }
        val me = ScriptedMeRepository(
            favoritesPages = mapOf(1 to Result.success(page1), 2 to Result.success(page2)),
        )
        val vm = LibraryViewModel(
            StubAuthRepository(MutableStateFlow(AuthSession("alice", "tok"))),
            me,
            NoOpPlaybackRepository,
        )
        advanceUntilIdle()
        assertThat(vm.state.value.tracks).hasSize(30)

        vm.loadMore()
        advanceUntilIdle()

        assertThat(vm.state.value.tracks).hasSize(60)
        assertThat(vm.state.value.nextPage).isEqualTo(3)
        assertThat(vm.state.value.loadingMore).isFalse()
    }

    @Test
    fun `favorites loadMore failure does not permanently disable pagination`() = runTest {
        val page1 = (1..30).map { track("p1-$it") }
        val me = ScriptedMeRepository(
            favoritesPages = mapOf(
                1 to Result.success(page1),
                2 to Result.failure(RuntimeException("flaky")),
            ),
        )
        val vm = LibraryViewModel(
            StubAuthRepository(MutableStateFlow(AuthSession("alice", "tok"))),
            me,
            NoOpPlaybackRepository,
        )
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        assertThat(vm.state.value.loadingMore).isFalse()
        assertThat(vm.state.value.hasMore).isTrue()
        assertThat(vm.state.value.tracks).hasSize(30)
    }

    private fun track(id: String) = Track(
        id = id,
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
}

private class StubAuthRepository(private val sessionFlow: MutableStateFlow<AuthSession?>) : AuthRepository {
    override val session: Flow<AuthSession?> = sessionFlow
    override suspend fun login(usernameOrEmail: String, password: String): Result<AuthSession> = error("not used")
    override suspend fun logout() {
        sessionFlow.value = null
    }
}

private class ScriptedMeRepository(
    private val favorites: List<Track> = emptyList(),
    private val feed: List<FeedItem> = emptyList(),
    private val history: List<Track> = emptyList(),
    private val favoritesError: Throwable? = null,
    private val favoritesPages: Map<Int, Result<List<Track>>>? = null,
) : MeRepository {
    var favoritesCalls = 0
        private set
    val favoritesPageCalls = mutableListOf<Int>()

    override suspend fun favorites(page: Int, count: Int): List<Track> {
        favoritesCalls += 1
        favoritesPageCalls += page
        favoritesPages?.get(page)?.let { return it.getOrThrow() }
        favoritesError?.let { throw it }
        return favorites
    }
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int): List<FeedItem> = feed
    override suspend fun history(page: Int, count: Int): List<Track> = history
}

private object NoOpPlaybackRepository : PlaybackRepository {
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
