package dev.josu.hypecar.feature.catalog

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.data.repository.FavoriteSyncManager
import dev.josu.hypecar.core.model.Blog
import dev.josu.hypecar.core.model.LatestMode
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.PopularMode
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.User
import dev.josu.hypecar.core.model.repository.CatalogRepository
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PopularViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init refresh loads popular tracks for default mode`() = runTest {
        val catalog = ScriptedPopularCatalog(popular = listOf(track("p1"), track("p2")))
        val vm = PopularViewModel(catalog, NoOpPlayback, popularFavoriteSync())

        advanceUntilIdle()

        assertThat(vm.state.value.tracks.map { it.id }).containsExactly("p1", "p2").inOrder()
        assertThat(catalog.popularCalls).containsExactly(PopularMode.NOW)
    }

    @Test
    fun `selectMode swaps to lastweek and refetches`() = runTest {
        val catalog = ScriptedPopularCatalog(popular = listOf(track("a")))
        val vm = PopularViewModel(catalog, NoOpPlayback, popularFavoriteSync())
        advanceUntilIdle()

        vm.selectMode(PopularMode.LAST_WEEK.ordinal)
        advanceUntilIdle()

        assertThat(vm.state.value.selectedIndex).isEqualTo(PopularMode.LAST_WEEK.ordinal)
        assertThat(catalog.popularCalls).containsExactly(PopularMode.NOW, PopularMode.LAST_WEEK).inOrder()
    }

    @Test
    fun `failed mode switch clears rows owned by the previous mode`() = runTest {
        val catalog = ScriptedPopularCatalog(
            popularByMode = mapOf(
                PopularMode.NOW to Result.success(listOf(track("old-mode"))),
                PopularMode.LAST_WEEK to Result.failure(IOException("offline")),
            ),
        )
        val vm = PopularViewModel(catalog, NoOpPlayback, popularFavoriteSync())
        advanceUntilIdle()
        assertThat(vm.state.value.tracks.map(Track::id)).containsExactly("old-mode")

        vm.selectMode(PopularMode.LAST_WEEK.ordinal)
        advanceUntilIdle()

        assertThat(vm.state.value.tracks).isEmpty()
        assertThat(vm.state.value.error).isEqualTo(dev.josu.hypecar.core.model.UiErrorKind.Network)
    }

    @Test
    fun `selectMode is a no-op for the same index`() = runTest {
        val catalog = ScriptedPopularCatalog(popular = listOf(track("a")))
        val vm = PopularViewModel(catalog, NoOpPlayback, popularFavoriteSync())
        advanceUntilIdle()

        vm.selectMode(PopularMode.NOW.ordinal)
        advanceUntilIdle()

        assertThat(catalog.popularCalls).hasSize(1)
    }

    @Test
    fun `failed fetch lands in state with loading cleared`() = runTest {
        val catalog = ScriptedPopularCatalog(popularError = IOException("offline"))
        val vm = PopularViewModel(catalog, NoOpPlayback, popularFavoriteSync())

        advanceUntilIdle()

        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.error).isEqualTo(dev.josu.hypecar.core.model.UiErrorKind.Network)
    }

    @Test
    fun `pull-to-refresh during loadMore does not lock pagination`() = runTest {
        val page1 = (1..30).map { track("p1-$it") }
        val page2 = (1..30).map { track("p2-$it") }
        val catalog = ScriptedPopularCatalog(
            popularPages = mapOf(1 to Result.success(page1), 2 to Result.success(page2)),
        )
        val vm = PopularViewModel(catalog, NoOpPlayback, popularFavoriteSync())
        advanceUntilIdle()

        vm.loadMore()
        vm.pullToRefresh()
        advanceUntilIdle()
        assertThat(vm.state.value.loadingMore).isFalse()
        assertThat(vm.state.value.tracks).hasSize(30)

        vm.loadMore()
        advanceUntilIdle()
        assertThat(vm.state.value.tracks).hasSize(60)
    }

    @Test
    fun `loadMore failure leaves hasMore true so user can retry`() = runTest {
        val page1 = (1..30).map { track("p1-$it") }
        val catalog = ScriptedPopularCatalog(
            popularPages = mapOf(
                1 to Result.success(page1),
                2 to Result.failure(IOException("flaky")),
            ),
        )
        val vm = PopularViewModel(catalog, NoOpPlayback, popularFavoriteSync())
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()
        assertThat(vm.state.value.loadingMore).isFalse()
        assertThat(vm.state.value.hasMore).isTrue()
        assertThat(vm.state.value.tracks).hasSize(30)
    }

    @Test
    fun `loadMore de-duplicates a track repeated across moving popular pages`() = runTest {
        val page1 = (1..30).map { track("track-$it") }
        val page2 = listOf(track("track-30").copy(title = "fresh overlap")) +
            (31..59).map { track("track-$it") }
        val catalog = ScriptedPopularCatalog(
            popularPages = mapOf(1 to Result.success(page1), 2 to Result.success(page2)),
        )
        val vm = PopularViewModel(catalog, NoOpPlayback, popularFavoriteSync())
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        assertThat(vm.state.value.tracks).hasSize(59)
        assertThat(vm.state.value.tracks.map(Track::id).toSet()).hasSize(59)
        assertThat(vm.state.value.tracks.first { it.id == "track-30" }.title)
            .isEqualTo("fresh overlap")
    }

    private fun track(id: String) = Track(
        id = id, artist = "x", title = "y", lovedCount = 0,
        postedBy = "z", postedById = 0, postedCount = 0,
        postDescription = "", datePostedEpochSeconds = 0L,
        postUrl = "", itunesUrl = "",
    )
}

private class ScriptedPopularCatalog(
    private val popular: List<Track> = emptyList(),
    private val popularError: Throwable? = null,
    private val popularPages: Map<Int, Result<List<Track>>>? = null,
    private val popularByMode: Map<PopularMode, Result<List<Track>>> = emptyMap(),
) : CatalogRepository {
    val popularCalls = mutableListOf<PopularMode>()
    val popularPageCalls = mutableListOf<Int>()

    override suspend fun popular(mode: PopularMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> {
        popularCalls += mode
        popularPageCalls += page
        popularByMode[mode]?.let { return it.getOrThrow() }
        popularPages?.get(page)?.let { return it.getOrThrow() }
        popularError?.let { throw it }
        return popular
    }

    override suspend fun latest(mode: LatestMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun track(trackId: String): Track = error("not used")
    override suspend fun blogs(page: Int, count: Int): List<Blog> = emptyList()
    override suspend fun blog(blogId: Int): Blog = error("not used")
    override suspend fun blogTracks(blogId: Int, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun user(username: String): User = error("not used")
    override suspend fun userFavorites(username: String, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun userFriends(username: String, page: Int, count: Int): List<User> = emptyList()
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

private fun popularFavoriteSync() = FavoriteSyncManager(PopularNoOpMe, NoOpPlayback)

private object PopularNoOpMe : dev.josu.hypecar.core.model.repository.MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<dev.josu.hypecar.core.model.Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<dev.josu.hypecar.core.model.Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<dev.josu.hypecar.core.model.Track> = emptyList()
    override suspend fun feed(mode: dev.josu.hypecar.core.model.FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<dev.josu.hypecar.core.model.FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<dev.josu.hypecar.core.model.Track> = emptyList()
}
