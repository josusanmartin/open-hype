package dev.josu.hypecar.feature.catalog

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutomotiveCatalogMetricsTest {
    @Test
    fun `automotive discovery metrics are dense enough for car display`() {
        val metrics = CatalogLayoutMetrics.automotive()

        assertThat(metrics.heroBaseHeight.value).isAtMost(168f)
        assertThat(metrics.featuredCoverSize.value).isAtMost(72f)
        assertThat(metrics.standardCoverSize.value).isAtMost(58f)
        assertThat(metrics.cardOuterVerticalPadding.value).isAtMost(4f)
        assertThat(metrics.contentBottomPadding.value).isAtMost(96f)
    }

    @Test
    fun `phone discovery metrics keep a compact editorial layout`() {
        val metrics = CatalogLayoutMetrics.phone()

        assertThat(metrics.heroBaseHeight.value).isAtMost(264f)
        assertThat(metrics.heroTitleSize.value).isAtMost(46f)
        assertThat(metrics.featuredCoverSize.value).isAtMost(108f)
        assertThat(metrics.cardOuterHorizontalPadding.value).isAtMost(12f)
        assertThat(metrics.cardOuterVerticalPadding.value).isAtMost(8f)
        assertThat(metrics.rowHorizontalPadding.value).isAtMost(16f)
        assertThat(metrics.rowVerticalPadding.value).isAtMost(14f)
        assertThat(metrics.contentBottomPadding.value).isAtMost(140f)
    }

    @Test
    fun `compact phone hero metrics use dense spacing to keep chips visible`() {
        val metrics = CatalogLayoutMetrics.phone(heroBaseHeight = 286.dp)

        assertThat(metrics.heroVerticalPadding.value).isAtMost(18f)
        assertThat(metrics.heroTitleTopPadding.value).isAtMost(18f)
        assertThat(metrics.heroChipsTopPadding.value).isAtMost(18f)
        assertThat(metrics.heroChipVerticalPadding.value).isAtMost(10f)
        assertThat(metrics.selectedIndicatorTopPadding.value).isAtMost(6f)
    }
}
