package dev.josu.hypecar.core.model

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide event bus for session lifecycle events that need to fan out
 * across layers without a direct dependency.
 *
 * Today this carries one event — [SessionEvent.Expired] — fired after a 401's
 * rejected token is confirmed to still be the active session and its local
 * account data has been cleared. The UI listens (via `AppChromeViewModel`)
 * and surfaces a snackbar so the user isn't silently signed out into an
 * empty Library.
 *
 * Lives in `core/model` for the same reason as [ScrollToTopBus]: feature
 * modules and data modules both need to talk to it without depending on
 * `:app`.
 */
object SessionEventBus {
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    fun emit(event: SessionEvent) {
        _events.tryEmit(event)
    }
}

sealed class SessionEvent {
    /** Server returned 401 and the local session was invalidated. */
    data object Expired : SessionEvent()
}
