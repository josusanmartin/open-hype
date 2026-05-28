package dev.josu.hypecar.core.ui

import androidx.compose.ui.graphics.Color

/**
 * Raw color values for the Hype Car brand palette. Most callers should not
 * reach for these directly — they should consume [MaterialTheme.colorScheme]
 * or [HypeTokens] instead — but they're public so designers can iterate on
 * the palette in one place.
 *
 * Naming convention: `<role><variant>(Light|Dark)` where `<role>` describes the
 * surface (e.g. `brand`, `card`, `chrome`) and `<variant>` is an optional
 * weight (e.g. `Soft`, `Strong`, `Muted`).
 */
object HypeColors {
    // Brand orange — the editorial accent.
    val BrandOrange = Color(0xFFFF8A3D)
    val BrandOrangeStrong = Color(0xFFFF6A21)
    val BrandOrangeDeep = Color(0xFFC56A3B)
    val BrandOrangeSoft = Color(0xFFFFB07B)
    val BrandOrangeWash = Color(0xFFFFC4A2)

    // Cream / warm neutrals — the daytime canvas.
    val CreamSurface = Color(0xFFF7F1E8)
    val CreamSurfaceAlt = Color(0xFFF8F0E8)
    val CreamSurfaceVariant = Color(0xFFE8DED0)
    val CreamOnSurface = Color(0xFF1E1A20)
    val CreamMuted = Color(0xFF6B5B53)
    val CreamMutedDeep = Color(0xFF8C7B71)

    // Near-black dark surfaces — the nighttime canvas and dense card chrome.
    val DarkCanvas = Color(0xFF0E0E0F)
    val DarkSurface = Color(0xFF111112)
    val DarkSurfaceAlt = Color(0xFF151211)
    val DarkSurfaceCard = Color(0xFF1A1513)
    val DarkSurfaceElevated = Color(0xFF1F1B19)
    val DarkOnSurface = Color(0xFFF1ECE5)
    val DarkOnSurfaceMuted = Color(0xFFD2D2D2)
    val DarkOnSurfaceDimmed = Color(0xFFA39B92)
    val DarkBorder = Color(0xFF2F2A26)

    // Bottom-nav / chrome accents.
    val ChromeUnselected = Color(0xFF8C8986)
    val ChromeProgressTrack = Color(0xFF434346)

    // Status / system.
    val Error = Color(0xFFD64545)
    val Success = Color(0xFF3D9C73)

    // Tinted near-neutrals used commonly in the codebase.
    val White = Color(0xFFFFFBF6)
    val Black = Color(0xFF050405)
}
