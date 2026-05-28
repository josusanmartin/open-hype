package dev.josu.hypecar.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Brand-level design tokens that live outside of Material3's `colorScheme`
 * because they capture semantics the framework doesn't model (e.g. the
 * dark-card surface on a light theme, the bottom-nav chrome color, accent-soft
 * variants for chip borders).
 *
 * Use [LocalHypeTokens] (or the [hypeTokens] convenience composable) from any
 * composable inside [HypeTheme] to read these. The token set is keyed on the
 * theme variant, so dark mode flips the whole table at once.
 */
@Immutable
data class HypeTokens(
    val brand: BrandPalette,
    val cards: CardPalette,
    val chrome: ChromePalette,
    val radii: Radii,
    val spacing: Spacing,
    val miniPlayer: MiniPlayerMetrics,
    val playerProgress: PlayerProgressColors,
) {
    @Immutable
    data class BrandPalette(
        val primary: Color,
        val primaryStrong: Color,
        val primaryDeep: Color,
        val primarySoft: Color,
        val primaryWash: Color,
    )

    @Immutable
    data class CardPalette(
        val surface: Color,
        val surfaceAlt: Color,
        val surfaceCard: Color,
        val surfaceElevated: Color,
        val onSurface: Color,
        val onSurfaceMuted: Color,
        val onSurfaceDimmed: Color,
        val border: Color,
    )

    @Immutable
    data class ChromePalette(
        val canvas: Color,
        val miniPlayerSurface: Color,
        val miniPlayerArtist: Color,
        val miniPlayerProgressTrack: Color,
        val navSelected: Color,
        val navUnselected: Color,
    )

    @Immutable
    data class Radii(
        val xs: Dp,
        val sm: Dp,
        val md: Dp,
        val lg: Dp,
        val xl: Dp,
        val pill: Dp,
    )

    @Immutable
    data class Spacing(
        val xxs: Dp,
        val xs: Dp,
        val sm: Dp,
        val md: Dp,
        val lg: Dp,
        val xl: Dp,
        val xxl: Dp,
    )

    @Immutable
    data class MiniPlayerMetrics(
        val barHeight: Dp,
        val artSize: Dp,
        val iconButtonSize: Dp,
        val iconSize: Dp,
        val rowSpacing: Dp,
        val rowHorizontalPadding: Dp,
        val rowVerticalPadding: Dp,
        val progressHeight: Dp,
        val progressHorizontalPadding: Dp,
        val bottomSpacer: Dp,
    )

    @Immutable
    data class PlayerProgressColors(
        val active: Color,
        val track: Color,
        val thumb: Color,
    )

    companion object {
        /** Tokens for the light/cream daytime theme. */
        fun light() = HypeTokens(
            brand = BrandPalette(
                primary = HypeColors.BrandOrange,
                primaryStrong = HypeColors.BrandOrangeStrong,
                primaryDeep = HypeColors.BrandOrangeDeep,
                primarySoft = HypeColors.BrandOrangeSoft,
                primaryWash = HypeColors.BrandOrangeWash,
            ),
            cards = CardPalette(
                surface = HypeColors.CreamSurface,
                surfaceAlt = HypeColors.CreamSurfaceAlt,
                surfaceCard = HypeColors.DarkSurfaceCard,
                surfaceElevated = HypeColors.DarkSurfaceElevated,
                onSurface = HypeColors.CreamOnSurface,
                onSurfaceMuted = HypeColors.CreamMuted,
                onSurfaceDimmed = HypeColors.CreamMutedDeep,
                border = HypeColors.CreamSurfaceVariant,
            ),
            chrome = ChromePalette(
                canvas = HypeColors.DarkCanvas,
                miniPlayerSurface = HypeColors.DarkSurface,
                miniPlayerArtist = HypeColors.DarkOnSurfaceMuted,
                miniPlayerProgressTrack = HypeColors.ChromeProgressTrack,
                navSelected = HypeColors.BrandOrange,
                navUnselected = HypeColors.ChromeUnselected,
            ),
            radii = StandardRadii,
            spacing = StandardSpacing,
            miniPlayer = MiniPlayerMetrics(
                barHeight = 72.dp,
                artSize = 48.dp,
                iconButtonSize = 48.dp,
                iconSize = 22.dp,
                rowSpacing = 10.dp,
                rowHorizontalPadding = 10.dp,
                rowVerticalPadding = 6.dp,
                progressHeight = 3.dp,
                progressHorizontalPadding = 10.dp,
                bottomSpacer = 6.dp,
            ),
            playerProgress = PlayerProgressColors(
                active = HypeColors.BrandOrange,
                track = HypeColors.ChromeProgressTrack,
                thumb = HypeColors.White,
            ),
        )

        /** Tokens for the dark nighttime theme. */
        fun dark() = light().copy(
            cards = CardPalette(
                surface = HypeColors.DarkSurface,
                surfaceAlt = HypeColors.DarkSurfaceAlt,
                surfaceCard = HypeColors.DarkSurfaceCard,
                surfaceElevated = HypeColors.DarkSurfaceElevated,
                onSurface = HypeColors.DarkOnSurface,
                onSurfaceMuted = HypeColors.DarkOnSurfaceMuted,
                onSurfaceDimmed = HypeColors.DarkOnSurfaceDimmed,
                border = HypeColors.DarkBorder,
            ),
            chrome = ChromePalette(
                canvas = HypeColors.Black,
                miniPlayerSurface = HypeColors.DarkSurface,
                miniPlayerArtist = HypeColors.DarkOnSurfaceMuted,
                miniPlayerProgressTrack = HypeColors.ChromeProgressTrack,
                navSelected = HypeColors.BrandOrange,
                navUnselected = HypeColors.ChromeUnselected,
            ),
        )

        /**
         * Mini-player metrics tuned for Android Auto's compact bottom bar.
         * Icon button size is held at 44dp — under the 48dp Material baseline
         * but the smallest size that still passes M3's minimumInteractiveSize.
         * Auto HUDs render their own transport row; this surface is only
         * tapped when the phone is docked, so the slimmer size keeps the
         * cluster of (prev / play / next / open) compact.
         */
        fun automotiveMiniPlayer(): MiniPlayerMetrics = MiniPlayerMetrics(
            barHeight = 62.dp,
            artSize = 36.dp,
            iconButtonSize = 44.dp,
            iconSize = 22.dp,
            rowSpacing = 8.dp,
            rowHorizontalPadding = 8.dp,
            rowVerticalPadding = 4.dp,
            progressHeight = 2.dp,
            progressHorizontalPadding = 8.dp,
            bottomSpacer = 4.dp,
        )

        private val StandardRadii = Radii(
            xs = 4.dp,
            sm = 8.dp,
            md = 12.dp,
            lg = 16.dp,
            xl = 24.dp,
            pill = 999.dp,
        )

        private val StandardSpacing = Spacing(
            xxs = 2.dp,
            xs = 4.dp,
            sm = 8.dp,
            md = 12.dp,
            lg = 16.dp,
            xl = 24.dp,
            xxl = 32.dp,
        )
    }
}

/** Composition local that exposes the active [HypeTokens] inside [HypeTheme]. */
val LocalHypeTokens = staticCompositionLocalOf<HypeTokens> {
    error("LocalHypeTokens not provided. Wrap your composable in HypeTheme {}.")
}

/** Convenience composable that reads the active token table. */
val hypeTokens: HypeTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalHypeTokens.current

/** Material3 `Shapes` derived from the radii tokens for a consistent corner system. */
val hypeShapes: Shapes
    @Composable
    @ReadOnlyComposable
    get() = with(hypeTokens.radii) {
        Shapes(
            extraSmall = RoundedCornerShape(xs),
            small = RoundedCornerShape(sm),
            medium = RoundedCornerShape(md),
            large = RoundedCornerShape(lg),
            extraLarge = RoundedCornerShape(xl),
        )
    }

/** A composition local for the "is this running on Android Auto?" flag, sourced from [HypeTheme]. */
val LocalIsAutomotive = compositionLocalOf { false }
