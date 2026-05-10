package dev.josu.hypecar.core.data.repository

import dev.josu.hypecar.core.data.local.dao.HistoryDao
import dev.josu.hypecar.core.data.local.dao.PlaylistDao
import dev.josu.hypecar.core.data.local.dao.TrackDao
import dev.josu.hypecar.core.data.local.dao.TrackListDao
import dev.josu.hypecar.core.data.local.entity.HistoryEntity
import dev.josu.hypecar.core.data.local.entity.PlaylistNameEntity
import dev.josu.hypecar.core.data.local.entity.TrackEntity
import dev.josu.hypecar.core.data.local.entity.TrackListEntity
import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.network.HypeApiService
import dev.josu.hypecar.core.network.dto.BlogDto
import dev.josu.hypecar.core.network.dto.GetTokenResponseDto
import dev.josu.hypecar.core.network.dto.TagDto
import dev.josu.hypecar.core.network.dto.TrackDto
import dev.josu.hypecar.core.network.dto.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody

internal class FakeSessionStore : SessionGateway {
    private val _session = MutableStateFlow<AuthSession?>(null)
    override val session: StateFlow<AuthSession?> = _session
    var savedSession: AuthSession?
        get() = _session.value
        set(value) {
            _session.value = value
        }
    var cleared: Boolean = false
        private set

    override suspend fun save(session: AuthSession) {
        _session.value = session
    }

    override suspend fun clear() {
        _session.value = null
        cleared = true
    }
}

internal class FakeTrackDao : TrackDao {
    val tracks = mutableMapOf<String, TrackEntity>()
    override suspend fun upsertAll(items: List<TrackEntity>) {
        items.forEach { tracks[it.id] = it }
    }
    override suspend fun byIds(ids: List<String>): List<TrackEntity> = ids.mapNotNull(tracks::get)
    override suspend fun byId(id: String): TrackEntity? = tracks[id]
}

internal class FakeTrackListDao : TrackListDao {
    val byKey = mutableMapOf<String, TrackListEntity>()
    override suspend fun upsert(item: TrackListEntity) {
        byKey[item.key] = item
    }
    override suspend fun get(key: String): TrackListEntity? = byKey[key]
}

internal class FakePlaylistDao : PlaylistDao {
    val all = mutableListOf<PlaylistNameEntity>()
    override suspend fun getAll(): List<PlaylistNameEntity> = all.toList()
    override suspend fun upsertAll(items: List<PlaylistNameEntity>) {
        items.forEach { entity ->
            val existing = all.indexOfFirst { it.id == entity.id }
            if (existing >= 0) all[existing] = entity else all += entity
        }
    }
    override suspend fun clear() {
        all.clear()
    }
}

internal class FakeHistoryDao : HistoryDao {
    val entries = mutableListOf<HistoryEntity>()
    override suspend fun upsert(item: HistoryEntity) {
        entries.removeAll { it.trackId == item.trackId }
        entries += item
    }
    override suspend fun recent(limit: Int, offset: Int): List<HistoryEntity> =
        entries.sortedByDescending { it.playedAtEpochSeconds }
            .drop(offset.coerceAtLeast(0))
            .take(limit.coerceAtLeast(0))
}

internal abstract class FakeHypeApiService : HypeApiService {
    override suspend fun getToken(username: String, password: String, deviceId: String): GetTokenResponseDto = error("Not used")
    override suspend fun tracks(params: Map<String, String>): List<TrackDto> = emptyList()
    override suspend fun track(trackId: String): TrackDto = error("Not used")
    override suspend fun popular(params: Map<String, String>): List<TrackDto> = emptyList()
    override suspend fun favorites(params: Map<String, String>): List<TrackDto> = emptyList()
    override suspend fun toggleFavorite(value: String, type: String): ResponseBody = "0".toResponseBody()
    override suspend fun playlistNames(): List<String> = emptyList()
    override suspend fun playlist(playlistId: Int, params: Map<String, String>): List<TrackDto> = emptyList()
    override suspend fun feed(params: Map<String, String>): List<TrackDto> = emptyList()
    override suspend fun postHistory(type: String, itemId: String, position: Int): ResponseBody = "0".toResponseBody()
    override suspend fun blogs(params: Map<String, String>): List<BlogDto> = emptyList()
    override suspend fun blog(blogId: Int): BlogDto = error("Not used")
    override suspend fun blogTracks(blogId: Int, params: Map<String, String>): List<TrackDto> = emptyList()
    override suspend fun user(username: String): UserDto = error("Not used")
    override suspend fun userFavorites(username: String, params: Map<String, String>): List<TrackDto> = emptyList()
    override suspend fun userFriends(username: String, params: Map<String, String>): List<UserDto> = emptyList()
    override suspend fun tags(): List<TagDto> = emptyList()
    override suspend fun tagTracks(tagName: String, params: Map<String, String>): List<TrackDto> = emptyList()
}

internal fun sampleTrackDto(
    id: String,
    title: String = "Track $id",
    artist: String = "Artist $id",
    isLoved: Boolean = false,
    audioUnavailable: Boolean = false,
) = TrackDto(
    id = id,
    artist = artist,
    title = title,
    lovedCount = 0,
    siteName = "Blog",
    siteId = 1,
    postedCount = 0,
    description = null,
    datePosted = 0,
    postUrl = null,
    itunesUrl = null,
    lovedMe = if (isLoved) 1 else null,
    audioUnavailable = audioUnavailable,
)
