package dev.josu.hypecar.core.model

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-process singleton the chrome uses to ask whichever route is currently
 * visible to scroll its list back to the top.
 *
 * Each route subscribes by route key and filters its own emissions out:
 * ```
 * LaunchedEffect(Unit) {
 *     ScrollToTopBus.events.collect { route ->
 *         if (route == "latest") listState.animateScrollToItem(0)
 *     }
 * }
 * ```
 *
 * Lives in `core/model` so feature modules can subscribe without depending on
 * the `app` module (which would create a cycle).
 */
object ScrollToTopBus {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun request(route: String) {
        _events.tryEmit(route)
    }
}
