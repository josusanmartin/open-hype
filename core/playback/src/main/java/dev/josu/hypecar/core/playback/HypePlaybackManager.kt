package dev.josu.hypecar.core.playback

import android.content.Context
import android.os.Bundle
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
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_READY) {
                consecutivePlaybackErrors = 0
            }
            if (events.shouldPublishFullQueue()) {
                publishQueueState()
            } else {
                publishProgressOnly()
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                currentTrack()?.let { track ->
                    scope.launch {
                        runSuspendCatchingPreservingCancellation {
                            historyRepository.postListen(track.id, 0)
                        }.onFailure {
                            Log.w(Tag, "Failed to post playback history for ${track.id}", it)
                        }
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(Tag, "Player error while loading media", error)
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

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(MusicAudioAttributes, true)
        .setHandleAudioBecomingNoisy(true)
        .setPauseAtEndOfMediaItems(false)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
        .also {
            it.addListener(playerListener)
        }

    private val trackIndex = linkedMapOf<String, Track>()
    private var errorEventCounter: Long = 0
    private fun nextErrorEventId(): Long {
        errorEventCounter += 1
        return errorEventCounter
    }
    private val _queue = MutableStateFlow(PlaybackQueue())
    override val queue: StateFlow<PlaybackQueue> = _queue
    private var progressJob: Job? = null

    override suspend fun play(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) {
            runPlayerCommand("clear empty queue") {
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
            player.setForegroundMode(true)
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

private fun MediaItem.toFallbackTrack(): Track? {
    val trackId = mediaId.rawTrackId().takeIf { it.isNotBlank() } ?: return null
    return Track(
        id = trackId,
        artist = mediaMetadata.artist?.toString().orEmpty(),
        title = mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: trackId,
        lovedCount = 0,
        postedBy = mediaMetadata.albumTitle?.toString().orEmpty(),
        postedById = 0,
        postedCount = 0,
        postDescription = "",
        datePostedEpochSeconds = 0L,
        postUrl = "",
        itunesUrl = "",
        thumbnails = TrackThumbnails(large = mediaMetadata.artworkUri?.toString()),
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
