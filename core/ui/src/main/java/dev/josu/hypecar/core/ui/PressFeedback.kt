package dev.josu.hypecar.core.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Small tactile scale feedback for high-frequency touch targets.
 *
 * This observes pointer presses without consuming them, so it can sit beside
 * `clickable`, `IconButton`, and `Surface(onClick)` without changing behavior.
 */
@Composable
fun Modifier.pressFeedback(
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    label: String = "pressFeedback",
): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) pressedScale else 1f,
        animationSpec = tween(
            durationMillis = if (pressed) 90 else 150,
            easing = FastOutSlowInEasing,
        ),
        label = label,
    )
    val feedbackModifier = if (enabled) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                try {
                    waitForUpOrCancellation()
                } finally {
                    pressed = false
                }
            }
        }
    } else {
        Modifier
    }
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(feedbackModifier)
}
