package dev.josu.hypecar.core.network

interface AuthTokenProvider {
    /**
     * Blocks until persisted authentication state has been loaded. Implementations
     * backed by in-memory state may keep the default no-op behavior.
     */
    fun awaitTokenInitialization() = Unit

    fun currentToken(): String?
}
