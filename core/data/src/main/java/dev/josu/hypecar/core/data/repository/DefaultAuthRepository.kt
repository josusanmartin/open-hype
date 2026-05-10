package dev.josu.hypecar.core.data.repository

import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.model.repository.AuthRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import dev.josu.hypecar.core.network.HypeApiService
import dev.josu.hypecar.core.network.dto.toModel
import kotlinx.coroutines.flow.Flow

class DefaultAuthRepository(
    private val api: HypeApiService,
    private val sessionStore: SessionGateway,
) : AuthRepository {
    override val session: Flow<AuthSession?> = sessionStore.session

    override suspend fun login(usernameOrEmail: String, password: String): Result<AuthSession> =
        runSuspendCatchingPreservingCancellation {
            api.getToken(
                username = usernameOrEmail,
                password = password,
                deviceId = AuthDeviceIdFactory.create(usernameOrEmail),
            ).toModel().also {
                sessionStore.save(it)
            }
        }

    override suspend fun logout() {
        sessionStore.clear()
    }
}
