package dev.josu.hypecar.core.data.repository

import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralizes the optimistic-favorite logic that used to be copy-pasted into
 * `LatestRoute`, `PopularRoute`, and `LibraryRoute`. Each route now subscribes
 * to [edits] and folds incoming [FavoriteEdit]s onto its local track list via
 * [applyTo]. Toggling routes through [toggle], which:
 *
 *  1. Emits the *optimistic* edit immediately so all subscribed lists update.
 *  2. Fires the server request via [MeRepository.toggleFavorite].
 *  3. If the request fails or the server contradicts the optimistic value,
 *     emits a *revert* edit that undoes the optimistic delta and applies the
 *     confirmed/original value.
 *
 * Stays @Singleton so a single in-process [SharedFlow] is the source of truth
 * for the heart icon across every screen — when a user loves a track in
 * Latest, the same icon flips in Library, Feed, the player queue, and the
 * Android Auto session without each surface needing to know about the others.
 * Every emission is also folded into [PlaybackRepository] so the now-playing
 * queue (player screen, mini player, car Now Playing) stays in step with the
 * lists.
 */
@Singleton
class FavoriteSyncManager internal constructor(
    private val meRepository: MeRepository,
    private val playbackRepository: PlaybackRepository,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(meRepository: MeRepository, playbackRepository: PlaybackRepository) : this(
        meRepository = meRepository,
        playbackRepository = playbackRepository,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val _edits = MutableSharedFlow<FavoriteEdit>(
        // Buffer optimistic + revert pairs without dropping them if a slow
        // consumer is mid-fold.
        extraBufferCapacity = 64,
    )

    /** One-shot edits that consumers fold into their local lists. */
    val edits: SharedFlow<FavoriteEdit> = _edits.asSharedFlow()

    /**
     * Toggles the loved state of [track] with optimistic UI.
     *
     * The track passed in must carry its current `isLoved` and `lovedCount`
     * so the optimistic delta is computed correctly. Returns the optimistic
     * value (i.e., `!track.isLoved`); a revert edit is emitted later if the
     * server disagrees.
     */
    fun toggle(track: Track): Boolean {
        val optimistic = !track.isLoved
        val optimisticDelta = if (optimistic) 1 else -1
        scope.launch {
            publish(FavoriteEdit(track.id, optimistic, optimisticDelta))
            val confirmed = runSuspendCatchingPreservingCancellation {
                meRepository.toggleFavorite(track.id)
            }.getOrNull()
            if (confirmed != optimistic) {
                // Revert: subtract the optimistic delta and flip the loved
                // flag to the confirmed/original value. The net effect on a
                // list that already applied the optimistic edit is a return to
                // the last trustworthy state.
                publish(FavoriteEdit(track.id, confirmed ?: track.isLoved, -optimisticDelta))
            }
        }
        return optimistic
    }

    /**
     * Broadcasts an [edit] produced by a surface that manages its own server
     * sync (the player screen's reconciliation loop, the Android Auto heart)
     * so lists and the playback queue stay consistent with it. The playback
     * update is a no-op when the track isn't in the current queue.
     */
    suspend fun publish(edit: FavoriteEdit) {
        _edits.emit(edit)
        playbackRepository.updateFavorite(edit.trackId, edit.isLoved)
    }

    /**
     * Folds [edit] onto [tracks], returning a new list. The matching track's
     * `isLoved` is replaced with the edit's value and its `lovedCount` is
     * shifted by the edit's delta (clamped at 0).
     */
    fun applyTo(tracks: List<Track>, edit: FavoriteEdit): List<Track> =
        tracks.map { track ->
            if (track.id == edit.trackId) {
                track.copy(
                    isLoved = edit.isLoved,
                    lovedCount = (track.lovedCount + edit.lovedCountDelta).coerceAtLeast(0),
                )
            } else {
                track
            }
        }
}

/**
 * A single optimistic-or-confirmed favorite change emitted by
 * [FavoriteSyncManager]. [lovedCountDelta] is the *incremental* shift to
 * apply (e.g. +1 on optimistic love, -1 on revert).
 */
data class FavoriteEdit(
    val trackId: String,
    val isLoved: Boolean,
    val lovedCountDelta: Int,
)
