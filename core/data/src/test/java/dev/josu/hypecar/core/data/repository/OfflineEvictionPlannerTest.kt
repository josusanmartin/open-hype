package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OfflineEvictionPlannerTest {
    @Test
    fun `eagerly evicts stale records even when quota is respected`() {
        val records = listOf(
            record(id = "fresh-old", bytes = 100, age = 1),
            record(id = "stale-recent", bytes = 100, age = 5),
            record(id = "fresh-recent", bytes = 100, age = 10),
        )

        val plan = OfflineEvictionPlanner.plan(
            records = records,
            quotaBytes = 1_000,
            staleTrackIds = setOf("stale-recent"),
        )

        assertThat(plan.evicted.map { it.trackId }).containsExactly("stale-recent")
        assertThat(plan.kept.map { it.trackId }).containsExactly("fresh-old", "fresh-recent").inOrder()
    }

    @Test
    fun `evicts stale records first and keeps fresh ones within quota`() {
        val records = listOf(
            record(id = "stale-old", bytes = 200, age = 1),
            record(id = "stale-recent", bytes = 200, age = 9),
            record(id = "fresh", bytes = 200, age = 5),
        )

        val plan = OfflineEvictionPlanner.plan(
            records = records,
            quotaBytes = 200,
            staleTrackIds = setOf("stale-old", "stale-recent"),
        )

        assertThat(plan.evicted.map { it.trackId }).containsExactly("stale-old", "stale-recent")
        assertThat(plan.kept.map { it.trackId }).containsExactly("fresh")
    }

    @Test
    fun `falls back to oldest-first when no stale ids are known`() {
        val records = listOf(
            record(id = "a", bytes = 100, age = 1),
            record(id = "b", bytes = 100, age = 2),
            record(id = "c", bytes = 100, age = 3),
        )

        val plan = OfflineEvictionPlanner.plan(records = records, quotaBytes = 200)

        assertThat(plan.evicted.map { it.trackId }).containsExactly("a")
        assertThat(plan.kept.map { it.trackId }).containsExactly("b", "c").inOrder()
    }

    @Test
    fun `returns empty plan when records list is empty`() {
        val plan = OfflineEvictionPlanner.plan(records = emptyList(), quotaBytes = 500)
        assertThat(plan.kept).isEmpty()
        assertThat(plan.evicted).isEmpty()
    }

    @Test
    fun `marking every record stale evicts everything regardless of quota`() {
        val records = listOf(
            record(id = "a", bytes = 100, age = 1),
            record(id = "b", bytes = 100, age = 2),
        )

        val plan = OfflineEvictionPlanner.plan(
            records = records,
            quotaBytes = 10_000,
            staleTrackIds = setOf("a", "b"),
        )

        assertThat(plan.kept).isEmpty()
        assertThat(plan.evicted).hasSize(2)
    }

    private fun record(id: String, bytes: Long, age: Long) = OfflineTrackRecord(
        trackId = id,
        fileName = "$id.audio",
        byteSize = bytes,
        downloadedAtEpochSeconds = age,
    )
}
