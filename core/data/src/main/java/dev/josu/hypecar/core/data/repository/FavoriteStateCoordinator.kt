package dev.josu.hypecar.core.data.repository

import dev.josu.hypecar.core.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Versioned account-scoped favorite truth shared by mutations and reads.
 *
 * A list request captures a token before it starts. When rows return, edits
 * newer than that token (or edits that were pending when it started) win over
 * the response. A later network response started after confirmation is
 * authoritative and retires the overlay. Cache hits always retain overlays.
 */
@Singleton
class FavoriteStateCoordinator @Inject constructor(
    private val accountDataWriteGate: AccountDataWriteGate,
) {
    private val lock = Any()
    private var version = 0L
    private val states = mutableMapOf<FavoriteStateKey, FavoriteState>()

    internal fun record(
        trackId: String,
        isLoved: Boolean,
        isPending: Boolean,
        generation: AccountDataWriteGate.Generation,
    ) {
        synchronized(lock) {
            // A delayed account-A completion must not prune or overwrite the
            // overlays already established for account B. Publication is also
            // gated, but coordinator state must reject the stale generation
            // independently because it is recorded before emission.
            if (!accountDataWriteGate.isCurrentAccount(generation)) return
            pruneLocked(generation)
            version += 1L
            states[FavoriteStateKey(generation, trackId)] = FavoriteState(
                isLoved = isLoved,
                version = version,
                isPending = isPending,
            )
        }
    }

    internal fun captureRead(): FavoriteReadToken {
        val generation = accountDataWriteGate.captureGeneration()
        return synchronized(lock) {
            pruneLocked(generation)
            FavoriteReadToken(
                generation = generation,
                version = version,
                pendingTrackIds = states
                    .filter { (key, state) -> key.generation == generation && state.isPending }
                    .keys
                    .mapTo(mutableSetOf(), FavoriteStateKey::trackId),
            )
        }
    }

    /** Applies every current overlay to a non-authoritative local cache hit. */
    internal fun applyToCached(tracks: List<Track>): List<Track> {
        val generation = accountDataWriteGate.captureGeneration()
        if (!accountDataWriteGate.isCurrentAccount(generation)) return tracks.distinctBy(Track::id)
        val snapshot = synchronized(lock) {
            pruneLocked(generation)
            statesForGenerationLocked(generation)
        }
        return tracks.applyFavoriteSnapshot(snapshot)
    }

    /**
     * Reconciles an actual network response and retires overlays that the
     * response was new enough to authoritatively supersede.
     */
    internal fun reconcileNetwork(
        tracks: List<Track>,
        token: FavoriteReadToken,
    ): List<Track> {
        if (!accountDataWriteGate.isCurrentBoundary(token.generation)) {
            return tracks
        }
        val overlays = synchronized(lock) {
            pruneLocked(token.generation)
            buildMap {
                tracks.forEach { track ->
                    val key = FavoriteStateKey(token.generation, track.id)
                    val state = states[key] ?: return@forEach
                    if (state.version > token.version || track.id in token.pendingTrackIds) {
                        put(track.id, state.isLoved)
                    } else {
                        states.remove(key)
                    }
                }
            }
        }
        return tracks.applyFavoriteSnapshot(overlays)
    }

    /** UI-level protection for repository fakes and responses racing an edit. */
    internal fun applyToFetchedForUi(
        tracks: List<Track>,
        token: FavoriteReadToken,
    ): List<Track> {
        if (!accountDataWriteGate.isCurrentBoundary(token.generation)) return tracks
        val overlays = synchronized(lock) {
            pruneLocked(token.generation)
            states
                .filter { (key, state) ->
                    key.generation == token.generation &&
                        (state.version > token.version || key.trackId in token.pendingTrackIds)
                }
                .mapKeys { it.key.trackId }
                .mapValues { it.value.isLoved }
        }
        return tracks.applyFavoriteSnapshot(overlays)
    }

    internal fun currentStates(): Map<String, Boolean> {
        val generation = accountDataWriteGate.captureGeneration()
        if (!accountDataWriteGate.isCurrentAccount(generation)) return emptyMap()
        return synchronized(lock) {
            pruneLocked(generation)
            statesForGenerationLocked(generation)
        }
    }

    private fun statesForGenerationLocked(
        generation: AccountDataWriteGate.Generation,
    ): Map<String, Boolean> = states
        .filterKeys { it.generation == generation }
        .mapKeys { it.key.trackId }
        .mapValues { it.value.isLoved }

    private fun pruneLocked(currentGeneration: AccountDataWriteGate.Generation) {
        states.keys.removeAll { it.generation != currentGeneration }
        if (!accountDataWriteGate.isCurrentAccount(currentGeneration)) states.clear()
    }
}

internal data class FavoriteReadToken(
    val generation: AccountDataWriteGate.Generation,
    val version: Long,
    val pendingTrackIds: Set<String>,
)

private data class FavoriteStateKey(
    val generation: AccountDataWriteGate.Generation,
    val trackId: String,
)

private data class FavoriteState(
    val isLoved: Boolean,
    val version: Long,
    val isPending: Boolean,
)

private fun List<Track>.applyFavoriteSnapshot(states: Map<String, Boolean>): List<Track> {
    val uniqueTracks = distinctBy(Track::id)
    if (states.isEmpty()) return uniqueTracks
    return uniqueTracks.map { track ->
        val isLoved = states[track.id] ?: return@map track
        if (isLoved == track.isLoved) {
            track
        } else {
            track.copy(
                isLoved = isLoved,
                lovedCount = (track.lovedCount + if (isLoved) 1 else -1).coerceAtLeast(0),
            )
        }
    }
}
