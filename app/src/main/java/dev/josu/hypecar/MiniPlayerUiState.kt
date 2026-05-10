package dev.josu.hypecar

import dev.josu.hypecar.core.model.PlaybackQueue

data class MiniPlayerUiState(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val isPlaying: Boolean,
    val progressFraction: Float,
) {
    companion object {
        fun fromQueue(queue: PlaybackQueue): MiniPlayerUiState? {
            val current = queue.current?.track ?: return null
            val progressFraction = if (queue.durationMs > 0) {
                (queue.positionMs.toFloat() / queue.durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            return MiniPlayerUiState(
                title = current.title,
                artist = current.artist,
                artworkUrl = current.bestThumbnail(),
                isPlaying = queue.isPlaying,
                progressFraction = progressFraction,
            )
        }
    }
}
