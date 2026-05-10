package dev.josu.hypecar.core.network

interface AuthTokenProvider {
    fun currentToken(): String?
}
