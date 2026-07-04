package dev.josu.hypecar.core.data.repository

import dev.josu.hypecar.core.model.SessionEvent
import dev.josu.hypecar.core.model.SessionEventBus
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Watches OkHttp responses and invalidates the stored session whenever the
 * Hype Machine API replies with HTTP 401. Other hosts and non-401 responses
 * are passed through untouched.
 *
 * On 401, also emits [SessionEvent.Expired] on [SessionEventBus] so the UI
 * can surface a "your session expired" snackbar instead of the user
 * discovering it via an empty Library.
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
        // A 401 from get_token is a wrong password on a *login attempt* — it
        // must not invalidate the currently stored (still valid) session or
        // flash a bogus "session expired" snackbar over the login screen.
        val isLoginAttempt = chain.request().url.encodedPath.endsWith("/get_token")
        if (response.code == 401 && chain.request().url.host == apiHost && !isLoginAttempt) {
            sessionGateway.invalidate()
            SessionEventBus.emit(SessionEvent.Expired)
        }
        return response
    }
}
