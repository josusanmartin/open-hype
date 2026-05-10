package dev.josu.hypecar.core.model

data class PlaybackItem(
    val track: Track,
    val mediaId: String = track.id,
)

data class PlaybackQueue(
    val items: List<PlaybackItem> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
    val transientError: PlaybackErrorEvent? = null,
) {
    val current: PlaybackItem?
        get() = items.getOrNull(currentIndex)
}

/**
 * One-shot playback failure surfaced to the UI. The [eventId] lets observers
 * deduplicate emissions while still reacting to repeated errors of the same kind.
 */
data class PlaybackErrorEvent(
    val eventId: Long,
    val trackId: String?,
    val recoverable: Boolean,
)

enum class PlaybackRepeatMode {
    OFF,
    ALL,
    ONE,
}
