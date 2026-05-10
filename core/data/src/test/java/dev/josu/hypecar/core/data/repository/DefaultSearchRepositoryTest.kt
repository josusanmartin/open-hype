package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.SearchQuery
import dev.josu.hypecar.core.model.SearchSort
import dev.josu.hypecar.core.network.dto.TrackDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.IOException

class DefaultSearchRepositoryTest {
    @Test
    fun `successful search caches results keyed by query and sort`() = runBlocking {
        val api = StubSearchApi(listOf(sampleTrackDto("a"), sampleTrackDto("b")))
        val trackDao = FakeTrackDao()
        val trackListDao = FakeTrackListDao()
        val repo = DefaultSearchRepository(api, trackDao, trackListDao, Json)

        val results = repo.searchTracks(SearchQuery("waves", SearchSort.NEWEST), page = 1, count = 30)

        assertThat(results.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(trackListDao.byKey).containsKey("search:waves:latest:1:30")
    }

    @Test
    fun `failure falls back to cached results from a prior successful search`() = runBlocking {
        val api = StubSearchApi(
            pages = listOf(listOf(sampleTrackDto("a"), sampleTrackDto("b")), emptyList()),
            errorOn = listOf(false, true),
        )
        val repo = DefaultSearchRepository(api, FakeTrackDao(), FakeTrackListDao(), Json)

        val first = repo.searchTracks(SearchQuery("waves", SearchSort.NEWEST), page = 1, count = 30)
        assertThat(first).hasSize(2)

        val cached = repo.searchTracks(SearchQuery("waves", SearchSort.NEWEST), page = 1, count = 30)
        assertThat(cached.map { it.id }).containsExactly("a", "b").inOrder()
    }
}

private class StubSearchApi(
    pages: List<List<TrackDto>>,
    errorOn: List<Boolean> = pages.map { false },
) : FakeHypeApiService() {
    private val responses = pages
    private val errors = errorOn
    private var call = 0
    constructor(initial: List<TrackDto>) : this(pages = listOf(initial))

    override suspend fun tracks(params: Map<String, String>): List<TrackDto> {
        val index = call.coerceAtMost(responses.lastIndex)
        call += 1
        if (errors.getOrNull(index) == true) throw IOException("offline")
        return responses[index]
    }
}
