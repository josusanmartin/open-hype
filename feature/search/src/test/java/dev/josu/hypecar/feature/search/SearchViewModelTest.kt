package dev.josu.hypecar.feature.search

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.SearchQuery
import dev.josu.hypecar.core.model.SearchSort
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.model.repository.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `query updates do not search before debounce window elapses`() = runTest {
        val search = RecordingSearchRepository(results = listOf(track("t1")))
        val vm = SearchViewModel(search, NoOpPlaybackRepository)

        vm.updateQuery("hello")
        advanceTimeBy(100)
        assertThat(search.calls).isEmpty()

        advanceUntilIdle()
        assertThat(search.calls.last()).isEqualTo("hello" to SearchSort.NEWEST)
        assertThat(vm.state.value.tracks.map { it.id }).containsExactly("t1")
    }

    @Test
    fun `rapid query changes only fire the last query after debounce`() = runTest {
        val search = RecordingSearchRepository(results = listOf(track("final")))
        val vm = SearchViewModel(search, NoOpPlaybackRepository)

        vm.updateQuery("h")
        vm.updateQuery("he")
        vm.updateQuery("hel")
        vm.updateQuery("hell")
        vm.updateQuery("hello")
        advanceUntilIdle()

        assertThat(search.calls).hasSize(1)
        assertThat(search.calls.single()).isEqualTo("hello" to SearchSort.NEWEST)
    }

    @Test
    fun `clearing the query stops the search and empties results`() = runTest {
        val search = RecordingSearchRepository(results = listOf(track("first")))
        val vm = SearchViewModel(search, NoOpPlaybackRepository)

        vm.updateQuery("hello")
        advanceUntilIdle()
        assertThat(vm.state.value.tracks).isNotEmpty()

        vm.updateQuery("")
        advanceUntilIdle()
        assertThat(vm.state.value.tracks).isEmpty()
        assertThat(vm.state.value.loading).isFalse()
    }

    @Test
    fun `updateSort triggers immediate search with new sort when query is non-blank`() = runTest {
        val search = RecordingSearchRepository(results = listOf(track("a")))
        val vm = SearchViewModel(search, NoOpPlaybackRepository)

        vm.updateQuery("hello")
        advanceUntilIdle()
        val initialCalls = search.calls.size

        vm.updateSort(SearchSort.MOST_FAVORITES)
        advanceUntilIdle()

        assertThat(search.calls.size).isEqualTo(initialCalls + 1)
        assertThat(search.calls.last()).isEqualTo("hello" to SearchSort.MOST_FAVORITES)
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

private class RecordingSearchRepository(
    private val results: List<Track>,
) : SearchRepository {
    val calls = mutableListOf<Pair<String, SearchSort>>()
    override suspend fun searchTracks(query: SearchQuery, page: Int, count: Int): List<Track> {
        calls += query.value to query.sort
        return results
    }
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
