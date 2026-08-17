package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.network.dto.GetTokenResponseDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class DefaultAuthRepositoryTest {
    @Test
    fun `login persists session through session store on success`() = runBlocking {
        val store = FakeSessionStore()
        val api = object : FakeHypeApiService() {
            override suspend fun getToken(username: String, password: String, deviceId: String): GetTokenResponseDto =
                GetTokenResponseDto(username = username, token = "tok-$deviceId")
        }
        val repo = DefaultAuthRepository(api, store)

        val result = repo.login("alice", "pw")

        assertThat(result.isSuccess).isTrue()
        assertThat(store.savedSession?.username).isEqualTo("alice")
        assertThat(store.savedSession?.token).startsWith("tok-")
    }

    @Test
    fun `login surfaces failure result on auth error`() = runBlocking {
        val store = FakeSessionStore()
        val api = object : FakeHypeApiService() {
            override suspend fun getToken(username: String, password: String, deviceId: String): GetTokenResponseDto =
                throw HttpException(Response.error<Any>(401, "".toResponseBody("text/plain".toMediaType())))
        }
        val repo = DefaultAuthRepository(api, store)

        val result = repo.login("alice", "wrong")

        assertThat(result.isFailure).isTrue()
        assertThat(store.savedSession).isNull()
    }

    @Test
    fun `logout clears session through store`() = runBlocking {
        val store = FakeSessionStore().apply {
            savedSession = dev.josu.hypecar.core.model.AuthSession("alice", "tok")
        }
        val repo = DefaultAuthRepository(
            api = object : FakeHypeApiService() {},
            sessionStore = store,
        )

        repo.logout()

        assertThat(store.savedSession).isNull()
        assertThat(store.cleared).isTrue()
    }

    @Test
    fun `login rejects blank authentication fields without persisting`() = runBlocking {
        val malformedResponses = listOf(
            GetTokenResponseDto(username = "", token = "tok"),
            GetTokenResponseDto(username = "alice", token = "   "),
        )

        malformedResponses.forEach { malformed ->
            val store = FakeSessionStore()
            val api = object : FakeHypeApiService() {
                override suspend fun getToken(
                    username: String,
                    password: String,
                    deviceId: String,
                ): GetTokenResponseDto = malformed
            }
            val repo = DefaultAuthRepository(api, store)

            val result = repo.login("alice", "pw")

            assertThat(result.isFailure).isTrue()
            assertThat(store.savedSession).isNull()
        }
    }

    @Test
    fun `session flow waits for persisted state initialization`() = runBlocking {
        val expected = AuthSession("alice", "persisted-token")
        val store = DelayedSessionGateway(expected)
        val repo = DefaultAuthRepository(
            api = object : FakeHypeApiService() {},
            sessionStore = store,
        )
        val firstSession = async { repo.session.first() }
        delay(100)
        assertThat(firstSession.isCompleted).isFalse()

        store.releaseInitialization()

        assertThat(firstSession.await()).isEqualTo(expected)
    }
}

private class DelayedSessionGateway(
    private val persistedSession: AuthSession,
) : SessionGateway {
    private val initialization = CompletableDeferred<Unit>()
    private val _session = MutableStateFlow<AuthSession?>(null)
    override val session: StateFlow<AuthSession?> = _session

    override suspend fun awaitSessionInitialized(): AuthSession? {
        initialization.await()
        _session.value = persistedSession
        return persistedSession
    }

    fun releaseInitialization() {
        initialization.complete(Unit)
    }

    override suspend fun save(session: AuthSession) {
        _session.value = session
    }

    override suspend fun clear() {
        _session.value = null
    }
}
