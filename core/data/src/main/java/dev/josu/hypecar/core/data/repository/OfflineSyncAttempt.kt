package dev.josu.hypecar.core.data.repository

import dev.josu.hypecar.core.model.repository.OfflineRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import retrofit2.HttpException
import java.io.IOException

/** Result of the exact offline sync attempt awaited by a caller. */
internal sealed interface OfflineSyncAttemptResult {
    data object Success : OfflineSyncAttemptResult

    data class Failure(
        val message: String,
        val retryable: Boolean = false,
    ) : OfflineSyncAttemptResult
}

/** Internal capability used by WorkManager without expanding the public model contract. */
internal interface OfflineSyncAttemptRunner {
    suspend fun runSyncAttempt(): OfflineSyncAttemptResult
}

/**
 * Prefer an attempt-scoped result when the implementation can provide one.
 * The fallback preserves compatibility with test doubles and other
 * [OfflineRepository] implementations that only expose the public API.
 */
internal suspend fun OfflineRepository.runSyncAttemptForWorker(): OfflineSyncAttemptResult = runSuspendCatchingPreservingCancellation {
    if (this is OfflineSyncAttemptRunner) {
        runSyncAttempt()
    } else {
        syncFavorites()
        status.value.error
            ?.let { message ->
                OfflineSyncAttemptResult.Failure(message.ifBlank { "Offline sync failed" })
            }
            ?: OfflineSyncAttemptResult.Success
    }
}.getOrElse { error ->
    OfflineSyncAttemptResult.Failure(
        message = error.offlineSyncFailureMessage(),
        retryable = error.isRetryableOfflineSyncFailure(),
    )
}

internal fun Throwable.offlineSyncFailureMessage(): String =
    message?.takeIf(String::isNotBlank) ?: "Offline sync failed"

internal fun Throwable.isRetryableOfflineSyncFailure(): Boolean =
    when (this) {
        is HttpException -> code() == 408 || code() == 429 || code() >= 500
        is IOException -> true
        else -> cause?.takeIf { it !== this }?.isRetryableOfflineSyncFailure() == true
    }
