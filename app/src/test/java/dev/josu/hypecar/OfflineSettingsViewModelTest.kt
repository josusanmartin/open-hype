package dev.josu.hypecar

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.repository.OfflineDownloadStatus
import dev.josu.hypecar.core.model.repository.OfflineRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setEnabled forwards to repository`() = runTest {
        val repo = RecordingOfflineRepository()
        val vm = OfflineSettingsViewModel(repo)

        vm.setEnabled(true)
        advanceUntilIdle()

        assertThat(repo.enabledCalls).containsExactly(true)
    }

    @Test
    fun `setQuota sanitizes nothing - just delegates`() = runTest {
        val repo = RecordingOfflineRepository()
        val vm = OfflineSettingsViewModel(repo)

        vm.setQuota(123L * 1024L * 1024L)
        advanceUntilIdle()

        assertThat(repo.quotaCalls).containsExactly(123L * 1024L * 1024L)
    }

    @Test
    fun `syncNow triggers single sync`() = runTest {
        val repo = RecordingOfflineRepository()
        val vm = OfflineSettingsViewModel(repo)

        vm.syncNow()
        advanceUntilIdle()

        assertThat(repo.syncCount).isEqualTo(1)
    }

    @Test
    fun `clearDownloads triggers clear`() = runTest {
        val repo = RecordingOfflineRepository()
        val vm = OfflineSettingsViewModel(repo)

        vm.clearDownloads()
        advanceUntilIdle()

        assertThat(repo.clearCount).isEqualTo(1)
    }

    @Test
    fun `status flow flows through unchanged`() {
        val source = MutableStateFlow(OfflineDownloadStatus(isEnabled = true, downloadedTrackCount = 5))
        val repo = RecordingOfflineRepository(initialStatus = source)
        val vm = OfflineSettingsViewModel(repo)

        assertThat(vm.status.value.isEnabled).isTrue()
        assertThat(vm.status.value.downloadedTrackCount).isEqualTo(5)
    }
}

private class RecordingOfflineRepository(
    initialStatus: MutableStateFlow<OfflineDownloadStatus> = MutableStateFlow(OfflineDownloadStatus()),
) : OfflineRepository {
    override val status: StateFlow<OfflineDownloadStatus> = initialStatus
    val enabledCalls = mutableListOf<Boolean>()
    val quotaCalls = mutableListOf<Long>()
    var syncCount = 0
        private set
    var clearCount = 0
        private set

    override suspend fun setEnabled(enabled: Boolean) {
        enabledCalls += enabled
    }
    override suspend fun setQuotaBytes(quotaBytes: Long) {
        quotaCalls += quotaBytes
    }
    override suspend fun syncFavorites() {
        syncCount += 1
    }
    override suspend fun clearDownloads() {
        clearCount += 1
    }
    override fun cachedAudioUri(trackId: String): String? = null
}
