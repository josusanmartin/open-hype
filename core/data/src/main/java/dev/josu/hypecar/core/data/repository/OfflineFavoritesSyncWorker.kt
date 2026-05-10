package dev.josu.hypecar.core.data.repository

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.josu.hypecar.core.model.repository.OfflineRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation

@HiltWorker
class OfflineFavoritesSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val offlineRepository: OfflineRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        runSuspendCatchingPreservingCancellation {
            offlineRepository.syncFavorites()
            if (offlineRepository.status.value.error != null) {
                Result.retry()
            } else {
                Result.success()
            }
        }.fold(
            onSuccess = { it },
            onFailure = { Result.retry() },
        )
}
