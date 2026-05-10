package dev.josu.hypecar.feature.player

import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.PlaybackRepeatMode

data class PlayerScreenUiModel(
    val title: String,
    val artist: String,
    val sourceLabel: String,
    val description: String,
    val artworkUrl: String?,
    val queueLabel: String,
    val elapsedLabel: String,
    val remainingLabel: String,
    val positionMs: Long,
    val durationMs: Long,
    val progressFraction: Float,
    val isPlaying: Boolean,
    val isLoved: Boolean,
    val isShuffleEnabled: Boolean,
    val repeatMode: PlaybackRepeatMode,
) {
    companion object {
        fun fromQueue(queue: PlaybackQueue): PlayerScreenUiModel? {
            val current = queue.current?.track ?: return null
            val durationMs = queue.durationMs.coerceAtLeast(0L)
            val positionMs = queue.positionMs.coerceIn(0L, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
            val progressFraction = if (durationMs > 0) {
                (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            return PlayerScreenUiModel(
                title = current.title,
                artist = current.artist,
                sourceLabel = current.postedBy,
                description = current.postDescription,
                artworkUrl = current.bestThumbnail(),
                queueLabel = "Queue position ${queue.currentIndex + 1} / ${queue.items.size}",
                elapsedLabel = formatMs(positionMs),
                remainingLabel = "-${formatMs((durationMs - positionMs).coerceAtLeast(0L))}",
                positionMs = positionMs,
                durationMs = durationMs,
                progressFraction = progressFraction,
                isPlaying = queue.isPlaying,
                isLoved = current.isLoved,
                isShuffleEnabled = queue.isShuffleEnabled,
                repeatMode = queue.repeatMode,
            )
        }

        private fun formatMs(value: Long): String {
            val totalSeconds = value / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
    }
}
