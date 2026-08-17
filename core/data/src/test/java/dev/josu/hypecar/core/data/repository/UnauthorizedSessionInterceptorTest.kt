package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.AuthSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class UnauthorizedSessionInterceptorTest {
    private val server = MockWebServer()

    @Before fun start() {
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

    @Test
    fun `401 from configured api host triggers gateway invalidate`() {
        val gateway = ObservableSessionGateway(initial = AuthSession("alice", "tok"))
        val client = clientWith(gateway, apiHost = server.hostName)
        server.enqueue(MockResponse().setResponseCode(401))

        client.newCall(Request.Builder().url(server.url("/v2/me/favorites?hm_token=tok")).build())
            .execute().close()

        assertThat(gateway.invalidateCount).isEqualTo(1)
        assertThat(gateway.invalidatedTokens).containsExactly("tok")
    }

    @Test
    fun `non-401 responses do not invalidate`() {
        val gateway = ObservableSessionGateway(initial = AuthSession("alice", "tok"))
        val client = clientWith(gateway, apiHost = server.hostName)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.newCall(Request.Builder().url(server.url("/v2/me/favorites?hm_token=tok")).build())
            .execute().close()

        assertThat(gateway.invalidateCount).isEqualTo(0)
    }

    @Test
    fun `401 from a different host is ignored`() {
        val gateway = ObservableSessionGateway(initial = AuthSession("alice", "tok"))
        val client = clientWith(gateway, apiHost = "api.hypem.com") // not server's host
        server.enqueue(MockResponse().setResponseCode(401))

        client.newCall(Request.Builder().url(server.url("/v2/me/favorites?hm_token=tok")).build())
            .execute().close()

        assertThat(gateway.invalidateCount).isEqualTo(0)
    }

    @Test
    fun `repeated 401s forward the rejected request token`() {
        val gateway = ObservableSessionGateway(initial = AuthSession("alice", "tok"))
        val client = clientWith(gateway, apiHost = server.hostName)
        repeat(3) { server.enqueue(MockResponse().setResponseCode(401)) }

        repeat(3) {
            client.newCall(Request.Builder().url(server.url("/v2/me/favorites?hm_token=tok")).build())
                .execute().close()
        }

        assertThat(gateway.invalidateCount).isEqualTo(3)
        assertThat(gateway.invalidatedTokens).containsExactly("tok", "tok", "tok")
    }

    @Test
    fun `anonymous 401 does not invalidate an authenticated session`() {
        val gateway = ObservableSessionGateway(initial = AuthSession("alice", "tok"))
        val client = clientWith(gateway, apiHost = server.hostName)
        server.enqueue(MockResponse().setResponseCode(401))

        client.newCall(Request.Builder().url(server.url("/v2/tracks")).build())
            .execute().close()

        assertThat(gateway.invalidateCount).isEqualTo(0)
    }

    @Test
    fun `login 401 does not invalidate even when a token query is present`() {
        val gateway = ObservableSessionGateway(initial = AuthSession("alice", "tok"))
        val client = clientWith(gateway, apiHost = server.hostName)
        server.enqueue(MockResponse().setResponseCode(401))

        client.newCall(Request.Builder().url(server.url("/v2/get_token?hm_token=tok")).build())
            .execute().close()

        assertThat(gateway.invalidateCount).isEqualTo(0)
    }

    private fun clientWith(gateway: SessionGateway, apiHost: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(UnauthorizedSessionInterceptor(gateway, apiHost = apiHost))
            .build()
}

private class ObservableSessionGateway(initial: AuthSession?) : SessionGateway {
    private val _session = MutableStateFlow(initial)
    override val session: StateFlow<AuthSession?> = _session
    var invalidateCount: Int = 0
        private set
    val invalidatedTokens = mutableListOf<String>()

    override suspend fun save(session: AuthSession) {
        _session.value = session
    }
    override suspend fun clear() {
        _session.value = null
    }
    override fun invalidate(expectedToken: String) {
        invalidateCount += 1
        invalidatedTokens += expectedToken
    }
}
