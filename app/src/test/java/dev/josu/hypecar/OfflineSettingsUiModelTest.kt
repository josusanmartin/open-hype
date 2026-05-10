package dev.josu.hypecar

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.repository.OfflineDownloadStatus
import org.junit.Test

class OfflineSettingsUiModelTest {
    @Test
    fun `fromStatus carries raw counts and quota`() {
        val model = OfflineSettingsUiModel.fromStatus(
            OfflineDownloadStatus(
                isEnabled = true,
                quotaBytes = 500L * 1024L * 1024L,
                usedBytes = 125L * 1024L * 1024L,
                downloadedTrackCount = 7,
            ),
        )

        assertThat(model.enabled).isTrue()
        assertThat(model.quotaBytes).isEqualTo(500L * 1024L * 1024L)
        assertThat(model.usedBytes).isEqualTo(125L * 1024L * 1024L)
        assertThat(model.downloadedTrackCount).isEqualTo(7)
        assertThat(model.syncStatus).isEqualTo(OfflineSyncStatus.WAITING)
    }

    @Test
    fun `syncStatus reflects active sync`() {
        val model = OfflineSettingsUiModel.fromStatus(OfflineDownloadStatus(isSyncing = true))
        assertThat(model.syncStatus).isEqualTo(OfflineSyncStatus.SYNCING)
        assertThat(model.isSyncing).isTrue()
    }

    @Test
    fun `syncStatus is SYNCED once a sync has completed`() {
        val model = OfflineSettingsUiModel.fromStatus(
            OfflineDownloadStatus(isEnabled = true, lastSyncedAtEpochSeconds = 100L),
        )
        assertThat(model.syncStatus).isEqualTo(OfflineSyncStatus.SYNCED)
    }

    @Test
    fun `syncStatus is OFF when offline mode is disabled`() {
        val model = OfflineSettingsUiModel.fromStatus(OfflineDownloadStatus(isEnabled = false))
        assertThat(model.syncStatus).isEqualTo(OfflineSyncStatus.OFF)
    }
}
