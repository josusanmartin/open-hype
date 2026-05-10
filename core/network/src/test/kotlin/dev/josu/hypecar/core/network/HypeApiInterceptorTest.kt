package dev.josu.hypecar.core.network

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class HypeApiInterceptorTest {
    @Test
    fun `injects hm_token query parameter when calling api host`() {
        val captured = captureRequest(
            url = "https://api.hypem.com/v2/tracks?count=10",
            tokenProvider = StaticTokenProvider("abc123"),
        )

        assertThat(captured.url.queryParameter("hm_token")).isEqualTo("abc123")
        assertThat(captured.url.queryParameter("count")).isEqualTo("10")
    }

    @Test
    fun `does not overwrite existing hm_token`() {
        val captured = captureRequest(
            url = "https://api.hypem.com/v2/tracks?hm_token=preset",
            tokenProvider = StaticTokenProvider("abc123"),
        )

        assertThat(captured.url.queryParameter("hm_token")).isEqualTo("preset")
    }

    @Test
    fun `does nothing when token is blank`() {
        val captured = captureRequest(
            url = "https://api.hypem.com/v2/tracks",
            tokenProvider = StaticTokenProvider("  "),
        )

        assertThat(captured.url.queryParameter("hm_token")).isNull()
    }

    @Test
    fun `leaves stream host alone even with token present`() {
        val captured = captureRequest(
            url = "https://hypem.com/serve/public/39v49",
            tokenProvider = StaticTokenProvider("abc123"),
        )

        assertThat(captured.url.queryParameter("hm_token")).isNull()
    }

    @Test
    fun `does not inject into localhost when devProxyAllowed is false`() {
        val captured = captureRequest(
            url = "http://localhost:8787/v2/tracks",
            tokenProvider = StaticTokenProvider("abc123"),
            devProxyAllowed = false,
        )

        assertThat(captured.url.queryParameter("hm_token")).isNull()
    }

    @Test
    fun `injects into localhost dev proxy when devProxyAllowed is true`() {
        val captured = captureRequest(
            url = "http://10.0.2.2:8787/v2/tracks",
            tokenProvider = StaticTokenProvider("abc123"),
            devProxyAllowed = true,
        )

        assertThat(captured.url.queryParameter("hm_token")).isEqualTo("abc123")
    }

    @Test
    fun `leaves arbitrary loopback paths alone even when devProxyAllowed is true`() {
        val captured = captureRequest(
            url = "http://127.0.0.1:8787/admin/secrets",
            tokenProvider = StaticTokenProvider("abc123"),
            devProxyAllowed = true,
        )

        assertThat(captured.url.queryParameter("hm_token")).isNull()
    }

    private fun captureRequest(
        url: String,
        tokenProvider: AuthTokenProvider,
        devProxyAllowed: Boolean = false,
    ): Request {
        val capturing = CapturingInterceptor()
        val client = OkHttpClient.Builder()
            .addInterceptor(HypeApiInterceptor(tokenProvider, devProxyAllowed))
            .addInterceptor(capturing)
            .build()
        client.newCall(Request.Builder().url(url.toHttpUrl()).build()).execute().close()
        return capturing.captured ?: error("interceptor never reached")
    }
}

private class StaticTokenProvider(private val token: String?) : AuthTokenProvider {
    override fun currentToken(): String? = token
}

private class CapturingInterceptor : Interceptor {
    var captured: Request? = null
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        captured = request
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("".toResponseBody(null))
            .build()
    }
}
