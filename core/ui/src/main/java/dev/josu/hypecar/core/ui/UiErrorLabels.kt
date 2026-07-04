package dev.josu.hypecar.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.josu.hypecar.core.model.UiErrorKind

/**
 * Resolves a [UiErrorKind] to its localized user-facing message. Screens with
 * more specific copy (e.g. login's invalid-credentials) can special-case a
 * kind before falling back to this.
 */
@Composable
fun UiErrorKind.errorLabel(): String = stringResource(
    when (this) {
        UiErrorKind.Network -> R.string.error_network
        UiErrorKind.SessionExpired -> R.string.error_session_expired
        UiErrorKind.Server -> R.string.error_server
        UiErrorKind.InvalidCredentials -> R.string.error_generic
        UiErrorKind.Unknown -> R.string.error_generic
    },
)
