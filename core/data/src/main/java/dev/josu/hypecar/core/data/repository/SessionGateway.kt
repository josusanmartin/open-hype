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

    /** Waits until persisted state has produced its initial value. */
    suspend fun awaitSessionInitialized(): AuthSession? = session.value

    suspend fun save(session: AuthSession)

    suspend fun clear()

    /**
     * Schedules a non-suspend, fire-and-forget clear only if [expectedToken]
     * still belongs to the active session. Safe to call from any thread
     * (including OkHttp interceptors).
     */
    fun invalidate(expectedToken: String) = Unit
}
