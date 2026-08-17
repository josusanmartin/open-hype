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
import java.io.IOException

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
    fun `worker reports permanent failure when repository status carries an error`() = runTest {
        val repo = ScriptedOfflineRepository(
            statusAfterSync = OfflineDownloadStatus(error = "io fail"),
        )

        val result = buildWorker(repo).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun `worker reports permanent failure when sync throws runtime exception`() = runTest {
        val repo = ScriptedOfflineRepository(throwOnSync = RuntimeException("boom"))

        val result = buildWorker(repo).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun `worker requests retry when sync throws io exception`() = runTest {
        val repo = ScriptedOfflineRepository(throwOnSync = IOException("offline"))

        val result = buildWorker(repo).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `worker uses successful attempt result instead of stale global error`() = runTest {
        val repo = AttemptReportingOfflineRepository(
            attemptResult = OfflineSyncAttemptResult.Success,
            status = OfflineDownloadStatus(error = "stale failure from an older attempt"),
        )

        val result = buildWorker(repo).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(repo.attemptCount).isEqualTo(1)
        assertThat(repo.publicSyncCount).isEqualTo(0)
    }

    @Test
    fun `worker reports permanent failed attempt even before global status is observed`() = runTest {
        val repo = AttemptReportingOfflineRepository(
            attemptResult = OfflineSyncAttemptResult.Failure("attempt failed"),
            status = OfflineDownloadStatus(error = null),
        )

        val result = buildWorker(repo).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        assertThat(repo.attemptCount).isEqualTo(1)
        assertThat(repo.publicSyncCount).isEqualTo(0)
    }

    @Test
    fun `worker reports permanent failure when attempt runner throws runtime exception`() = runTest {
        val repo = AttemptReportingOfflineRepository(
            attemptResult = OfflineSyncAttemptResult.Success,
            status = OfflineDownloadStatus(),
            attemptFailure = RuntimeException(),
        )

        val result = buildWorker(repo).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        assertThat(repo.attemptCount).isEqualTo(1)
    }

    @Test
    fun `worker retries an explicitly retryable failed attempt`() = runTest {
        val repo = AttemptReportingOfflineRepository(
            attemptResult = OfflineSyncAttemptResult.Failure(
                message = "temporarily unavailable",
                retryable = true,
            ),
            status = OfflineDownloadStatus(),
        )

        val result = buildWorker(repo).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        assertThat(repo.attemptCount).isEqualTo(1)
    }

    @Test
    fun `worker retries when attempt runner throws io exception`() = runTest {
        val repo = AttemptReportingOfflineRepository(
            attemptResult = OfflineSyncAttemptResult.Success,
            status = OfflineDownloadStatus(),
            attemptFailure = IOException("temporarily offline"),
        )

        val result = buildWorker(repo).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        assertThat(repo.attemptCount).isEqualTo(1)
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

private class AttemptReportingOfflineRepository(
    private val attemptResult: OfflineSyncAttemptResult,
    status: OfflineDownloadStatus,
    private val attemptFailure: Throwable? = null,
) : OfflineRepository,
    OfflineSyncAttemptRunner {
    private val _status = MutableStateFlow(status)
    override val status: StateFlow<OfflineDownloadStatus> = _status
    var attemptCount: Int = 0
        private set
    var publicSyncCount: Int = 0
        private set

    override suspend fun runSyncAttempt(): OfflineSyncAttemptResult {
        attemptCount += 1
        attemptFailure?.let { throw it }
        return attemptResult
    }

    override suspend fun setEnabled(enabled: Boolean) = Unit
    override suspend fun setQuotaBytes(quotaBytes: Long) = Unit
    override suspend fun syncFavorites() {
        publicSyncCount += 1
    }
    override suspend fun clearDownloads() = Unit
    override fun cachedAudioUri(trackId: String): String? = null
}
