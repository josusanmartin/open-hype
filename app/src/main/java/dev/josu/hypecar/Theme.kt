package dev.josu.hypecar

import androidx.compose.runtime.Composable
import dev.josu.hypecar.core.ui.HypeTheme

/**
 * Thin alias around the real theme implementation in `:core:ui`.
 *
 * The design review consolidated all colors, typography, shapes, and chrome
 * tokens into [HypeTheme]; this top-level alias is kept so the
 * `MainActivity.setContent { AppTheme { … } }` call site (and any external
 * test fixtures that reference `AppTheme`) keeps working without an import
 * churn.
 *
 * New call sites should prefer `HypeTheme` directly so the `isAutomotive`
 * parameter is reachable.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    HypeTheme(content = content)
}
