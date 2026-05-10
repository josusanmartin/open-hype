package dev.josu.hypecar.core.data.repository

import dev.josu.hypecar.core.data.local.dao.HistoryDao
import dev.josu.hypecar.core.data.local.dao.PlaylistDao
import dev.josu.hypecar.core.data.local.dao.TrackDao
import dev.josu.hypecar.core.data.local.dao.TrackListDao
import dev.josu.hypecar.core.data.local.entity.HistoryEntity
import dev.josu.hypecar.core.data.local.entity.TrackEntity
import dev.josu.hypecar.core.data.local.entity.TrackListEntity
import dev.josu.hypecar.core.model.Blog
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
import dev.josu.hypecar.core.model.LatestMode
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.PopularMode
import dev.josu.hypecar.core.model.SearchQuery
import dev.josu.hypecar.core.model.Tag
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.User
import dev.josu.hypecar.core.model.repository.CatalogRepository
import dev.josu.hypecar.core.model.repository.HistoryRepository
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.SearchRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import dev.josu.hypecar.core.network.HypeApiService
import dev.josu.hypecar.core.network.dto.toModel
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody

class DefaultCatalogRepository(
    private val api: HypeApiService,
    private val trackDao: TrackDao,
    private val trackListDao: TrackListDao,
    private val json: Json,
) : CatalogRepository {
    override suspend fun latest(mode: LatestMode, page: Int, count: Int): List<Track> =
        cachedTrackList(
            key = "latest:${mode.apiValue}:$page:$count",
            fetch = { api.tracks(mapOf("mode" to mode.apiValue, "page" to page.toString(), "count" to count.toString())).map { it.toModel() } },
        )

    override suspend fun popular(mode: PopularMode, page: Int, count: Int): List<Track> =
        cachedTrackList(
            key = "popular:${mode.apiValue}:$page:$count",
            fetch = { api.popular(mapOf("mode" to mode.apiValue, "page" to page.toString(), "count" to count.toString())).map { it.toModel() } },
        )

    override suspend fun track(trackId: String): Track =
        trackDao.byId(trackId)?.toModel()
            ?: api.track(trackId).toModel().also { cacheTracks(listOf(it)) }

    override suspend fun blogs(page: Int, count: Int): List<Blog> =
        api.blogs(mapOf("page" to page.toString(), "count" to count.toString())).map { it.toModel() }

    override suspend fun blog(blogId: Int): Blog = api.blog(blogId).toModel()

    override suspend fun blogTracks(blogId: Int, page: Int, count: Int): List<Track> =
        cachedTrackList(
            key = "blog:$blogId:$page:$count",
            fetch = { api.blogTracks(blogId, mapOf("page" to page.toString(), "count" to count.toString())).map { it.toModel() } },
        )

    override suspend fun user(username: String): User = api.user(username).toModel()

    override suspend fun userFavorites(username: String, page: Int, count: Int): List<Track> =
        cachedTrackList(
            key = "userFavorites:$username:$page:$count",
            fetch = { api.userFavorites(username, mapOf("page" to page.toString(), "count" to count.toString())).map { it.toModel() } },
        )

    override suspend fun userFriends(username: String, page: Int, count: Int): List<User> =
        api.userFriends(username, mapOf("page" to page.toString(), "count" to count.toString())).map { it.toModel() }

    override suspend fun tags(): List<Tag> = api.tags().map { it.toModel() }

    override suspend fun tagTracks(tag: String, page: Int, count: Int): List<Track> =
        cachedTrackList(
            key = "tag:$tag:$page:$count",
            fetch = { api.tagTracks(tag, mapOf("page" to page.toString(), "count" to count.toString())).map { it.toModel() } },
        )

    private suspend fun cachedTrackList(key: String, fetch: suspend () -> List<Track>): List<Track> {
        val cached = trackListDao.get(key)?.let { listEntity ->
            val ids = json.decodeTrackIdsOrNull(listEntity.trackIdsJson) ?: return@let null
            if (ids.isEmpty()) emptyList() else hydrateTracks(ids)
        }
        return runSuspendCatchingPreservingCancellation {
            fetch().also { tracks ->
                cacheTracks(tracks)
                trackListDao.upsert(
                    TrackListEntity(
                        key = key,
                        trackIdsJson = json.encodeToString(tracks.map { it.id }),
                        updatedAtEpochSeconds = System.currentTimeMillis() / 1000,
                    ),
                )
            }
        }.getOrElse { cached ?: throw it }
    }

    private suspend fun hydrateTracks(ids: List<String>): List<Track> {
        val indexed = trackDao.byIdsChunked(ids).associateBy { it.id }
        return ids.mapNotNull { indexed[it]?.toModel() }
    }

    private suspend fun cacheTracks(tracks: List<Track>) {
        trackDao.upsertAll(tracks.map { it.toEntity() })
    }
}

class DefaultMeRepository(
    private val api: HypeApiService,
    private val trackDao: TrackDao,
    private val trackListDao: TrackListDao,
    private val playlistDao: PlaylistDao,
    private val historyDao: HistoryDao,
    private val json: Json,
) : MeRepository {
    override suspend fun favorites(page: Int, count: Int): List<Track> =
        cachedTrackList(
            key = "favorites:$page:$count",
            fetch = { api.favorites(mapOf("page" to page.toString(), "count" to count.toString())).map { it.toModel() } },
        )

    override suspend fun toggleFavorite(trackId: String): Boolean? =
        runSuspendCatchingPreservingCancellation {
            val responseState = api.toggleFavorite(trackId).string().trim().toFavoriteState()
            if (responseState != null) {
                updateCachedFavorite(trackId, responseState)
                responseState
            } else {
                val confirmedTrack = runSuspendCatchingPreservingCancellation {
                    api.track(trackId).toModel()
                }.getOrNull()
                val confirmedState = confirmedTrack?.isLoved
                if (confirmedTrack != null) {
                    trackDao.upsertAll(listOf(confirmedTrack.toEntity()))
                }
                confirmedState
            }
        }.getOrNull()

    private suspend fun updateCachedFavorite(trackId: String, isLoved: Boolean) {
        val current = trackDao.byId(trackId)?.toModel() ?: return
        val lovedCountDelta = when {
            isLoved && !current.isLoved -> 1
            !isLoved && current.isLoved -> -1
            else -> 0
        }
        trackDao.upsertAll(
            listOf(
                current.copy(
                    isLoved = isLoved,
                    lovedCount = (current.lovedCount + lovedCountDelta).coerceAtLeast(0),
                ).toEntity(),
            ),
        )
    }

    override suspend fun playlistNames(): List<Playlist> {
        val cached = playlistDao.getAll().map { it.toModel() }
        return runSuspendCatchingPreservingCancellation {
            api.playlistNames().mapIndexed { index, name ->
                Playlist(id = index + 1, name = name)
            }.also { playlists ->
                playlistDao.replaceAll(playlists.map { it.toEntity(System.currentTimeMillis() / 1000) })
            }
        }.getOrElse { cached }
    }

    override suspend fun playlist(playlistId: Int, page: Int, count: Int): List<Track> =
        cachedTrackList(
            key = "playlist:$playlistId:$page:$count",
            fetch = { api.playlist(playlistId, mapOf("page" to page.toString(), "count" to count.toString())).map { it.toModel() } },
        )

    override suspend fun feed(mode: FeedMode, page: Int, count: Int): List<FeedItem> =
        cachedTrackList(
            key = "feed:${mode.apiValue}:$page:$count",
            fetch = { api.feed(mapOf("mode" to mode.apiValue, "page" to page.toString(), "count" to count.toString())).map { it.toModel() } },
        ).map { FeedItem(it) }

    override suspend fun history(page: Int, count: Int): List<Track> {
        val safePage = page.coerceAtLeast(1)
        val safeCount = count.coerceAtLeast(1)
        val offset = (safePage - 1) * safeCount
        val ids = historyDao.recent(limit = safeCount, offset = offset).map { it.trackId }
        if (ids.isEmpty()) return emptyList()
        val indexed = trackDao.byIdsChunked(ids).associateBy { it.id }
        return ids.mapNotNull { indexed[it]?.toModel() }
    }

    private suspend fun cachedTrackList(key: String, fetch: suspend () -> List<Track>): List<Track> {
        val cached = trackListDao.get(key)?.let { listEntity ->
            val ids = json.decodeTrackIdsOrNull(listEntity.trackIdsJson) ?: return@let null
            val indexed = trackDao.byIdsChunked(ids).associateBy { it.id }
            ids.mapNotNull { indexed[it]?.toModel() }
        }
        return runSuspendCatchingPreservingCancellation {
            fetch().also { tracks ->
                trackDao.upsertAll(tracks.map { it.toEntity() })
                trackListDao.upsert(
                    TrackListEntity(
                        key = key,
                        trackIdsJson = json.encodeToString(tracks.map { it.id }),
                        updatedAtEpochSeconds = System.currentTimeMillis() / 1000,
                    ),
                )
            }
        }.getOrElse { cached ?: throw it }
    }
}

class DefaultSearchRepository(
    private val api: HypeApiService,
    private val trackDao: TrackDao,
    private val trackListDao: TrackListDao,
    private val json: Json,
) : SearchRepository {
    override suspend fun searchTracks(query: SearchQuery, page: Int, count: Int): List<Track> {
        val cacheKey = "search:${query.value}:${query.sort.apiValue}:$page:$count"
        val cached = trackListDao.get(cacheKey)?.let {
            val ids = json.decodeTrackIdsOrNull(it.trackIdsJson) ?: return@let null
            val indexed = trackDao.byIdsChunked(ids).associateBy { entity -> entity.id }
            ids.mapNotNull { id -> indexed[id]?.toModel() }
        }
        return runSuspendCatchingPreservingCancellation {
            api.tracks(
                mapOf(
                    "q" to query.value,
                    "sort" to query.sort.apiValue,
                    "page" to page.toString(),
                    "count" to count.toString(),
                ),
            ).map { it.toModel() }.also { tracks ->
                trackDao.upsertAll(tracks.map { it.toEntity() })
                trackListDao.upsert(
                    TrackListEntity(
                        key = cacheKey,
                        trackIdsJson = json.encodeToString(tracks.map { it.id }),
                        updatedAtEpochSeconds = System.currentTimeMillis() / 1000,
                    ),
                )
            }
        }.getOrElse { cached ?: throw it }
    }
}

class DefaultHistoryRepository(
    private val api: HypeApiService,
    private val historyDao: HistoryDao,
    private val trackDao: TrackDao,
) : HistoryRepository {
    override suspend fun postListen(trackId: String, positionSeconds: Int): Boolean {
        historyDao.upsert(
            HistoryEntity(
                trackId = trackId,
                lastPositionSeconds = positionSeconds,
                playedAtEpochSeconds = System.currentTimeMillis() / 1000,
            ),
        )
        return runSuspendCatchingPreservingCancellation {
            api.postHistory(itemId = trackId, position = positionSeconds).toFlag()
        }.getOrDefault(false)
    }
}

private const val SqliteInClauseChunk = 500

internal suspend fun TrackDao.byIdsChunked(ids: List<String>): List<TrackEntity> {
    if (ids.isEmpty()) return emptyList()
    if (ids.size <= SqliteInClauseChunk) return byIds(ids)
    return ids.chunked(SqliteInClauseChunk).flatMap { byIds(it) }
}

private fun ResponseBody.toFlag(): Boolean = string().trim() == "1"

private fun String.toFavoriteState(): Boolean? =
    when (this) {
        "1" -> true
        "0" -> false
        else -> null
    }

private fun Json.decodeTrackIdsOrNull(raw: String): List<String>? =
    runCatching { decodeFromString<List<String>>(raw) }.getOrNull()
