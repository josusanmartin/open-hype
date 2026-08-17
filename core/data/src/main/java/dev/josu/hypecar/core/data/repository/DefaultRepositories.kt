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
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.User
import dev.josu.hypecar.core.model.repository.CatalogRepository
import dev.josu.hypecar.core.model.repository.HistoryRepository
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.SearchRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import dev.josu.hypecar.core.network.AuthTokenProvider
import dev.josu.hypecar.core.network.HypeApiService
import dev.josu.hypecar.core.network.dto.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody

class DefaultCatalogRepository(
    private val api: HypeApiService,
    private val trackDao: TrackDao,
    private val trackListDao: TrackListDao,
    private val json: Json,
    private val accountDataWriteGate: AccountDataWriteGate = AccountDataWriteGate(),
    private val favoriteStateCoordinator: FavoriteStateCoordinator = FavoriteStateCoordinator(accountDataWriteGate),
) : CatalogRepository {
    override suspend fun latest(mode: LatestMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> =
        cachedTrackList(
            key = "latest:${mode.apiValue}:$page:$count",
            forceRefresh = forceRefresh,
            fetch = { api.tracks(mapOf("mode" to mode.apiValue, "page" to page.toString(), "count" to count.toString())).map { it.toModel() } },
        )

    override suspend fun popular(mode: PopularMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> =
        cachedTrackList(
            key = "popular:${mode.apiValue}:$page:$count",
            forceRefresh = forceRefresh,
            fetch = { api.popular(mapOf("mode" to mode.apiValue, "page" to page.toString(), "count" to count.toString())).map { it.toModel() } },
        )

    override suspend fun track(trackId: String): Track {
        val generation = accountDataWriteGate.captureGeneration()
        val favoriteRead = favoriteStateCoordinator.captureRead()
        val cached = if (accountDataWriteGate.isActive()) {
            trackDao.byId(trackId)?.toModel()?.let { favoriteStateCoordinator.applyToCached(listOf(it)).single() }
        } else {
            null
        }
        if (cached != null && accountDataWriteGate.isCurrentAccount(generation)) return cached

        val track = favoriteStateCoordinator.reconcileNetwork(
            tracks = listOf(api.track(trackId).toModel()),
            token = favoriteRead,
        ).single()
        accountDataWriteGate.requireCurrentBoundary(generation)
        accountDataWriteGate.writeIfCurrent(generation) {
            cacheTracks(listOf(track))
        }
        return track
    }

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

    private suspend fun cachedTrackList(key: String, forceRefresh: Boolean = false, fetch: suspend () -> List<Track>): List<Track> {
        val generation = accountDataWriteGate.captureGeneration()
        val favoriteRead = favoriteStateCoordinator.captureRead()
        val cachedEntity = if (accountDataWriteGate.isActive()) trackListDao.get(key) else null
        val cachedIds = cachedEntity?.let { json.decodeTrackIdsOrNull(it.trackIdsJson) }
        val cached = cachedIds?.let { ids ->
            favoriteStateCoordinator.applyToCached(if (ids.isEmpty()) emptyList() else hydrateTracks(ids))
        }
        // Serve the cache only when the caller didn't force a refresh, the entry
        // is fresh, and hydration is complete (a partially-hydrated list means the
        // track table is missing rows for this entry — refetch rather than shrink).
        if (
            !forceRefresh &&
            cached != null &&
            cachedEntity.isFreshTrackList() &&
            cached.size == cachedIds.size &&
            accountDataWriteGate.isCurrentAccount(generation)
        ) {
            return cached
        }
        val result = runSuspendCatchingPreservingCancellation {
            val tracks = favoriteStateCoordinator.reconcileNetwork(fetch(), favoriteRead)
            accountDataWriteGate.requireCurrentBoundary(generation)
            accountDataWriteGate.writeIfCurrent(generation) {
                cacheTracks(tracks)
                trackListDao.upsert(
                    TrackListEntity(
                        key = key,
                        trackIdsJson = json.encodeToString(tracks.map { it.id }),
                        updatedAtEpochSeconds = nowEpochSeconds(),
                    ),
                )
            }
            tracks
        }
        val failure = result.exceptionOrNull()
        if (failure != null) {
            if (cached != null && accountDataWriteGate.isCurrentAccount(generation)) return cached
            throw failure
        }
        return result.getOrThrow()
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
    private val accountDataWriteGate: AccountDataWriteGate = AccountDataWriteGate(),
    private val favoriteStateCoordinator: FavoriteStateCoordinator = FavoriteStateCoordinator(accountDataWriteGate),
) : MeRepository,
    AccountScopedFavoriteRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> =
        cachedTrackList(
            key = "favorites:$page:$count",
            forceRefresh = forceRefresh,
            fetch = { api.favorites(mapOf("page" to page.toString(), "count" to count.toString())).map { it.toModel() } },
        )

    override suspend fun toggleFavorite(trackId: String): Boolean? {
        val accountAccess = accountDataWriteGate.captureAccountAccess()
        if (!accountAccess.isActive) return null
        return toggleFavoriteInternal(
            trackId = trackId,
            authToken = accountAccess.authToken,
            generation = accountAccess.generation,
        )
    }

    override suspend fun toggleFavoriteForAccount(
        trackId: String,
        authToken: String,
        accountGeneration: AccountDataWriteGate.Generation,
    ): Boolean? = toggleFavoriteInternal(trackId, authToken, accountGeneration)

    override suspend fun favoriteStateForAccount(
        trackId: String,
        authToken: String,
        accountGeneration: AccountDataWriteGate.Generation,
    ): Boolean? = runSuspendCatchingPreservingCancellation {
        if (!accountDataWriteGate.isCurrentAccount(accountGeneration)) return@runSuspendCatchingPreservingCancellation null
        val track = api.track(trackId, authToken = authToken).toModel()
        val written = accountDataWriteGate.writeIfCurrent(accountGeneration) {
            trackDao.upsertAll(listOf(track.toEntity()))
        }
        track.isLoved.takeIf { written }
    }.getOrNull()

    private suspend fun toggleFavoriteInternal(
        trackId: String,
        authToken: String?,
        generation: AccountDataWriteGate.Generation,
    ): Boolean? = try {
        runSuspendCatchingPreservingCancellation {
            // Invalidate membership before the non-idempotent request. If the
            // process dies after the server commits, a future launch must
            // refetch instead of trusting a fresh-but-stale favorites list.
            val invalidatedBeforeMutation = accountDataWriteGate.writeIfCurrent(generation) {
                trackListDao.deleteByKeyPrefix("favorites:")
            }
            if (!invalidatedBeforeMutation) return@runSuspendCatchingPreservingCancellation null
            val responseState = api.toggleFavorite(trackId, authToken = authToken).string().trim().toFavoriteState()
            val confirmedTrack = if (responseState == null) {
                runSuspendCatchingPreservingCancellation {
                    api.track(trackId, authToken = authToken).toModel()
                }.getOrNull()
            } else {
                null
            }
            val confirmed = responseState ?: confirmedTrack?.isLoved
            accountDataWriteGate.writeIfCurrent(generation) {
                if (confirmedTrack != null) {
                    trackDao.upsertAll(listOf(confirmedTrack.toEntity()))
                } else if (responseState != null) {
                    updateCachedFavorite(trackId, responseState)
                }
            }
            confirmed
        }.getOrNull()
    } finally {
        // A transport error can arrive after the server committed the
        // non-idempotent toggle. Invalidate membership on every attempted
        // mutation, including cancellation/lost-response paths, without
        // allowing cleanup failure to replace the original outcome.
        withContext(NonCancellable) {
            runCatching {
                accountDataWriteGate.writeIfCurrent(generation) {
                    trackListDao.deleteByKeyPrefix("favorites:")
                }
            }
        }
    }

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
        val generation = accountDataWriteGate.captureGeneration()
        check(accountDataWriteGate.isCurrentAccount(generation)) { "Playlist names require an active account" }
        val cached = playlistDao.getAll().map { it.toModel() }
        if (!accountDataWriteGate.isCurrentAccount(generation)) throw AccountBoundaryChangedCancellationException()
        val result = runSuspendCatchingPreservingCancellation {
            val playlists = api.playlistNames().mapIndexed { index, name ->
                Playlist(id = index + 1, name = name)
            }
            accountDataWriteGate.requireCurrentBoundary(generation)
            accountDataWriteGate.writeIfCurrent(generation) {
                playlistDao.replaceAll(playlists.map { it.toEntity(System.currentTimeMillis() / 1000) })
            }
            playlists
        }
        val failure = result.exceptionOrNull()
        if (failure != null) {
            if (accountDataWriteGate.isCurrentAccount(generation)) return cached
            throw failure
        }
        return result.getOrThrow()
    }

    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> =
        cachedTrackList(
            key = "playlist:$playlistId:$page:$count",
            forceRefresh = forceRefresh,
            fetch = { api.playlist(playlistId, mapOf("page" to page.toString(), "count" to count.toString())).map { it.toModel() } },
        )

    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> =
        cachedTrackList(
            key = "feed:${mode.apiValue}:$page:$count",
            forceRefresh = forceRefresh,
            fetch = { api.feed(mapOf("mode" to mode.apiValue, "page" to page.toString(), "count" to count.toString())).map { it.toModel() } },
        ).map { FeedItem(it) }

    override suspend fun history(page: Int, count: Int): List<Track> {
        val generation = accountDataWriteGate.captureGeneration()
        if (!accountDataWriteGate.isCurrentAccount(generation)) return emptyList()
        val safePage = page.coerceAtLeast(1)
        val safeCount = count.coerceAtLeast(1)
        val offset = (safePage - 1) * safeCount
        val ids = historyDao.recent(limit = safeCount, offset = offset).map { it.trackId }
        if (ids.isEmpty()) return emptyList()
        val indexed = trackDao.byIdsChunked(ids).associateBy { it.id }
        val tracks = favoriteStateCoordinator.applyToCached(ids.mapNotNull { indexed[it]?.toModel() })
        if (!accountDataWriteGate.isCurrentAccount(generation)) {
            throw AccountBoundaryChangedCancellationException()
        }
        return tracks
    }

    private suspend fun cachedTrackList(key: String, forceRefresh: Boolean = false, fetch: suspend () -> List<Track>): List<Track> {
        val generation = accountDataWriteGate.captureGeneration()
        check(accountDataWriteGate.isCurrentAccount(generation)) { "Private library data requires an active account" }
        val favoriteRead = favoriteStateCoordinator.captureRead()
        val cachedEntity = trackListDao.get(key)
        val cachedIds = cachedEntity?.let { json.decodeTrackIdsOrNull(it.trackIdsJson) }
        val cached = cachedIds?.let { ids ->
            val indexed = trackDao.byIdsChunked(ids).associateBy { it.id }
            reconcileFavoriteMembership(
                key,
                favoriteStateCoordinator.applyToCached(ids.mapNotNull { indexed[it]?.toModel() }),
            )
        }
        if (
            !forceRefresh &&
            cached != null &&
            cachedEntity.isFreshTrackList() &&
            cached.size == cachedIds.size &&
            accountDataWriteGate.isCurrentAccount(generation)
        ) {
            return cached
        }
        val result = runSuspendCatchingPreservingCancellation {
            val tracks = reconcileFavoriteMembership(
                key,
                favoriteStateCoordinator.reconcileNetwork(fetch(), favoriteRead),
            )
            accountDataWriteGate.requireCurrentBoundary(generation)
            accountDataWriteGate.writeIfCurrent(generation) {
                trackDao.upsertAll(tracks.map { it.toEntity() })
                trackListDao.upsert(
                    TrackListEntity(
                        key = key,
                        trackIdsJson = json.encodeToString(tracks.map { it.id }),
                        updatedAtEpochSeconds = nowEpochSeconds(),
                    ),
                )
            }
            tracks
        }
        val failure = result.exceptionOrNull()
        if (failure != null) {
            if (cached != null && accountDataWriteGate.isCurrentAccount(generation)) return cached
            throw failure
        }
        return result.getOrThrow()
    }

    private suspend fun reconcileFavoriteMembership(key: String, tracks: List<Track>): List<Track> {
        if (!key.startsWith("favorites:")) return tracks
        val states = favoriteStateCoordinator.currentStates()
        if (states.isEmpty()) return tracks
        val kept = tracks.filterNot { states[it.id] == false }
        val missingLovedIds = states
            .filterValues { it }
            .keys
            .minus(kept.mapTo(mutableSetOf(), Track::id))
        if (missingLovedIds.isEmpty()) return kept
        val additions = favoriteStateCoordinator.applyToCached(
            trackDao.byIdsChunked(missingLovedIds.toList()).map { it.toModel() },
        )
        return (kept + additions).distinctBy(Track::id)
    }
}

class DefaultSearchRepository(
    private val api: HypeApiService,
    private val trackDao: TrackDao,
    private val trackListDao: TrackListDao,
    private val json: Json,
    private val accountDataWriteGate: AccountDataWriteGate = AccountDataWriteGate(),
    private val favoriteStateCoordinator: FavoriteStateCoordinator = FavoriteStateCoordinator(accountDataWriteGate),
) : SearchRepository {
    override suspend fun searchTracks(query: SearchQuery, page: Int, count: Int): List<Track> {
        val generation = accountDataWriteGate.captureGeneration()
        val favoriteRead = favoriteStateCoordinator.captureRead()
        val cacheKey = "search:${query.value}:${query.sort.apiValue}:$page:$count"
        val cachedEntity = if (accountDataWriteGate.isActive()) trackListDao.get(cacheKey) else null
        val cachedIds = cachedEntity?.let { json.decodeTrackIdsOrNull(it.trackIdsJson) }
        val cached = cachedIds?.let { ids ->
            val indexed = trackDao.byIdsChunked(ids).associateBy { entity -> entity.id }
            favoriteStateCoordinator.applyToCached(ids.mapNotNull { id -> indexed[id]?.toModel() })
        }
        if (
            cached != null &&
            cachedEntity.isFreshTrackList() &&
            cached.size == cachedIds.size &&
            accountDataWriteGate.isCurrentAccount(generation)
        ) {
            return cached
        }
        val result = runSuspendCatchingPreservingCancellation {
            val tracks = favoriteStateCoordinator.reconcileNetwork(
                tracks = api.tracks(
                    mapOf(
                        "q" to query.value,
                        "sort" to query.sort.apiValue,
                        "page" to page.toString(),
                        "count" to count.toString(),
                    ),
                ).map { it.toModel() },
                token = favoriteRead,
            )
            accountDataWriteGate.requireCurrentBoundary(generation)
            accountDataWriteGate.writeIfCurrent(generation) {
                trackDao.upsertAll(tracks.map { it.toEntity() })
                trackListDao.upsert(
                    TrackListEntity(
                        key = cacheKey,
                        trackIdsJson = json.encodeToString(tracks.map { it.id }),
                        updatedAtEpochSeconds = nowEpochSeconds(),
                    ),
                )
            }
            tracks
        }
        val failure = result.exceptionOrNull()
        if (failure != null) {
            if (cached != null && accountDataWriteGate.isCurrentAccount(generation)) return cached
            throw failure
        }
        return result.getOrThrow()
    }
}

class DefaultHistoryRepository(
    private val api: HypeApiService,
    private val historyDao: HistoryDao,
    private val trackDao: TrackDao,
    private val accountDataWriteGate: AccountDataWriteGate = AccountDataWriteGate(),
    private val authTokenProvider: AuthTokenProvider? = null,
    private val beforeRemotePost: suspend () -> Unit = {},
) : HistoryRepository {
    override suspend fun postListen(trackId: String, positionSeconds: Int): Boolean {
        authTokenProvider?.awaitTokenInitialization()
        val generation = accountDataWriteGate.captureGeneration()
        // Pin the credential beside the generation. If logout/login happens
        // before OkHttp interception, this operation must never acquire the
        // next account's token.
        val authToken = authTokenProvider?.currentToken()?.takeUnless(String::isBlank)
        if (authTokenProvider != null && authToken == null) return false
        val recorded = accountDataWriteGate.writeIfCurrent(generation) {
            historyDao.upsert(
                HistoryEntity(
                    trackId = trackId,
                    lastPositionSeconds = positionSeconds,
                    // Millisecond precision prevents rapid skips in the same
                    // second from producing unstable recency pagination.
                    playedAtEpochSeconds = System.currentTimeMillis(),
                ),
            )
        }
        if (!recorded) return false
        beforeRemotePost()
        if (!accountDataWriteGate.isCurrentAccount(generation)) return false
        return runSuspendCatchingPreservingCancellation {
            api.postHistory(
                itemId = trackId,
                position = positionSeconds,
                authToken = authToken,
            ).toFlag()
        }.getOrDefault(false)
    }
}

private const val SqliteInClauseChunk = 500

private fun AccountDataWriteGate.requireCurrentBoundary(generation: AccountDataWriteGate.Generation) {
    if (!isCurrentBoundary(generation)) throw AccountBoundaryChangedCancellationException()
}

private class AccountBoundaryChangedCancellationException : CancellationException("Account boundary changed")

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

private const val TrackListFreshnessSeconds = 5 * 60L

private fun TrackListEntity?.isFreshTrackList(nowEpochSeconds: Long = nowEpochSeconds()): Boolean =
    this != null && nowEpochSeconds - updatedAtEpochSeconds <= TrackListFreshnessSeconds

private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000L
