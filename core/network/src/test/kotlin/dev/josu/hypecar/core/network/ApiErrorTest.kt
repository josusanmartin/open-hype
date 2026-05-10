package dev.josu.hypecar.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class ApiErrorTest {
    @Test
    fun `401 maps to InvalidCredentials when login attempt`() {
        val error = httpException(401).toApiError(loginAttempt = true)
        assertThat(error).isInstanceOf(ApiError.InvalidCredentials::class.java)
    }

    @Test
    fun `401 maps to SessionExpired when not a login attempt`() {
        val error = httpException(401).toApiError(loginAttempt = false)
        assertThat(error).isInstanceOf(ApiError.SessionExpired::class.java)
    }

    @Test
    fun `4xx other than 401 maps to ClientError with status code`() {
        val error = httpException(404).toApiError() as ApiError.ClientError
        assertThat(error.httpStatus).isEqualTo(404)
    }

    @Test
    fun `5xx maps to ServerError with status code`() {
        val error = httpException(503).toApiError() as ApiError.ServerError
        assertThat(error.httpStatus).isEqualTo(503)
    }

    @Test
    fun `IOException maps to Network error`() {
        val error = IOException("offline").toApiError()
        assertThat(error).isInstanceOf(ApiError.Network::class.java)
    }

    @Test
    fun `SerializationException maps to UnexpectedResponse`() {
        val error = SerializationException("malformed").toApiError()
        assertThat(error).isInstanceOf(ApiError.UnexpectedResponse::class.java)
    }

    @Test
    fun `passthrough preserves existing ApiError`() {
        val original = ApiError.SessionExpired
        assertThat(original.toApiError()).isSameInstanceAs(original)
    }

    @Test
    fun `unmapped exception falls through to Unknown`() {
        val error = IllegalStateException("nope").toApiError()
        assertThat(error).isInstanceOf(ApiError.Unknown::class.java)
    }

    private fun httpException(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "".toResponseBody("text/plain".toMediaType())))
}
