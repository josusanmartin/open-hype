package dev.josu.hypecar.core.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A shimmer-shaped placeholder for content that's still loading. Pulses
 * between two surface colors so the screen looks alive instead of static.
 *
 * Used to replace `CircularProgressIndicator` on list-heavy screens — the
 * indicator gives users no sense of what the loaded shape will look like,
 * while a skeleton matches the layout one-to-one.
 *
 * The semantics layer marks the placeholder as "Loading content" so
 * TalkBack can announce the state instead of staying silent on a blank
 * surface.
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    cornerRadius: Dp = 6.dp,
) {
    val pulse = rememberSkeletonPulse()
    SkeletonBlockContent(
        modifier = modifier,
        height = height,
        cornerRadius = cornerRadius,
        alpha = { pulse.value },
    )
}

/**
 * A track-row-shaped skeleton with a square cover-art placeholder and three
 * lines of varying widths approximating title / artist / stats.
 */
@Composable
fun SkeletonTrackRow(
    modifier: Modifier = Modifier,
    coverSize: Dp = 64.dp,
) {
    val pulse = rememberSkeletonPulse()
    val loadingLabel = stringResource(R.string.skeleton_loading)
    SkeletonTrackRowContent(
        modifier = modifier,
        coverSize = coverSize,
        alpha = { pulse.value },
        loadingLabel = loadingLabel,
    )
}

@Composable
private fun SkeletonTrackRowContent(
    modifier: Modifier,
    coverSize: Dp,
    alpha: () -> Float,
    loadingLabel: String?,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .then(
                if (loadingLabel != null) {
                    Modifier.semantics { contentDescription = loadingLabel }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .size(coverSize)
                .skeletonBackground(cornerRadius = 8.dp, alpha = alpha),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.padding(top = 4.dp)) {
            SkeletonBlockContent(
                modifier = Modifier.width(180.dp),
                height = 14.dp,
                cornerRadius = 6.dp,
                alpha = alpha,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SkeletonBlockContent(
                modifier = Modifier.width(120.dp),
                height = 12.dp,
                cornerRadius = 6.dp,
                alpha = alpha,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SkeletonBlockContent(
                modifier = Modifier.width(80.dp),
                height = 10.dp,
                cornerRadius = 6.dp,
                alpha = alpha,
            )
        }
    }
}

/**
 * Stacks [count] [SkeletonTrackRow]s — drop this in wherever a
 * CircularProgressIndicator + list-shaped target used to live.
 */
@Composable
fun SkeletonTrackList(
    modifier: Modifier = Modifier,
    count: Int = 6,
) {
    val pulse = rememberSkeletonPulse()
    val loadingLabel = stringResource(R.string.skeleton_loading)
    Column(
        modifier = modifier.semantics { contentDescription = loadingLabel },
    ) {
        repeat(count) {
            SkeletonTrackRowContent(
                modifier = Modifier,
                coverSize = 64.dp,
                alpha = { pulse.value },
                loadingLabel = null,
            )
        }
    }
}

@Composable
private fun rememberSkeletonPulse(): State<Float> {
    val transition = rememberInfiniteTransition(label = "skeletonPulse")
    return transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
}

@Composable
private fun SkeletonBlockContent(
    modifier: Modifier,
    height: Dp,
    cornerRadius: Dp,
    alpha: () -> Float,
) {
    Box(
        modifier = modifier
            .height(height)
            .skeletonBackground(cornerRadius = cornerRadius, alpha = alpha),
    )
}

private fun Modifier.skeletonBackground(
    cornerRadius: Dp,
    alpha: () -> Float,
): Modifier = drawWithCache {
    val radius = cornerRadius.toPx()
    onDrawBehind {
        drawRoundRect(
            color = SkeletonBaseColor,
            alpha = alpha(),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}

private val SkeletonBaseColor = Color(0xFF6E5F58)
