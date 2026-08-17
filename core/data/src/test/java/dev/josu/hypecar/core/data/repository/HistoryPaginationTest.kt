package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.data.local.entity.HistoryEntity
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.network.dto.toModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test

class HistoryPaginationTest {
    @Test
    fun `history returns the requested page slice ordered by recency`() = runBlocking {
        val historyDao = FakeHistoryDao()
        val trackDao = FakeTrackDao()
        (1..7).forEach { i ->
            historyDao.upsert(
                HistoryEntity(
                    trackId = "t$i",
                    lastPositionSeconds = 0,
                    playedAtEpochSeconds = i.toLong(),
                ),
            )
            trackDao.upsertAll(listOf(sampleTrackDto("t$i").toModel().toEntity()))
        }
        val repo = DefaultMeRepository(
            api = object : FakeHypeApiService() {},
            trackDao = trackDao,
            trackListDao = FakeTrackListDao(),
            playlistDao = FakePlaylistDao(),
            historyDao = historyDao,
            json = Json,
        )

        val page1 = repo.history(page = 1, count = 3)
        val page2 = repo.history(page = 2, count = 3)
        val page3 = repo.history(page = 3, count = 3)

        assertThat(page1.map { it.id }).containsExactly("t7", "t6", "t5").inOrder()
        assertThat(page2.map { it.id }).containsExactly("t4", "t3", "t2").inOrder()
        assertThat(page3.map { it.id }).containsExactly("t1").inOrder()
    }

    @Test
    fun `history returns empty list when no history is recorded`() = runBlocking {
        val repo = DefaultMeRepository(
            api = object : FakeHypeApiService() {},
            trackDao = FakeTrackDao(),
            trackListDao = FakeTrackListDao(),
            playlistDao = FakePlaylistDao(),
            historyDao = FakeHistoryDao(),
            json = Json,
        )

        assertThat(repo.history(page = 1, count = 10)).isEmpty()
    }

    @Test
    fun `history coerces invalid page and count to safe defaults`() = runBlocking {
        val historyDao = FakeHistoryDao()
        val trackDao = FakeTrackDao()
        historyDao.upsert(HistoryEntity("a", 0, 1))
        historyDao.upsert(HistoryEntity("b", 0, 2))
        trackDao.upsertAll(
            listOf(
                sampleTrackDto("a").toModel().toEntity(),
                sampleTrackDto("b").toModel().toEntity(),
            ),
        )
        val repo = DefaultMeRepository(
            api = object : FakeHypeApiService() {},
            trackDao = trackDao,
            trackListDao = FakeTrackListDao(),
            playlistDao = FakePlaylistDao(),
            historyDao = historyDao,
            json = Json,
        )

        val tracks = repo.history(page = 0, count = 0)
        assertThat(tracks).isNotEmpty()
    }

    @Test
    fun `history does not read retained rows while account gate is inactive`() = runBlocking {
        val historyDao = FakeHistoryDao().apply {
            upsert(HistoryEntity("account-a", 0, 1))
        }
        val trackDao = FakeTrackDao().apply {
            upsertAll(listOf(sampleTrackDto("account-a", isLoved = true).toModel().toEntity()))
        }
        val gate = AccountDataWriteGate(initiallyActive = false)
        val repo = DefaultMeRepository(
            api = object : FakeHypeApiService() {},
            trackDao = trackDao,
            trackListDao = FakeTrackListDao(),
            playlistDao = FakePlaylistDao(),
            historyDao = historyDao,
            json = Json,
            accountDataWriteGate = gate,
        )

        assertThat(repo.history(page = 1, count = 10)).isEmpty()
    }

    @Test
    fun `history uses track id as a stable tie breaker`() = runBlocking {
        val historyDao = FakeHistoryDao().apply {
            upsert(HistoryEntity("b", 0, 100))
            upsert(HistoryEntity("a", 0, 100))
        }
        val trackDao = FakeTrackDao().apply {
            upsertAll(
                listOf(
                    sampleTrackDto("a").toModel().toEntity(),
                    sampleTrackDto("b").toModel().toEntity(),
                ),
            )
        }
        val repo = DefaultMeRepository(
            api = object : FakeHypeApiService() {},
            trackDao = trackDao,
            trackListDao = FakeTrackListDao(),
            playlistDao = FakePlaylistDao(),
            historyDao = historyDao,
            json = Json,
        )

        assertThat(repo.history(page = 1, count = 10).map(Track::id))
            .containsExactly("a", "b").inOrder()
    }
}
