package dev.josu.hypecar.feature.catalog

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.data.repository.FavoriteSyncManager
import dev.josu.hypecar.core.model.Blog
import dev.josu.hypecar.core.model.LatestMode
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.PopularMode
import dev.josu.hypecar.core.model.Tag
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
class LatestViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init refresh loads latest tracks for default mode`() = runTest {
        val catalog = ScriptedCatalogRepository(latest = listOf(track("a"), track("b")))
        val vm = LatestViewModel(catalog, NoOpPlaybackRepository, latestFavoriteSync())

        advanceUntilIdle()

        assertThat(vm.state.value.tracks.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.error).isNull()
        assertThat(catalog.latestCalls).containsExactly(LatestMode.ALL)
    }

    @Test
    fun `selectMode switches mode and refetches`() = runTest {
        val catalog = ScriptedCatalogRepository(latest = listOf(track("a")))
        val vm = LatestViewModel(catalog, NoOpPlaybackRepository, latestFavoriteSync())
        advanceUntilIdle()

        vm.selectMode(LatestMode.ONLY_REMIXES.ordinal)
        advanceUntilIdle()

        assertThat(vm.state.value.selectedIndex).isEqualTo(LatestMode.ONLY_REMIXES.ordinal)
        assertThat(catalog.latestCalls).containsExactly(LatestMode.ALL, LatestMode.ONLY_REMIXES).inOrder()
    }

    @Test
    fun `selectMode is a no-op when index is unchanged`() = runTest {
        val catalog = ScriptedCatalogRepository(latest = listOf(track("a")))
        val vm = LatestViewModel(catalog, NoOpPlaybackRepository, latestFavoriteSync())
        advanceUntilIdle()

        vm.selectMode(LatestMode.ALL.ordinal)
        advanceUntilIdle()

        assertThat(catalog.latestCalls).hasSize(1)
    }

    @Test
    fun `errors land in state with loading cleared`() = runTest {
        val catalog = ScriptedCatalogRepository(latestError = IOException("offline"))
        val vm = LatestViewModel(catalog, NoOpPlaybackRepository, latestFavoriteSync())

        advanceUntilIdle()

        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.error).isEqualTo("offline")
        assertThat(vm.state.value.tracks).isEmpty()
    }

    @Test
    fun `loadMore appends next page and advances cursor`() = runTest {
        val page1 = (1..30).map { track("p1-$it") }
        val page2 = (1..30).map { track("p2-$it") }
        val catalog = ScriptedCatalogRepository(
            latestPages = mapOf(1 to Result.success(page1), 2 to Result.success(page2)),
        )
        val vm = LatestViewModel(catalog, NoOpPlaybackRepository, latestFavoriteSync())
        advanceUntilIdle()
        assertThat(vm.state.value.tracks).hasSize(30)
        assertThat(vm.state.value.hasMore).isTrue()

        vm.loadMore()
        advanceUntilIdle()

        assertThat(vm.state.value.tracks).hasSize(60)
        assertThat(vm.state.value.tracks.last().id).isEqualTo("p2-30")
        assertThat(vm.state.value.nextPage).isEqualTo(3)
        assertThat(vm.state.value.hasMore).isTrue()
        assertThat(vm.state.value.loadingMore).isFalse()
    }

    @Test
    fun `loadMore short page sets hasMore false and stops further calls`() = runTest {
        val page1 = (1..30).map { track("p1-$it") }
        val page2 = (1..5).map { track("p2-$it") } // shorter than page size
        val catalog = ScriptedCatalogRepository(
            latestPages = mapOf(1 to Result.success(page1), 2 to Result.success(page2)),
        )
        val vm = LatestViewModel(catalog, NoOpPlaybackRepository, latestFavoriteSync())
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()
        assertThat(vm.state.value.hasMore).isFalse()

        vm.loadMore() // should be a no-op
        advanceUntilIdle()
        assertThat(catalog.latestPageCalls).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `loadMore failure keeps hasMore true so user can retry`() = runTest {
        val page1 = (1..30).map { track("p1-$it") }
        val catalog = ScriptedCatalogRepository(
            latestPages = mapOf(
                1 to Result.success(page1),
                2 to Result.failure(IOException("flaky")),
                3 to Result.success((1..30).map { track("p3-$it") }),
            ),
        )
        val vm = LatestViewModel(catalog, NoOpPlaybackRepository, latestFavoriteSync())
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()
        assertThat(vm.state.value.loadingMore).isFalse()
        assertThat(vm.state.value.hasMore).isTrue() // recoverable
        assertThat(vm.state.value.tracks).hasSize(30) // failure does not append

        // Subsequent retry advances to page 3 (nextPage was bumped only on success — actually not, per impl).
        // The retry uses page=2 again because nextPage isn't advanced on failure.
        vm.loadMore()
        advanceUntilIdle()
        assertThat(catalog.latestPageCalls).containsExactly(1, 2, 2).inOrder()
    }

    @Test
    fun `pull-to-refresh clears stuck loadingMore and re-enables pagination`() = runTest {
        val page1 = (1..30).map { track("p1-$it") }
        val catalog = ScriptedCatalogRepository(
            latestPages = mapOf(1 to Result.success(page1), 2 to Result.success((1..30).map { track("p2-$it") })),
        )
        val vm = LatestViewModel(catalog, NoOpPlaybackRepository, latestFavoriteSync())
        advanceUntilIdle()

        // Kick off a loadMore; before it can complete, pull-to-refresh.
        vm.loadMore()
        vm.pullToRefresh()
        advanceUntilIdle()

        assertThat(vm.state.value.loadingMore).isFalse()
        assertThat(vm.state.value.refreshing).isFalse()
        assertThat(vm.state.value.tracks).hasSize(30)

        // Now loadMore should still be reachable.
        vm.loadMore()
        advanceUntilIdle()
        assertThat(vm.state.value.tracks).hasSize(60)
    }

    @Test
    fun `play forwards current tracks at index to playback repository`() = runTest {
        val playback = RecordingPlaybackRepository()
        val catalog = ScriptedCatalogRepository(latest = listOf(track("a"), track("b"), track("c")))
        val vm = LatestViewModel(catalog, playback, latestFavoriteSync())
        advanceUntilIdle()

        vm.play(1)
        advanceUntilIdle()

        assertThat(playback.lastPlayed?.map { it.id }).containsExactly("a", "b", "c").inOrder()
        assertThat(playback.lastStartIndex).isEqualTo(1)
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

private class ScriptedCatalogRepository(
    private val latest: List<Track> = emptyList(),
    private val latestError: Throwable? = null,
    private val latestPages: Map<Int, Result<List<Track>>>? = null,
) : CatalogRepository {
    val latestCalls = mutableListOf<LatestMode>()
    val latestPageCalls = mutableListOf<Int>()

    override suspend fun latest(mode: LatestMode, page: Int, count: Int): List<Track> {
        latestCalls += mode
        latestPageCalls += page
        latestPages?.get(page)?.let { return it.getOrThrow() }
        latestError?.let { throw it }
        return latest
    }

    override suspend fun popular(mode: PopularMode, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun track(trackId: String): Track = error("not used")
    override suspend fun blogs(page: Int, count: Int): List<Blog> = emptyList()
    override suspend fun blog(blogId: Int): Blog = error("not used")
    override suspend fun blogTracks(blogId: Int, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun user(username: String): User = error("not used")
    override suspend fun userFavorites(username: String, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun userFriends(username: String, page: Int, count: Int): List<User> = emptyList()
    override suspend fun tags(): List<Tag> = emptyList()
    override suspend fun tagTracks(tag: String, page: Int, count: Int): List<Track> = emptyList()
}

private class RecordingPlaybackRepository : PlaybackRepository {
    override val queue: StateFlow<PlaybackQueue> = MutableStateFlow(PlaybackQueue())
    var lastPlayed: List<Track>? = null
        private set
    var lastStartIndex: Int = -1
        private set

    override suspend fun play(tracks: List<Track>, startIndex: Int) {
        lastPlayed = tracks
        lastStartIndex = startIndex
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

private fun latestFavoriteSync() = FavoriteSyncManager(LatestNoOpMe)

private object LatestNoOpMe : dev.josu.hypecar.core.model.repository.MeRepository {
    override suspend fun favorites(page: Int, count: Int): List<dev.josu.hypecar.core.model.Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<dev.josu.hypecar.core.model.Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int): List<dev.josu.hypecar.core.model.Track> = emptyList()
    override suspend fun feed(mode: dev.josu.hypecar.core.model.FeedMode, page: Int, count: Int): List<dev.josu.hypecar.core.model.FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<dev.josu.hypecar.core.model.Track> = emptyList()
}
