package dev.josu.hypecar.core.data.net

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class HypeResponseCacheInterceptorTest {
    private val server = MockWebServer()

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `token-bearing proxy response is marked no-store`() {
        val client = client(apiHost = server.hostName)
        server.enqueue(MockResponse().setHeader("Cache-Control", "public, max-age=3600").setBody("private"))

        val response = client.newCall(
            Request.Builder().url(server.url("/v2/me/favorites?hm_token=secret")).build(),
        ).execute()

        response.use {
            assertThat(it.header("Cache-Control")).isEqualTo("no-store")
        }
    }

    @Test
    fun `successful anonymous response on selected host gets short public cache window`() {
        val client = client(apiHost = server.hostName)
        server.enqueue(MockResponse().setBody("public"))

        val response = client.newCall(Request.Builder().url(server.url("/v2/tracks")).build()).execute()

        response.use {
            assertThat(it.header("Cache-Control")).isEqualTo("public, max-age=60")
        }
    }

    private fun client(apiHost: String): OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(
            HypeResponseCacheInterceptor(
                apiHost = apiHost,
                anonymousMaxAgeSeconds = 60,
            ),
        )
        .build()
}
