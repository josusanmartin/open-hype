package dev.josu.hypecar.core.data.repository

import dev.josu.hypecar.core.data.local.dao.HistoryDao
import dev.josu.hypecar.core.data.local.dao.PlaylistDao
import dev.josu.hypecar.core.data.local.dao.TrackDao
import dev.josu.hypecar.core.data.local.dao.TrackListDao
import dev.josu.hypecar.core.data.local.entity.HistoryEntity
import dev.josu.hypecar.core.data.local.entity.PlaylistNameEntity
import dev.josu.hypecar.core.data.local.entity.TrackEntity
import dev.josu.hypecar.core.data.local.entity.TrackListEntity
import dev.josu.hypecar.core.network.HypeApiService
import dev.josu.hypecar.core.network.dto.BlogDto
import dev.josu.hypecar.core.network.dto.GetTokenResponseDto
import dev.josu.hypecar.core.network.dto.TrackDto
import dev.josu.hypecar.core.network.dto.UserDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test

class RepositoryCancellationTest {
    @Test
    fun `favorite toggles preserve coroutine cancellation`() {
        val repository = DefaultMeRepository(
            api = CancellingFavoriteApi,
            trackDao = CancellationEmptyTrackDao,
            trackListDao = CancellationEmptyTrackListDao,
            playlistDao = CancellationEmptyPlaylistDao,
            historyDao = CancellationEmptyHistoryDao,
            json = Json,
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { repository.toggleFavorite("39v49") }
        }
    }
}

private object CancellingFavoriteApi : CancellationEmptyHypeApiService() {
    override suspend fun toggleFavorite(value: String, type: String, authToken: String?): ResponseBody =
        throw CancellationException("test cancellation")
}

private object CancellationEmptyTrackDao : TrackDao {
    override suspend fun upsertAll(items: List<TrackEntity>) = Unit
    override suspend fun byIds(ids: List<String>): List<TrackEntity> = emptyList()
    override suspend fun byId(id: String): TrackEntity? = null
}

private object CancellationEmptyTrackListDao : TrackListDao {
    override suspend fun upsert(item: TrackListEntity) = Unit
    override suspend fun get(key: String): TrackListEntity? = null
    override suspend fun deleteByKeyPrefix(prefix: String) = Unit
}

private object CancellationEmptyPlaylistDao : PlaylistDao {
    override suspend fun getAll(): List<PlaylistNameEntity> = emptyList()
    override suspend fun upsertAll(items: List<PlaylistNameEntity>) = Unit
    override suspend fun clear() = Unit
}

private object CancellationEmptyHistoryDao : HistoryDao {
    override suspend fun upsert(item: HistoryEntity) = Unit
    override suspend fun recent(limit: Int, offset: Int): List<HistoryEntity> = emptyList()
}

private abstract class CancellationEmptyHypeApiService : HypeApiService {
    override suspend fun getToken(username: String, password: String, deviceId: String): GetTokenResponseDto = error("Not used")
    override suspend fun tracks(params: Map<String, String>): List<TrackDto> = emptyList()
    override suspend fun track(trackId: String, authToken: String?): TrackDto = error("Not used")
    override suspend fun popular(params: Map<String, String>): List<TrackDto> = emptyList()
    override suspend fun favorites(params: Map<String, String>): List<TrackDto> = emptyList()
    override suspend fun toggleFavorite(value: String, type: String, authToken: String?): ResponseBody =
        "0".toResponseBody()
    override suspend fun playlistNames(): List<String> = emptyList()
    override suspend fun playlist(playlistId: Int, params: Map<String, String>): List<TrackDto> = emptyList()
    override suspend fun feed(params: Map<String, String>): List<TrackDto> = emptyList()
    override suspend fun postHistory(type: String, itemId: String, position: Int, authToken: String?): ResponseBody = "0".toResponseBody()
    override suspend fun blogs(params: Map<String, String>): List<BlogDto> = emptyList()
    override suspend fun blog(blogId: Int): BlogDto = error("Not used")
    override suspend fun blogTracks(blogId: Int, params: Map<String, String>): List<TrackDto> = emptyList()
    override suspend fun user(username: String): UserDto = error("Not used")
    override suspend fun userFavorites(username: String, params: Map<String, String>): List<TrackDto> = emptyList()
    override suspend fun userFriends(username: String, params: Map<String, String>): List<UserDto> = emptyList()
}
