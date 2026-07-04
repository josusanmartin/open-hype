package dev.josu.hypecar.core.data

import dev.josu.hypecar.core.model.UiErrorKind
import dev.josu.hypecar.core.network.ApiError
import dev.josu.hypecar.core.network.toApiError

/**
 * Maps any failure surfaced by the repositories onto the UI-displayable
 * [UiErrorKind]. Lives in core/data because it needs [ApiError] from
 * core/network, which feature modules deliberately don't depend on.
 */
fun Throwable.toUiErrorKind(loginAttempt: Boolean = false): UiErrorKind =
    when (toApiError(loginAttempt = loginAttempt)) {
        is ApiError.InvalidCredentials -> UiErrorKind.InvalidCredentials
        is ApiError.SessionExpired -> UiErrorKind.SessionExpired
        is ApiError.Network -> UiErrorKind.Network
        is ApiError.ServerError -> UiErrorKind.Server
        is ApiError.ClientError -> UiErrorKind.Server
        is ApiError.UnexpectedResponse -> UiErrorKind.Server
        is ApiError.Unknown -> UiErrorKind.Unknown
    }
