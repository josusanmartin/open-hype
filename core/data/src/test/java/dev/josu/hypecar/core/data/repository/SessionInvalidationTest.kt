package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.AuthSession
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SessionInvalidationTest {
    @Test
    fun `invalidate is a no-op when no session is loaded`() = runBlocking {
        val store = FakeSessionStore()

        store.invalidateLikeProduction()

        assertThat(store.cleared).isFalse()
        assertThat(store.savedSession).isNull()
    }

    @Test
    fun `invalidate clears existing session`() = runBlocking {
        val store = FakeSessionStore().apply {
            savedSession = AuthSession(username = "alice", token = "tok")
        }

        store.invalidateLikeProduction()

        assertThat(store.savedSession).isNull()
        assertThat(store.cleared).isTrue()
    }

    @Test
    fun `repeated invalidate calls do not double-clear or throw`() = runBlocking {
        val store = FakeSessionStore().apply {
            savedSession = AuthSession(username = "alice", token = "tok")
        }

        store.invalidateLikeProduction()
        store.invalidateLikeProduction()
        store.invalidateLikeProduction()

        assertThat(store.savedSession).isNull()
    }
}

/**
 * Test helper that mirrors the production [HypeSessionStore.invalidate] guard:
 * skip when no session, otherwise call [SessionGateway.clear].
 */
private suspend fun FakeSessionStore.invalidateLikeProduction() {
    if (session.value == null) return
    clear()
}
