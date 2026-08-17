package dev.josu.hypecar.core.data.repository

import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.model.repository.AuthRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import dev.josu.hypecar.core.network.HypeApiService
import dev.josu.hypecar.core.network.dto.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException

/**
 * Clears every piece of local state derived from the signed-in account.
 * Session persistence owns this seam so explicit logout and server-side
 * expiration cannot take different cleanup paths.
 */
fun interface AccountLocalDataWiper {
    suspend fun wipe()
}

class DefaultAuthRepository(
    private val api: HypeApiService,
    private val sessionStore: SessionGateway,
) : AuthRepository {
    override val session: Flow<AuthSession?> = flow {
        sessionStore.awaitSessionInitialized()
        emitAll(sessionStore.session)
    }

    override suspend fun login(usernameOrEmail: String, password: String): Result<AuthSession> =
        runSuspendCatchingPreservingCancellation {
            sessionStore.awaitSessionInitialized()
            val session = api.getToken(
                username = usernameOrEmail,
                password = password,
                deviceId = AuthDeviceIdFactory.create(usernameOrEmail),
            ).toModel()
            if (session.username.isBlank() || session.token.isBlank()) {
                throw SerializationException("Authentication response is missing a username or token")
            }
            sessionStore.save(session)
            session
        }

    override suspend fun logout() {
        sessionStore.clear()
    }
}
