package dev.josu.hypecar.core.data.repository

import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.model.repository.AuthRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import dev.josu.hypecar.core.network.HypeApiService
import dev.josu.hypecar.core.network.dto.toModel
import kotlinx.coroutines.flow.Flow

/**
 * Clears every piece of local state derived from the signed-in account:
 * Room caches (favorites/feed/history lists), downloaded offline audio, and
 * the HTTP cache. Kept as a seam so [DefaultAuthRepository] stays pure-JVM
 * testable while the real implementation (wired in DataModule) touches Room
 * and OkHttp.
 */
fun interface AccountLocalDataWiper {
    suspend fun wipe()
}

class DefaultAuthRepository(
    private val api: HypeApiService,
    private val sessionStore: SessionGateway,
    private val accountDataWiper: AccountLocalDataWiper,
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
        // A wipe failure must not block sign-out — the session is already
        // gone, and the wipe re-runs on the next logout. (No logging here so
        // the class stays pure-JVM testable.)
        runSuspendCatchingPreservingCancellation { accountDataWiper.wipe() }
    }
}
