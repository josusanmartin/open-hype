package dev.josu.hypecar.core.model

/**
 * Coarse classification of a failed operation, exposed by ViewModels instead
 * of exception text. Composables resolve each kind to a localized string —
 * per the project policy that no user-visible copy lives in Kotlin, and so
 * raw transport messages ("Unable to resolve host…") never reach the screen.
 */
enum class UiErrorKind {
    Network,
    InvalidCredentials,
    SessionExpired,
    Server,
    Unknown,
}
