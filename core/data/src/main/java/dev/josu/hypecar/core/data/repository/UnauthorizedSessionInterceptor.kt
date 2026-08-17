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
        val request = chain.request()
        val response = chain.proceed(request)
        // A 401 from get_token is a wrong password on a *login attempt* — it
        // must not invalidate the currently stored (still valid) session or
        // flash a bogus "session expired" snackbar over the login screen.
        val isLoginAttempt = request.url.encodedPath.trimEnd('/').endsWith("/get_token")
        val rejectedToken = request.url.queryParameter("hm_token")
        if (
            response.code == 401 &&
            request.url.host == apiHost &&
            !isLoginAttempt &&
            !rejectedToken.isNullOrBlank()
        ) {
            sessionGateway.invalidate(rejectedToken)
        }
        return response
    }
}
