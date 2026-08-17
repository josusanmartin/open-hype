package dev.josu.hypecar.core.data.net

import okhttp3.Interceptor
import okhttp3.Response

/** Prevents token-bearing API responses from ever becoming disk-cache entries. */
internal class HypeResponseCacheInterceptor(
    private val apiHost: String,
    private val anonymousMaxAgeSeconds: Int,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val carriesAuthToken = request.url.queryParameter("hm_token") != null
        return when {
            // This check intentionally precedes the host/method branch. It
            // covers both the production API and the selected dev proxy.
            carriesAuthToken ->
                response.newBuilder()
                    .removeHeader("Pragma")
                    .header("Cache-Control", "no-store")
                    .build()
            request.method != "GET" || request.url.host != apiHost -> response
            response.isSuccessful ->
                response.newBuilder()
                    .removeHeader("Pragma")
                    .header("Cache-Control", "public, max-age=$anonymousMaxAgeSeconds")
                    .build()
            else -> response
        }
    }
}
