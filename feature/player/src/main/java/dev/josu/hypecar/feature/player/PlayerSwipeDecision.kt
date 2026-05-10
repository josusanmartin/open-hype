package dev.josu.hypecar.feature.player

enum class PlayerSwipeDirection {
    PREVIOUS,
    NEXT,
}

object PlayerSwipeDecision {
    fun fromOffset(
        offsetPx: Float,
        thresholdPx: Float,
    ): PlayerSwipeDirection? = when {
        offsetPx >= thresholdPx -> PlayerSwipeDirection.PREVIOUS
        offsetPx <= -thresholdPx -> PlayerSwipeDirection.NEXT
        else -> null
    }
}
