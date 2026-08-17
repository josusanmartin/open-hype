package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FavoriteStateCoordinatorTest {
    @Test
    fun `edit completed during an older read wins over the stale response and cache`() {
        val gate = AccountDataWriteGate()
        val coordinator = FavoriteStateCoordinator(gate)
        val generation = gate.captureGeneration()
        val read = coordinator.captureRead()

        coordinator.record("track", isLoved = true, isPending = true, generation)
        coordinator.record("track", isLoved = true, isPending = false, generation)

        val fetched = coordinator.reconcileNetwork(listOf(track(isLoved = false, lovedCount = 7)), read)
        val cached = coordinator.applyToCached(listOf(track(isLoved = false, lovedCount = 7)))

        assertThat(fetched.single().isLoved).isTrue()
        assertThat(fetched.single().lovedCount).isEqualTo(8)
        assertThat(cached.single().isLoved).isTrue()
        assertThat(cached.single().lovedCount).isEqualTo(8)
    }

    @Test
    fun `network read started after confirmation retires the overlay`() {
        val gate = AccountDataWriteGate()
        val coordinator = FavoriteStateCoordinator(gate)
        val generation = gate.captureGeneration()
        coordinator.record("track", isLoved = true, isPending = false, generation)
        val laterRead = coordinator.captureRead()

        val fetched = coordinator.reconcileNetwork(listOf(track(isLoved = false, lovedCount = 7)), laterRead)
        val cachedAfterward = coordinator.applyToCached(listOf(track(isLoved = false, lovedCount = 7)))

        assertThat(fetched.single().isLoved).isFalse()
        assertThat(cachedAfterward.single().isLoved).isFalse()
        assertThat(coordinator.currentStates()).doesNotContainKey("track")
    }

    @Test
    fun `account boundary change drops previous account favorite truth`() = runTest {
        val gate = AccountDataWriteGate()
        val coordinator = FavoriteStateCoordinator(gate)
        coordinator.record("track", isLoved = true, isPending = false, gate.captureGeneration())

        gate.deactivate()
        gate.activate()

        assertThat(coordinator.applyToCached(listOf(track(isLoved = false, lovedCount = 7))).single().isLoved)
            .isFalse()
        assertThat(coordinator.currentStates()).isEmpty()
    }

    @Test
    fun `delayed old-account record cannot erase the new account overlay`() = runTest {
        val gate = AccountDataWriteGate()
        val coordinator = FavoriteStateCoordinator(gate)
        val accountA = gate.captureGeneration()
        gate.deactivate()
        gate.activate()
        val accountB = gate.captureGeneration()
        coordinator.record("track", isLoved = true, isPending = false, accountB)

        coordinator.record("old-track", isLoved = true, isPending = false, accountA)

        assertThat(coordinator.currentStates()).containsExactly("track", true)
    }

    @Test
    fun `duplicate first-page ids are removed both signed in and signed out`() = runTest {
        val gate = AccountDataWriteGate()
        val coordinator = FavoriteStateCoordinator(gate)
        val duplicates = listOf(track(), track().copy(title = "newer"))

        assertThat(coordinator.applyToCached(duplicates).map(Track::id)).containsExactly("track")

        gate.deactivate()

        assertThat(coordinator.applyToCached(duplicates).map(Track::id)).containsExactly("track")
    }

    private fun track(
        isLoved: Boolean = false,
        lovedCount: Int = 0,
    ) = Track(
        id = "track",
        artist = "Artist",
        title = "Title",
        lovedCount = lovedCount,
        postedBy = "Blog",
        postedById = 1,
        postedCount = 1,
        postDescription = "",
        datePostedEpochSeconds = 0L,
        postUrl = "",
        itunesUrl = "",
        isLoved = isLoved,
    )
}
