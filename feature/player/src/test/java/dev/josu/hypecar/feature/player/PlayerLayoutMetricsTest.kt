package dev.josu.hypecar.feature.player

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerLayoutMetricsTest {
    @Test
    fun `phone layout keeps artwork and controls comfortably inside the viewport`() {
        val metrics = PlayerLayoutMetrics.phone()

        assertThat(metrics.topBarHeight.value).isEqualTo(0f)
        assertThat(metrics.showsPhoneOverlayCollapseControl).isFalse()
        assertThat(metrics.artworkTopPadding.value).isAtLeast(28f)
        assertThat(metrics.artworkWidthFraction).isAtMost(0.70f)
        assertThat(metrics.titleHorizontalPadding).isAtMost(30.dp)
        assertThat(metrics.progressHorizontalPadding).isAtMost(30.dp)
        assertThat(metrics.bottomControlsReservedHeight).isAtMost(124.dp)
        assertThat(metrics.primaryControlSize).isAtMost(76.dp)
        assertThat(metrics.secondaryControlSize).isAtMost(52.dp)
    }
}
