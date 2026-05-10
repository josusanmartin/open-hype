package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.data.local.dao.TrackDao
import dev.josu.hypecar.core.data.local.entity.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TrackDaoChunkingTest {
    @Test
    fun `byIdsChunked passes small lists through unchanged`() = runBlocking {
        val dao = RecordingChunkedTrackDao()
        dao.tracks["a"] = sample("a")
        dao.tracks["b"] = sample("b")

        val results = dao.byIdsChunked(listOf("a", "b", "missing"))

        assertThat(results.map { it.id }).containsExactly("a", "b")
        assertThat(dao.calls).hasSize(1)
    }

    @Test
    fun `byIdsChunked splits oversized id lists into 500-row chunks`() = runBlocking {
        val dao = RecordingChunkedTrackDao()
        val ids = (1..1234).map { "id-$it" }
        ids.forEach { dao.tracks[it] = sample(it) }

        val results = dao.byIdsChunked(ids)

        assertThat(results).hasSize(1234)
        assertThat(dao.calls.map { it.size }).containsExactly(500, 500, 234).inOrder()
    }

    @Test
    fun `byIdsChunked is a no-op for empty input`() = runBlocking {
        val dao = RecordingChunkedTrackDao()

        val results = dao.byIdsChunked(emptyList())

        assertThat(results).isEmpty()
        assertThat(dao.calls).isEmpty()
    }

    private fun sample(id: String) = TrackEntity(
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
        thumbnailSmall = null,
        thumbnailMedium = null,
        thumbnailLarge = null,
        rank = null,
        viaUser = null,
        viaQuery = null,
        isLoved = false,
        audioUnavailable = false,
        mediaType = null,
    )
}

private class RecordingChunkedTrackDao : TrackDao {
    val tracks = mutableMapOf<String, TrackEntity>()
    val calls = mutableListOf<List<String>>()
    override suspend fun upsertAll(items: List<TrackEntity>) {
        items.forEach { tracks[it.id] = it }
    }
    override suspend fun byIds(ids: List<String>): List<TrackEntity> {
        calls += ids
        return ids.mapNotNull(tracks::get)
    }
    override suspend fun byId(id: String): TrackEntity? = tracks[id]
}
