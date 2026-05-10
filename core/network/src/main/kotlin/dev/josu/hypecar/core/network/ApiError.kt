package dev.josu.hypecar.core.network

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

sealed class ApiError(message: String, cause: Throwable? = null) : Throwable(message, cause) {
    object InvalidCredentials : ApiError("Username or password is incorrect.")
    object SessionExpired : ApiError("Your session has expired. Please log in again.")
    class ClientError(val httpStatus: Int, cause: Throwable) : ApiError("Request rejected by the server (HTTP $httpStatus).", cause)
    class ServerError(val httpStatus: Int, cause: Throwable) : ApiError("The server is having trouble right now (HTTP $httpStatus). Try again shortly.", cause)
    class Network(cause: Throwable) : ApiError("Can't reach the server. Check your connection and try again.", cause)
    class UnexpectedResponse(cause: Throwable) : ApiError("Got an unexpected response from the server.", cause)
    class Unknown(cause: Throwable) : ApiError(cause.message ?: "Something went wrong.", cause)
}

fun Throwable.toApiError(loginAttempt: Boolean = false): ApiError = when (this) {
    is ApiError -> this
    is HttpException -> when (code()) {
        401 -> if (loginAttempt) ApiError.InvalidCredentials else ApiError.SessionExpired
        in 400..499 -> ApiError.ClientError(code(), this)
        in 500..599 -> ApiError.ServerError(code(), this)
        else -> ApiError.Unknown(this)
    }
    is SerializationException -> ApiError.UnexpectedResponse(this)
    is IOException -> ApiError.Network(this)
    else -> ApiError.Unknown(this)
}
