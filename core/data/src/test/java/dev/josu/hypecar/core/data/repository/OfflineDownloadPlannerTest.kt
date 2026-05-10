package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OfflineDownloadPlannerTest {
    @Test
    fun `shouldDownload stops when quota is already full`() {
        val planner = OfflineDownloadPlanner(quotaBytes = 500, usedBytes = 500)

        assertThat(planner.shouldDownloadNext()).isFalse()
    }

    @Test
    fun `recordDownloaded accepts files that fit quota`() {
        val planner = OfflineDownloadPlanner(quotaBytes = 500, usedBytes = 200)

        assertThat(planner.recordDownloaded(byteSize = 250)).isTrue()
        assertThat(planner.usedBytes).isEqualTo(450)
        assertThat(planner.shouldDownloadNext()).isTrue()
    }

    @Test
    fun `recordDownloaded rejects files that would exceed quota`() {
        val planner = OfflineDownloadPlanner(quotaBytes = 500, usedBytes = 300)

        assertThat(planner.recordDownloaded(byteSize = 250)).isFalse()
        assertThat(planner.usedBytes).isEqualTo(300)
    }

    @Test
    fun `accumulator records accepted downloads immediately`() {
        val accumulator = OfflineSyncAccumulator(
            quotaBytes = 500,
            existingRecords = emptyList(),
        )
        val record = OfflineTrackRecord(
            trackId = "39v49",
            fileName = "39v49.audio",
            byteSize = 250,
            downloadedAtEpochSeconds = 1,
        )

        val records = accumulator.recordDownloaded(record)

        assertThat(records).containsExactly(record)
        assertThat(accumulator.records).containsExactly(record)
        assertThat(accumulator.usedBytes).isEqualTo(250)
    }

    @Test
    fun `accumulator dedupes existing records and reports them as cached`() {
        val existing = OfflineTrackRecord(
            trackId = "39v49",
            fileName = "39v49.audio",
            byteSize = 100,
            downloadedAtEpochSeconds = 1,
        )
        val accumulator = OfflineSyncAccumulator(
            quotaBytes = 500,
            existingRecords = listOf(existing),
        )

        assertThat(accumulator.contains("39v49")).isTrue()
        assertThat(accumulator.contains("brand-new")).isFalse()
        assertThat(accumulator.usedBytes).isEqualTo(100)
    }

    @Test
    fun `recordDownloaded rejects when the new record would not fit`() {
        val accumulator = OfflineSyncAccumulator(
            quotaBytes = 200,
            existingRecords = listOf(
                OfflineTrackRecord(
                    trackId = "old",
                    fileName = "old.audio",
                    byteSize = 150,
                    downloadedAtEpochSeconds = 1,
                ),
            ),
        )
        val tooBig = OfflineTrackRecord(
            trackId = "new",
            fileName = "new.audio",
            byteSize = 100,
            downloadedAtEpochSeconds = 2,
        )

        assertThat(accumulator.recordDownloaded(tooBig)).isNull()
        assertThat(accumulator.contains("new")).isFalse()
    }
}
