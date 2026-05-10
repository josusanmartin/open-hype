package dev.josu.hypecar.core.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

class HypeApiInterceptor(
    private val authTokenProvider: AuthTokenProvider,
    private val devProxyAllowed: Boolean = false,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val originalUrl = original.url

        if (!originalUrl.shouldReceiveAuthToken()) {
            return chain.proceed(original)
        }

        val updatedUrl = originalUrl.newBuilder().apply {
            val token = authTokenProvider.currentToken()
            if (!token.isNullOrBlank() && originalUrl.queryParameter("hm_token") == null) {
                addQueryParameter("hm_token", token)
            }
        }.build()

        return chain.proceed(
            original.newBuilder()
                .url(updatedUrl)
                .build(),
        )
    }

    private fun HttpUrl.shouldReceiveAuthToken(): Boolean =
        host == "api.hypem.com" || (devProxyAllowed && isDevProxyHost())

    private fun HttpUrl.isDevProxyHost(): Boolean =
        encodedPath.startsWith("/v2/") &&
            (host == "10.0.2.2" || host == "localhost" || host == "127.0.0.1")
}
