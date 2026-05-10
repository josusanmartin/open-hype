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
    private var call = 0
    constructor(initial: List<TrackDto>) : this(pages = listOf(initial))

    override suspend fun tracks(params: Map<String, String>): List<TrackDto> {
        val index = call.coerceAtMost(responses.lastIndex)
        call += 1
        if (errors.getOrNull(index) == true) throw IOException("offline")
        return responses[index]
    }
}
