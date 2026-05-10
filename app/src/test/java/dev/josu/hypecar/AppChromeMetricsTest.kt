package dev.josu.hypecar

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppChromeMetricsTest {
    @Test
    fun `automotive chrome leaves more vertical space for car content`() {
        val metrics = AppChromeMetrics.automotive()

        assertThat(metrics.bottomNavHeight).isNotNull()
        assertThat(metrics.bottomNavHeight!!.value).isAtMost(64f)
        assertThat(metrics.miniPlayerArtSize.value).isAtMost(38f)
        assertThat(metrics.miniPlayerRowVerticalPadding.value).isAtMost(5f)
        assertThat(metrics.miniPlayerBottomSpacer.value).isAtMost(4f)
        assertThat(metrics.miniPlayerIconButtonSize.value).isAtMost(40f)
    }

    @Test
    fun `phone chrome keeps playback controls compact`() {
        val metrics = AppChromeMetrics.phone()

        assertThat(metrics.bottomNavHeight).isNotNull()
        assertThat(metrics.bottomNavHeight!!.value).isAtMost(74f)
        assertThat(metrics.bottomBarUsesExternalSystemBarPadding).isTrue()
        assertThat(metrics.miniPlayerArtSize.value).isAtMost(48f)
        assertThat(metrics.miniPlayerRowVerticalPadding.value).isAtMost(7f)
        assertThat(metrics.miniPlayerIconButtonSize.value).isAtMost(42f)
        assertThat(metrics.miniPlayerBottomSpacer.value).isAtMost(6f)
    }
}
