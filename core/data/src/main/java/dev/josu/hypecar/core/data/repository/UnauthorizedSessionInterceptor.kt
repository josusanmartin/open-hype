package dev.josu.hypecar.core.data.repository

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Watches OkHttp responses and invalidates the stored session whenever the
 * Hype Machine API replies with HTTP 401. Other hosts and non-401 responses
 * are passed through untouched.
 *
 * Extracted from [dev.josu.hypecar.core.data.di.DataModule] so the behavior
 * can be exercised end-to-end with [okhttp3.mockwebserver.MockWebServer].
 */
class UnauthorizedSessionInterceptor(
    private val sessionGateway: SessionGateway,
    private val apiHost: String = "api.hypem.com",
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 401 && chain.request().url.host == apiHost) {
            sessionGateway.invalidate()
        }
        return response
    }
}
