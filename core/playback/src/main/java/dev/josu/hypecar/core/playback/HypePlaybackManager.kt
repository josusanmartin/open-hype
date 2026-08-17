package dev.josu.hypecar.core.playback

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.josu.hypecar.core.model.MediaItemExtras
import dev.josu.hypecar.core.model.PlaybackErrorEvent
import dev.josu.hypecar.core.model.PlaybackItem
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.PlaybackRepeatMode
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.TrackThumbnails
import dev.josu.hypecar.core.model.repository.HistoryRepository
import dev.josu.hypecar.core.model.repository.OfflineRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@androidx.annotation.OptIn(UnstableApi::class)
class HypePlaybackManager @Inject constructor(
    @ApplicationContext context: Context,
    private val historyRepository: HistoryRepository,
    private val offlineRepository: OfflineRepository,
    private val foregroundServiceStarter: PlaybackForegroundServiceStarter,
) : PlaybackRepository {
    private companion object {
        const val Tag = "HypePlaybackManager"
        const val ListenQualificationMs = 3_000L

        val MusicAudioAttributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Consecutive [Player.Listener.onPlayerError]s without an intervening
     * READY state. Once every queue item has failed in a row, recovery stops
     * instead of looping error→skip→prepare forever under repeat-all (which
     * hammers the network and spams a snackbar per lap).
     */
    private var consecutivePlaybackErrors = 0
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            beginListenTracking(mediaItem?.let(::trackForMediaItem)?.id)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_READY) {
                consecutivePlaybackErrors = 0
            }
            if (events.shouldPublishFullQueue()) {
                publishQueueState()
            } else {
                publishProgressOnly()
            }
            syncListenTracking()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(Tag, "Player error while loading media", error)
            failCurrentListenTracking()
            val failedTrackId = currentTrack()?.id
            consecutivePlaybackErrors += 1
            val wholeQueueFailed = consecutivePlaybackErrors >= player.mediaItemCount.coerceAtLeast(1)
            val recoverable = player.hasNextMediaItem() && !wholeQueueFailed
            runCatching {
                if (recoverable) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    player.pause()
                }
            }.onFailure {
                Log.w(Tag, "Failed to recover from player error", it)
            }
            _queue.value = _queue.value.copy(
                transientError = PlaybackErrorEvent(
                    eventId = nextErrorEventId(),
                    trackId = failedTrackId,
                    recoverable = recoverable,
                ),
            )
            publishQueueState()
        }
    }

    private val playerDelegate = lazy(LazyThreadSafetyMode.NONE) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ExoPlayer must be initialized on the main thread"
        }
        ExoPlayer.Builder(context)
            .setAudioAttributes(MusicAudioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setPauseAtEndOfMediaItems(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also {
                it.addListener(playerListener)
            }
    }
    val player: ExoPlayer by playerDelegate
    internal val isPlayerInitialized: Boolean
        get() = playerDelegate.isInitialized()

    private val trackIndex = linkedMapOf<String, Track>()
    private var errorEventCounter: Long = 0
    private fun nextErrorEventId(): Long {
        errorEventCounter += 1
        return errorEventCounter
    }
    private val _queue = MutableStateFlow(PlaybackQueue())
    override val queue: StateFlow<PlaybackQueue> = _queue
    private var progressJob: Job? = null
    private val listenTracker = PlaybackListenTracker(ListenQualificationMs)
    private var listenJob: Job? = null
    private var listenJobIsPosting = false
    private var listenJobOperationId = 0L

    override suspend fun play(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) {
            runPlayerCommand("clear empty queue") {
                beginListenTracking(trackId = null)
                trackIndex.clear()
                player.stop()
                player.clearMediaItems()
            }
            return
        }

        val safeStartIndex = startIndex.coerceIn(0, tracks.lastIndex)
        // Cached-file lookups stat the disk; resolve them off the main thread
        // before entering the player command.
        val mediaItems = withContext(Dispatchers.IO) {
            tracks.map { track ->
                track.toMediaItem(offlineRepository.cachedAudioUri(track.id))
            }
        }
        runPlayerCommand("play") {
            foregroundServiceStarter.ensureStarted()
            // A new queue is a new playback occurrence even when it starts
            // with the same track and index as the previous queue.
            beginListenTracking(trackId = null)
            trackIndex.clear()
            consecutivePlaybackErrors = 0
            tracks.forEach { trackIndex[it.id] = it }
            player.setMediaItems(mediaItems, safeStartIndex, C.TIME_UNSET)
            player.prepare()
            player.playWhenReady = true
        }
    }

    override suspend fun playFromTrack(track: Track) {
        play(listOf(track))
    }

    override suspend fun togglePlayPause() {
        runPlayerCommand("toggle play pause") {
            if (player.currentMediaItem == null) return@runPlayerCommand
            if (player.isPlaying) {
                player.pause()
            } else {
                // Resuming can happen after the media service was torn down
                // (task swiped while paused); restart it so playback keeps its
                // session, notification, and foreground-service protection.
                foregroundServiceStarter.ensureStarted()
                player.play()
            }
        }
    }

    override suspend fun skipNext() {
        runPlayerCommand("skip next") {
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
            }
        }
    }

    override suspend fun skipPrevious() {
        runPlayerCommand("skip previous") {
            if (player.currentMediaItem != null) {
                // seekToPrevious applies ExoPlayer's restart-current threshold
                // — the same behavior the notification/car previous button
                // gets via COMMAND_SEEK_TO_PREVIOUS, so all surfaces agree.
                player.seekToPrevious()
            }
        }
    }

    override suspend fun seekTo(positionMs: Long) {
        runPlayerCommand("seek") {
            if (player.currentMediaItem != null) {
                player.seekTo(positionMs.coerceAtLeast(0L))
            }
        }
    }

    override suspend fun seekToQueueItem(index: Int) {
        runPlayerCommand("seek to queue item") {
            if (index in 0 until player.mediaItemCount) {
                player.seekTo(index, 0L)
            }
        }
    }

    override suspend fun toggleShuffle() {
        runPlayerCommand("toggle shuffle") {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
        }
    }

    override suspend fun cycleRepeatMode() {
        runPlayerCommand("cycle repeat") {
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    override suspend fun updateFavorite(trackId: String, isLoved: Boolean) = withContext(Dispatchers.Main.immediate) {
        val existing = trackForMediaId(trackId)
            ?: _queue.value.items.firstOrNull { it.track.id == trackId || it.mediaId == trackId }?.track
            ?: return@withContext
        val lovedCountDelta = when {
            isLoved && !existing.isLoved -> 1
            !isLoved && existing.isLoved -> -1
            else -> 0
        }
        val updated = existing.copy(
            isLoved = isLoved,
            lovedCount = (existing.lovedCount + lovedCountDelta).coerceAtLeast(0),
        )
        trackIndex[updated.id] = updated
        // The media session can outlive/recreate its callback. Keep the
        // authoritative favorite snapshot in the MediaItem as well as the
        // in-memory index so a recreated callback never falls back to stale
        // metadata extras.
        for (index in 0 until player.mediaItemCount) {
            val mediaItem = player.getMediaItemAt(index)
            if (mediaItem.mediaId.rawTrackId() != updated.id) continue
            val extras = Bundle(mediaItem.mediaMetadata.extras ?: Bundle()).apply {
                putBoolean(MediaItemExtras.IsLoved, updated.isLoved)
                putInt(MediaItemExtras.LovedCount, updated.lovedCount)
            }
            val mediaMetadata = mediaItem.mediaMetadata.buildUpon()
                .setExtras(extras)
                .build()
            player.replaceMediaItem(
                index,
                mediaItem.buildUpon()
                    .setMediaMetadata(mediaMetadata)
                    .build(),
            )
        }
        publishQueueState()
    }

    fun trackForMediaId(mediaId: String): Track? = trackIndex[mediaId] ?: trackIndex[mediaId.rawTrackId()]

    private fun publishQueueState() {
        val transient = _queue.value.transientError
        val nextQueue = runCatching {
            val items = player.currentTimeline.run {
                (0 until windowCount).mapNotNull { index ->
                    val mediaItem = player.getMediaItemAt(index)
                    val mediaId = mediaItem.mediaId
                    trackForMediaItem(mediaItem)?.let { PlaybackItem(it, mediaId) }
                }
            }
            PlaybackQueue(
                items = items,
                currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { it > 0 } ?: 0L,
                isShuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode.toPlaybackRepeatMode(),
                transientError = transient,
            )
        }.getOrElse {
            Log.w(Tag, "Failed to publish playback queue state", it)
            _queue.value.copy(isPlaying = false)
        }
        _queue.value = nextQueue
        syncProgressTicker(nextQueue.isPlaying)
    }

    override fun acknowledgePlaybackError(eventId: Long) {
        val current = _queue.value.transientError
        if (current?.eventId == eventId) {
            _queue.value = _queue.value.copy(transientError = null)
        }
    }

    private fun syncProgressTicker(isPlaying: Boolean) {
        if (!isPlaying) {
            progressJob?.cancel()
            progressJob = null
            return
        }
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                delay(1_000)
                publishProgressOnly()
            }
        }
    }

    private fun beginListenTracking(trackId: String?) {
        cancelListenJob()
        listenTracker.onMediaItemTransition(trackId)
    }

    private fun failCurrentListenTracking() {
        cancelListenJob()
        listenTracker.onPlaybackError(SystemClock.elapsedRealtime())
    }

    /**
     * Qualifies history from elapsed time spent actually playing, rather than
     * from position alone. A seek past three seconds therefore cannot create a
     * listen, while pauses and buffering do not consume the threshold.
     */
    private fun syncListenTracking() {
        val nowMs = SystemClock.elapsedRealtime()
        val isReadyAndPlaying = player.playbackState == Player.STATE_READY && player.isPlaying
        val pending = listenTracker.onPlaybackActiveChanged(isReadyAndPlaying, nowMs)

        if (!isReadyAndPlaying) {
            // Once qualified, let the single repository write finish even if
            // the user pauses. Transitions and errors still cancel it.
            if (!listenJobIsPosting) cancelListenJob()
            return
        }
        if (pending == null || listenJob?.isActive == true) return

        val operationId = ++listenJobOperationId
        listenJob = scope.launch {
            try {
                // A positive delay guarantees this launch suspends before the
                // field assignment above completes on Dispatchers.Main.immediate.
                delay(pending.remainingMs.coerceAtLeast(1L))
                val completionTimeMs = SystemClock.elapsedRealtime()
                val stillPlaying = player.playbackState == Player.STATE_READY && player.isPlaying
                listenTracker.onPlaybackActiveChanged(stillPlaying, completionTimeMs)
                if (!stillPlaying) return@launch

                val trackId = listenTracker.recordIfQualified(
                    sessionId = pending.sessionId,
                    nowMs = completionTimeMs,
                ) ?: return@launch
                listenJobIsPosting = true
                val positionSeconds = (player.currentPosition.coerceAtLeast(0L) / 1_000L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                runSuspendCatchingPreservingCancellation {
                    historyRepository.postListen(trackId, positionSeconds)
                }.onFailure {
                    Log.w(Tag, "Failed to post playback history for $trackId", it)
                }
            } finally {
                if (listenJobOperationId == operationId) {
                    listenJob = null
                    listenJobIsPosting = false
                }
            }
        }
    }

    private fun cancelListenJob() {
        listenJobOperationId += 1
        listenJob?.cancel()
        listenJob = null
        listenJobIsPosting = false
    }

    private fun publishProgressOnly() {
        val current = _queue.value
        val position = runCatching { player.currentPosition.coerceAtLeast(0L) }.getOrDefault(current.positionMs)
        val duration = runCatching { player.duration.takeIf { it > 0 } ?: 0L }.getOrDefault(current.durationMs)
        val playing = runCatching { player.isPlaying }.getOrDefault(current.isPlaying)
        if (
            position == current.positionMs &&
            duration == current.durationMs &&
            playing == current.isPlaying
        ) {
            return
        }
        _queue.value = current.copy(
            positionMs = position,
            durationMs = duration,
            isPlaying = playing,
        )
        if (!playing && progressJob?.isActive == true) {
            progressJob?.cancel()
            progressJob = null
        }
    }

    private fun currentTrack(): Track? {
        val mediaItem = player.currentMediaItem ?: return null
        return trackForMediaItem(mediaItem)
    }

    private fun trackForMediaItem(mediaItem: MediaItem): Track? {
        val mediaId = mediaItem.mediaId
        return trackForMediaId(mediaId) ?: mediaItem.toFallbackTrack()?.also { track ->
            trackIndex[track.id] = track
        }
    }

    /**
     * Runs a player mutation on the player's application thread. Media3
     * requires all Player access to happen on the thread the player was
     * created on (the main thread here); callers of this repository can be on
     * any dispatcher — e.g. [FavoriteSyncManager]'s IO scope or Auto's IO
     * callback scope — so the hop lives in one place instead of at every
     * call site.
     */
    private suspend fun runPlayerCommand(commandName: String, block: () -> Unit) =
        withContext(Dispatchers.Main.immediate) {
            runCatching(block).onFailure {
                Log.w(Tag, "Playback command failed: $commandName", it)
            }
            publishQueueState()
        }
}

internal data class PendingListen(
    val sessionId: Long,
    val remainingMs: Long,
)

/**
 * Small deterministic state machine for one playback occurrence. Time is
 * supplied by the caller so tests cover skips, failures, pauses, and repeats
 * without sleeping or depending on ExoPlayer's asynchronous test renderer.
 */
internal class PlaybackListenTracker(
    private val qualificationMs: Long,
) {
    private var sessionId = 0L
    private var trackId: String? = null
    private var playedMs = 0L
    private var activeSinceMs: Long? = null
    private var recorded = false
    private var failed = false

    init {
        require(qualificationMs > 0L)
    }

    fun onMediaItemTransition(trackId: String?) {
        sessionId += 1
        this.trackId = trackId
        playedMs = 0L
        activeSinceMs = null
        recorded = false
        failed = false
    }

    fun onPlaybackActiveChanged(isActive: Boolean, nowMs: Long): PendingListen? {
        accumulatePlayingTime(nowMs)
        activeSinceMs = if (isActive && canRecord()) nowMs else null
        return pendingListen()
    }

    fun onPlaybackError(nowMs: Long) {
        accumulatePlayingTime(nowMs)
        sessionId += 1
        activeSinceMs = null
        failed = true
    }

    fun recordIfQualified(sessionId: Long, nowMs: Long): String? {
        if (this.sessionId != sessionId || activeSinceMs == null || !canRecord()) return null
        accumulatePlayingTime(nowMs)
        if (playedMs < qualificationMs) return null

        recorded = true
        activeSinceMs = null
        return trackId
    }

    private fun accumulatePlayingTime(nowMs: Long) {
        val activeSince = activeSinceMs ?: return
        val elapsedMs = (nowMs - activeSince).coerceAtLeast(0L)
        playedMs = (playedMs + elapsedMs).coerceAtMost(qualificationMs)
        activeSinceMs = nowMs
    }

    private fun pendingListen(): PendingListen? {
        if (activeSinceMs == null || !canRecord()) return null
        return PendingListen(
            sessionId = sessionId,
            remainingMs = (qualificationMs - playedMs).coerceAtLeast(0L),
        )
    }

    private fun canRecord(): Boolean = trackId != null && !recorded && !failed
}

private fun Track.toMediaItem(cachedAudioUri: String?): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(cachedAudioUri ?: streamUrl())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(postedBy)
                .setArtworkUri(bestThumbnail()?.let(android.net.Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                // The Auto service reads these to seed the Now Playing heart
                // for phone-initiated playback; without is_loved a loved track
                // renders unfilled in the car and a heart tap un-loves it.
                .setExtras(
                    Bundle().apply {
                        putBoolean(MediaItemExtras.IsLoved, isLoved)
                        putInt(MediaItemExtras.BlogId, postedById)
                        putString(MediaItemExtras.BlogName, postedBy)
                        putInt(MediaItemExtras.LovedCount, lovedCount)
                    },
                )
                .build(),
        )
        .build()

internal fun MediaItem.toFallbackTrack(): Track? {
    val trackId = mediaId.rawTrackId().takeIf { it.isNotBlank() } ?: return null
    val extras = mediaMetadata.extras
    val albumTitle = mediaMetadata.albumTitle?.toString().orEmpty()
    return Track(
        id = trackId,
        artist = mediaMetadata.artist?.toString().orEmpty(),
        title = mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: trackId,
        lovedCount = extras?.getInt(MediaItemExtras.LovedCount, 0) ?: 0,
        postedBy = albumTitle.ifBlank { extras?.getString(MediaItemExtras.BlogName).orEmpty() },
        postedById = extras?.getInt(MediaItemExtras.BlogId, 0) ?: 0,
        postedCount = 0,
        postDescription = mediaMetadata.description?.toString().orEmpty(),
        datePostedEpochSeconds = 0L,
        postUrl = "",
        itunesUrl = "",
        thumbnails = TrackThumbnails(large = mediaMetadata.artworkUri?.toString()),
        isLoved = extras?.getBoolean(MediaItemExtras.IsLoved, false) ?: false,
    )
}

private fun String.rawTrackId(): String =
    removePrefix("track:")
        .substringBefore("?src=")

private fun Int.toPlaybackRepeatMode(): PlaybackRepeatMode =
    when (this) {
        Player.REPEAT_MODE_ALL -> PlaybackRepeatMode.ALL
        Player.REPEAT_MODE_ONE -> PlaybackRepeatMode.ONE
        else -> PlaybackRepeatMode.OFF
    }

private fun Player.Events.shouldPublishFullQueue(): Boolean =
    shouldPublishFullQueueForPlayerEvents(::contains)

internal fun shouldPublishFullQueueForPlayerEvents(containsEvent: (Int) -> Boolean): Boolean =
    containsEvent(Player.EVENT_TIMELINE_CHANGED) ||
        containsEvent(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
        containsEvent(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
        containsEvent(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
        containsEvent(Player.EVENT_IS_PLAYING_CHANGED) ||
        containsEvent(Player.EVENT_POSITION_DISCONTINUITY) ||
        containsEvent(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
        containsEvent(Player.EVENT_REPEAT_MODE_CHANGED) ||
        containsEvent(Player.EVENT_MEDIA_METADATA_CHANGED)
