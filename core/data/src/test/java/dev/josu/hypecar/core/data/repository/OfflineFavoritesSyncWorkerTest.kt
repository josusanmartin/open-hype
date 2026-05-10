package dev.josu.hypecar.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.repository.OfflineDownloadStatus
import dev.josu.hypecar.core.model.repository.OfflineRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OfflineFavoritesSyncWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `worker reports success when sync completes without error`() = runTest {
        val repo = ScriptedOfflineRepository()

        val result = buildWorker(repo).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(repo.syncCount).isEqualTo(1)
    }

    @Test
    fun `worker requests retry when repository status carries an error`() = runTest {
        val repo = ScriptedOfflineRepository(
            statusAfterSync = OfflineDownloadStatus(error = "io fail"),
        )

        val result = buildWorker(repo).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `worker requests retry when sync throws`() = runTest {
        val repo = ScriptedOfflineRepository(throwOnSync = RuntimeException("boom"))

        val result = buildWorker(repo).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    private fun buildWorker(repo: OfflineRepository): OfflineFavoritesSyncWorker =
        TestListenableWorkerBuilder<OfflineFavoritesSyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = OfflineFavoritesSyncWorker(appContext, workerParameters, repo)
            })
            .build()
}

private class ScriptedOfflineRepository(
    private val statusAfterSync: OfflineDownloadStatus = OfflineDownloadStatus(),
    private val throwOnSync: Throwable? = null,
) : OfflineRepository {
    private val _status = MutableStateFlow(OfflineDownloadStatus())
    override val status: StateFlow<OfflineDownloadStatus> = _status
    var syncCount = 0
        private set

    override suspend fun setEnabled(enabled: Boolean) = Unit
    override suspend fun setQuotaBytes(quotaBytes: Long) = Unit
    override suspend fun syncFavorites() {
        syncCount += 1
        throwOnSync?.let { throw it }
        _status.value = statusAfterSync
    }
    override suspend fun clearDownloads() = Unit
    override fun cachedAudioUri(trackId: String): String? = null
}
