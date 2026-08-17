package dev.josu.hypecar.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The root theme for Hype Car. Everything visual hangs off this:
 *
 *  - [MaterialTheme.colorScheme] — semantic Material 3 colors (primary,
 *    background, surface, error, …). Use these when you mean the *semantic*
 *    role rather than a brand-specific shade.
 *  - [hypeTokens] — brand-level tokens that Material 3 doesn't model
 *    (cards, chrome, mini-player metrics, radii, spacing). Use these when
 *    you want "the dark card surface" regardless of light/dark mode.
 *
 * Light and dark variants ship with the same brand orange but flipped
 * neutrals. Dynamic color is intentionally *not* used — Hype's editorial
 * orange/cream palette is part of the brand identity.
 */
@Composable
fun HypeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isAutomotive: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val tokens = if (darkTheme) HypeTokens.dark() else HypeTokens.light()
    val tunedTokens = if (isAutomotive) {
        tokens.copy(miniPlayer = HypeTokens.automotiveMiniPlayer())
    } else {
        tokens
    }
    CompositionLocalProvider(
        LocalHypeTokens provides tunedTokens,
        LocalIsAutomotive provides isAutomotive,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = HypeTypography,
            shapes = hypeShapes,
            content = content,
        )
    }
}

private val ColorSecondaryLight = androidx.compose.ui.graphics.Color(0xFF1F2226)
private val Color3a271c = androidx.compose.ui.graphics.Color(0xFF3A271C)
private val ColorOnPrimaryDark = androidx.compose.ui.graphics.Color(0xFF1A0E07)

private val LightColorScheme = lightColorScheme(
    primary = HypeColors.BrandOrangeDeep,
    onPrimary = HypeColors.White,
    primaryContainer = HypeColors.BrandOrangeWash,
    onPrimaryContainer = Color3a271c,
    secondary = ColorSecondaryLight,
    onSecondary = HypeColors.White,
    background = HypeColors.CreamSurface,
    onBackground = HypeColors.CreamOnSurface,
    surface = HypeColors.CreamSurface,
    onSurface = HypeColors.CreamOnSurface,
    surfaceVariant = HypeColors.CreamSurfaceVariant,
    onSurfaceVariant = HypeColors.CreamMuted,
    error = HypeColors.ErrorLight,
    onError = HypeColors.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = HypeColors.BrandOrange,
    onPrimary = ColorOnPrimaryDark,
    primaryContainer = HypeColors.BrandOrangeDeep,
    onPrimaryContainer = HypeColors.BrandOrangeWash,
    secondary = HypeColors.CreamSurface,
    onSecondary = HypeColors.DarkCanvas,
    background = HypeColors.DarkCanvas,
    onBackground = HypeColors.DarkOnSurface,
    surface = HypeColors.DarkSurface,
    onSurface = HypeColors.DarkOnSurface,
    surfaceVariant = HypeColors.DarkSurfaceCard,
    onSurfaceVariant = HypeColors.DarkOnSurfaceMuted,
    error = HypeColors.ErrorDark,
    onError = ColorOnPrimaryDark,
)

internal val HypeTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 58.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1.2).sp,
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 25.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
)
