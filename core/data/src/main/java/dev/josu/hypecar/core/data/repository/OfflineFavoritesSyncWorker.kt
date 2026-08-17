package dev.josu.hypecar.core.data.repository

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.josu.hypecar.core.model.repository.OfflineRepository

@HiltWorker
class OfflineFavoritesSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val offlineRepository: OfflineRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val attempt = offlineRepository.runSyncAttemptForWorker()
        return when (attempt) {
            OfflineSyncAttemptResult.Success -> Result.success()
            is OfflineSyncAttemptResult.Failure -> {
                if (attempt.retryable) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        }
    }
}
