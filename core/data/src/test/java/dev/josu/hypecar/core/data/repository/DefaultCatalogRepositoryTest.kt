package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.LatestMode
import dev.josu.hypecar.core.network.dto.TrackDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.IOException

class DefaultCatalogRepositoryTest {
    @Test
    fun `latest caches successful fetch in track list`() = runBlocking {
        val api = StubLatestApi(listOf(sampleTrackDto("t1"), sampleTrackDto("t2")))
        val trackDao = FakeTrackDao()
        val trackListDao = FakeTrackListDao()
        val repo = DefaultCatalogRepository(api, trackDao, trackListDao, Json)

        val tracks = repo.latest(mode = LatestMode.ALL, page = 1, count = 30)

        assertThat(tracks.map { it.id }).containsExactly("t1", "t2").inOrder()
        assertThat(trackDao.tracks.keys).containsExactly("t1", "t2")
        assertThat(trackListDao.byKey).containsKey("latest:all:1:30")
    }

    @Test
    fun `latest returns cached tracks on network failure`() = runBlocking {
        val api = StubLatestApi(
            throwOn = listOf(false, true),
            pages = listOf(
                listOf(sampleTrackDto("t1"), sampleTrackDto("t2")),
                emptyList(),
            ),
        )
        val trackDao = FakeTrackDao()
        val trackListDao = FakeTrackListDao()
        val repo = DefaultCatalogRepository(api, trackDao, trackListDao, Json)

        val first = repo.latest(mode = LatestMode.ALL, page = 1, count = 30)
        assertThat(first).hasSize(2)

        val cached = repo.latest(mode = LatestMode.ALL, page = 1, count = 30)
        assertThat(cached.map { it.id }).containsExactly("t1", "t2").inOrder()
    }

    @Test
    fun `latest returns fresh cached tracks without hitting network again`() = runBlocking {
        val api = StubLatestApi(
            pages = listOf(
                listOf(sampleTrackDto("t1")),
                listOf(sampleTrackDto("network")),
            ),
        )
        val trackDao = FakeTrackDao()
        val trackListDao = FakeTrackListDao()
        val repo = DefaultCatalogRepository(api, trackDao, trackListDao, Json)

        val first = repo.latest(mode = LatestMode.ALL, page = 1, count = 30)
        val second = repo.latest(mode = LatestMode.ALL, page = 1, count = 30)

        assertThat(first.map { it.id }).containsExactly("t1")
        assertThat(second.map { it.id }).containsExactly("t1")
        assertThat(api.callCount).isEqualTo(1)
    }

    @Test
    fun `forceRefresh bypasses a fresh cache and hits the network`() = runBlocking {
        val api = StubLatestApi(
            pages = listOf(
                listOf(sampleTrackDto("t1")),
                listOf(sampleTrackDto("network")),
            ),
        )
        val repo = DefaultCatalogRepository(api, FakeTrackDao(), FakeTrackListDao(), Json)

        repo.latest(mode = LatestMode.ALL, page = 1, count = 30)
        val second = repo.latest(mode = LatestMode.ALL, page = 1, count = 30, forceRefresh = true)

        assertThat(second.map { it.id }).containsExactly("network")
        assertThat(api.callCount).isEqualTo(2)
    }

    @Test
    fun `latest rethrows when nothing is cached`() = runBlocking {
        val api = StubLatestApi(throwOn = listOf(true), pages = listOf(emptyList()))
        val repo = DefaultCatalogRepository(api, FakeTrackDao(), FakeTrackListDao(), Json)

        try {
            repo.latest(mode = LatestMode.ALL, page = 1, count = 30)
            error("expected exception")
        } catch (expected: IOException) {
            assertThat(expected).hasMessageThat().isEqualTo("offline")
        }
    }
}

private class StubLatestApi(
    pages: List<List<TrackDto>>,
    throwOn: List<Boolean> = pages.map { false },
) : FakeHypeApiService() {
    private val responses = pages
    private val errors = throwOn
    var callCount = 0
        private set
    constructor(initial: List<TrackDto>) : this(pages = listOf(initial))

    override suspend fun tracks(params: Map<String, String>): List<TrackDto> {
        val index = callCount.coerceAtMost(responses.lastIndex)
        callCount += 1
        if (errors.getOrNull(index) == true) throw IOException("offline")
        return responses[index]
    }
}
