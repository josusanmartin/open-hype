package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class DefaultMeRepositoryTest {
    @Test
    fun `toggleFavorite returns true and sends track id when api accepts request`() {
        runBlocking {
            val api = RecordingFavoriteApi(response = "1")
            val repository = DefaultMeRepository(
                api = api,
                trackDao = EmptyTrackDao,
                trackListDao = EmptyTrackListDao,
                playlistDao = EmptyPlaylistDao,
                historyDao = EmptyHistoryDao,
                json = Json,
            )

            val result = repository.toggleFavorite("39v49")

            assertThat(result).isTrue()
            assertThat(api.favoriteRequests).containsExactly("39v49" to "item")
        }
    }

    @Test
    fun `scoped favorite mutation pins captured token on toggle and confirmation`() {
        runBlocking {
            val gate = AccountDataWriteGate()
            val api = ConfirmingFavoriteApi(
                toggleResponse = "unknown",
                confirmedTrack = sampleTrackDto(id = "39v49", isLoved = true, lovedCount = 28),
            )
            val repository = DefaultMeRepository(
                api = api,
                trackDao = EmptyTrackDao,
                trackListDao = EmptyTrackListDao,
                playlistDao = EmptyPlaylistDao,
                historyDao = EmptyHistoryDao,
                json = Json,
                accountDataWriteGate = gate,
            )

            repository.toggleFavoriteForAccount(
                trackId = "39v49",
                authToken = "token-a",
                accountGeneration = gate.captureGeneration(),
            )

            assertThat(api.favoriteAuthTokens).containsExactly("token-a")
            assertThat(api.trackAuthTokens).containsExactly("token-a")
        }
    }

    @Test
    fun `scoped favorite response cannot write after account switch`() = runBlocking {
        val gate = AccountDataWriteGate()
        val trackDao = MutableTrackDao(
            sampleTrackEntity(id = "39v49", isLoved = false, lovedCount = 27),
        )
        val trackListDao = FakeTrackListDao().apply {
            upsert(
                TrackListEntity(
                    key = "favorites:1:30",
                    trackIdsJson = "[\"39v49\"]",
                    updatedAtEpochSeconds = 1L,
                ),
            )
        }
        val capturedGeneration = gate.captureGeneration()
        val api = SwitchingFavoriteApi {
            assertThat(trackListDao.get("favorites:1:30")).isNull()
            gate.deactivate()
            gate.activate()
        }
        val repository = DefaultMeRepository(
            api = api,
            trackDao = trackDao,
            trackListDao = trackListDao,
            playlistDao = EmptyPlaylistDao,
            historyDao = EmptyHistoryDao,
            json = Json,
            accountDataWriteGate = gate,
        )

        val result = repository.toggleFavoriteForAccount(
            trackId = "39v49",
            authToken = "token-a",
            accountGeneration = capturedGeneration,
        )

        assertThat(result).isTrue()
        assertThat(api.authTokens).containsExactly("token-a")
        assertThat(trackDao.byId("39v49")?.isLoved).isFalse()
        assertThat(trackListDao.get("favorites:1:30")).isNull()
    }

    @Test
    fun `toggleFavorite drops cached favorites lists so membership refetches`() {
        runBlocking {
            val trackListDao = FakeTrackListDao()
            trackListDao.upsert(
                TrackListEntity(
                    key = "favorites:1:30",
                    trackIdsJson = "[\"other\"]",
                    updatedAtEpochSeconds = System.currentTimeMillis() / 1000,
                ),
            )
            trackListDao.upsert(
                TrackListEntity(
                    key = "feed:all:1:30",
                    trackIdsJson = "[\"other\"]",
                    updatedAtEpochSeconds = System.currentTimeMillis() / 1000,
                ),
            )
            val repository = DefaultMeRepository(
                api = RecordingFavoriteApi(response = "1"),
                trackDao = EmptyTrackDao,
                trackListDao = trackListDao,
                playlistDao = EmptyPlaylistDao,
                historyDao = EmptyHistoryDao,
                json = Json,
            )

            repository.toggleFavorite("39v49")

            assertThat(trackListDao.get("favorites:1:30")).isNull()
            assertThat(trackListDao.get("feed:all:1:30")).isNotNull()
        }
    }

    @Test
    fun `toggleFavorite updates cached track love state when api succeeds`() {
        runBlocking {
            val trackDao = MutableTrackDao(
                sampleTrackEntity(id = "39v49", isLoved = false, lovedCount = 27),
            )
            val repository = DefaultMeRepository(
                api = RecordingFavoriteApi(response = "1"),
                trackDao = trackDao,
                trackListDao = EmptyTrackListDao,
                playlistDao = EmptyPlaylistDao,
                historyDao = EmptyHistoryDao,
                json = Json,
            )

            val result = repository.toggleFavorite("39v49")

            assertThat(result).isTrue()
            assertThat(trackDao.byId("39v49")?.isLoved).isTrue()
            assertThat(trackDao.byId("39v49")?.lovedCount).isEqualTo(28)
        }
    }

    @Test
    fun `toggleFavorite confirms state from refetched track when toggle body is ambiguous`() {
        runBlocking {
            val trackDao = MutableTrackDao(
                sampleTrackEntity(id = "39v49", isLoved = false, lovedCount = 27),
            )
            val api = ConfirmingFavoriteApi(
                toggleResponse = "unknown",
                confirmedTrack = sampleTrackDto(id = "39v49", isLoved = true, lovedCount = 28),
            )
            val repository = DefaultMeRepository(
                api = api,
                trackDao = trackDao,
                trackListDao = EmptyTrackListDao,
                playlistDao = EmptyPlaylistDao,
                historyDao = EmptyHistoryDao,
                json = Json,
            )

            val result = repository.toggleFavorite("39v49")

            assertThat(result).isTrue()
            assertThat(api.favoriteRequests).containsExactly("39v49" to "item")
            assertThat(api.trackRequests).containsExactly("39v49")
            assertThat(trackDao.byId("39v49")?.isLoved).isTrue()
            assertThat(trackDao.byId("39v49")?.lovedCount).isEqualTo(28)
        }
    }

    @Test
    fun `toggleFavorite trusts explicit toggle response over stale refetched track`() {
        runBlocking {
            val trackDao = MutableTrackDao(
                sampleTrackEntity(id = "39v49", isLoved = false, lovedCount = 27),
            )
            val api = ConfirmingFavoriteApi(
                toggleResponse = "1",
                confirmedTrack = sampleTrackDto(id = "39v49", isLoved = false, lovedCount = 27),
            )
            val repository = DefaultMeRepository(
                api = api,
                trackDao = trackDao,
                trackListDao = EmptyTrackListDao,
                playlistDao = EmptyPlaylistDao,
                historyDao = EmptyHistoryDao,
                json = Json,
            )

            val result = repository.toggleFavorite("39v49")

            assertThat(result).isTrue()
            assertThat(api.favoriteRequests).containsExactly("39v49" to "item")
            assertThat(api.trackRequests).isEmpty()
            assertThat(trackDao.byId("39v49")?.isLoved).isTrue()
            assertThat(trackDao.byId("39v49")?.lovedCount).isEqualTo(28)
        }
    }

    @Test
    fun `toggleFavorite returns zero response as fallback unlike state when confirmation fails`() {
        runBlocking {
            val trackDao = MutableTrackDao(
                sampleTrackEntity(id = "39v49", isLoved = true, lovedCount = 28),
            )
            val repository = DefaultMeRepository(
                api = RecordingFavoriteApi(response = "0"),
                trackDao = trackDao,
                trackListDao = EmptyTrackListDao,
                playlistDao = EmptyPlaylistDao,
                historyDao = EmptyHistoryDao,
                json = Json,
            )

            val result = repository.toggleFavorite("39v49")

            assertThat(result).isFalse()
            assertThat(trackDao.byId("39v49")?.isLoved).isFalse()
            assertThat(trackDao.byId("39v49")?.lovedCount).isEqualTo(27)
        }
    }

    @Test
    fun `toggleFavorite returns null when api rejects token`() = runBlocking {
        val trackListDao = FakeTrackListDao().apply {
            upsert(
                TrackListEntity(
                    key = "favorites:1:30",
                    trackIdsJson = "[]",
                    updatedAtEpochSeconds = System.currentTimeMillis() / 1000,
                ),
            )
        }
        val repository = DefaultMeRepository(
            api = UnauthorizedFavoriteApi,
            trackDao = EmptyTrackDao,
            trackListDao = trackListDao,
            playlistDao = EmptyPlaylistDao,
            historyDao = EmptyHistoryDao,
            json = Json,
        )

        val result = repository.toggleFavorite("39v49")

        assertThat(result).isNull()
        assertThat(trackListDao.get("favorites:1:30")).isNull()
    }
}

private class RecordingFavoriteApi(
    private val response: String,
) : EmptyHypeApiService() {
    val favoriteRequests = mutableListOf<Pair<String, String>>()
    val authTokens = mutableListOf<String?>()

    override suspend fun toggleFavorite(value: String, type: String, authToken: String?): ResponseBody {
        favoriteRequests += value to type
        authTokens += authToken
        return response.toResponseBody()
    }
}

private class ConfirmingFavoriteApi(
    private val toggleResponse: String,
    private val confirmedTrack: TrackDto,
) : EmptyHypeApiService() {
    val favoriteRequests = mutableListOf<Pair<String, String>>()
    val favoriteAuthTokens = mutableListOf<String?>()
    val trackRequests = mutableListOf<String>()
    val trackAuthTokens = mutableListOf<String?>()

    override suspend fun toggleFavorite(value: String, type: String, authToken: String?): ResponseBody {
        favoriteRequests += value to type
        favoriteAuthTokens += authToken
        return toggleResponse.toResponseBody()
    }

    override suspend fun track(trackId: String, authToken: String?): TrackDto {
        trackRequests += trackId
        trackAuthTokens += authToken
        return confirmedTrack
    }
}

private class SwitchingFavoriteApi(
    private val beforeResponse: suspend () -> Unit,
) : EmptyHypeApiService() {
    val authTokens = mutableListOf<String?>()

    override suspend fun toggleFavorite(value: String, type: String, authToken: String?): ResponseBody {
        authTokens += authToken
        beforeResponse()
        return "1".toResponseBody()
    }
}

private object UnauthorizedFavoriteApi : EmptyHypeApiService() {
    override suspend fun toggleFavorite(value: String, type: String, authToken: String?): ResponseBody =
        throw HttpException(Response.error<ResponseBody>(401, "Unauthorized".toResponseBody()))
}

private object EmptyTrackDao : TrackDao {
    override suspend fun upsertAll(items: List<TrackEntity>) = Unit
    override suspend fun byIds(ids: List<String>): List<TrackEntity> = emptyList()
    override suspend fun byId(id: String): TrackEntity? = null
}

private class MutableTrackDao(
    initialTrack: TrackEntity,
) : TrackDao {
    private val tracks = mutableMapOf(initialTrack.id to initialTrack)

    override suspend fun upsertAll(items: List<TrackEntity>) {
        items.forEach { tracks[it.id] = it }
    }

    override suspend fun byIds(ids: List<String>): List<TrackEntity> =
        ids.mapNotNull(tracks::get)

    override suspend fun byId(id: String): TrackEntity? = tracks[id]
}

private fun sampleTrackEntity(
    id: String,
    isLoved: Boolean,
    lovedCount: Int,
) = TrackEntity(
    id = id,
    artist = "L.A. Sagne",
    title = "Music In The Neighbourhood",
    lovedCount = lovedCount,
    postedBy = "Destroy//Exist",
    postedById = 22246,
    postedCount = 3,
    postDescription = "After a run of singles.",
    datePostedEpochSeconds = 1774723952,
    postUrl = "https://www.destroyexist.com/2026/03/la-sagne-music-in-neighbourhood.html",
    itunesUrl = "https://hypem.com/go/itunes_search/L.A.%20Sagne",
    thumbnailSmall = null,
    thumbnailMedium = null,
    thumbnailLarge = null,
    rank = null,
    viaUser = null,
    viaQuery = null,
    isLoved = isLoved,
    audioUnavailable = false,
    mediaType = null,
)

private fun sampleTrackDto(
    id: String,
    isLoved: Boolean,
    lovedCount: Int,
) = TrackDto(
    id = id,
    artist = "L.A. Sagne",
    title = "Music In The Neighbourhood",
    lovedCount = lovedCount,
    siteName = "Destroy//Exist",
    siteId = 22246,
    postedCount = 3,
    description = "After a run of singles.",
    datePosted = 1774723952,
    postUrl = "https://www.destroyexist.com/2026/03/la-sagne-music-in-neighbourhood.html",
    itunesUrl = "https://hypem.com/go/itunes_search/L.A.%20Sagne",
    lovedMe = if (isLoved) 1 else null,
)

private object EmptyTrackListDao : TrackListDao {
    override suspend fun upsert(item: TrackListEntity) = Unit
    override suspend fun get(key: String): TrackListEntity? = null
    override suspend fun deleteByKeyPrefix(prefix: String) = Unit
}

private object EmptyPlaylistDao : PlaylistDao {
    override suspend fun getAll(): List<PlaylistNameEntity> = emptyList()
    override suspend fun upsertAll(items: List<PlaylistNameEntity>) = Unit
    override suspend fun clear() = Unit
}

private object EmptyHistoryDao : HistoryDao {
    override suspend fun upsert(item: HistoryEntity) = Unit
    override suspend fun recent(limit: Int, offset: Int): List<HistoryEntity> = emptyList()
}

private abstract class EmptyHypeApiService : HypeApiService {
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
