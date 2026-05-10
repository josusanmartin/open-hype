package dev.josu.hypecar.core.data.repository

import dev.josu.hypecar.core.model.AuthSession
import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal session-storage contract used by the auth repository. Decouples
 * persistence from the platform-specific DataStore wrapper so the repository
 * can be unit-tested without an Android Context.
 */
interface SessionGateway {
    val session: StateFlow<AuthSession?>

    suspend fun save(session: AuthSession)

    suspend fun clear()

    /**
     * Schedules a non-suspend, fire-and-forget clear. Safe to call from any
     * thread (including OkHttp interceptors). No-op when no session is loaded.
     */
    fun invalidate() = Unit
}
