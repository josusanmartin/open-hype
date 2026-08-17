package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.MeRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteSyncManagerTest {
    @Test
    fun `toggle emits a revert edit when favorite request fails`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val manager = FavoriteSyncManager(FailingMeRepository, SyncTestNoOpPlayback, CoroutineScope(testDispatcher))
        val edits = async(testDispatcher) {
            withTimeout(2_000) {
                manager.edits.take(2).toList()
            }
        }

        manager.toggle(sampleTrack(isLoved = false, lovedCount = 7))
        advanceUntilIdle()

        assertThat(edits.await()).containsExactly(
            FavoriteEdit(trackId = "39v49", isLoved = true, lovedCountDelta = 1),
            FavoriteEdit(trackId = "39v49", isLoved = false, lovedCountDelta = -1),
        ).inOrder()
    }

    @Test
    fun `toggle folds optimistic and revert states into the playback queue`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val playback = RecordingSyncPlayback()
        val manager = FavoriteSyncManager(FailingMeRepository, playback, CoroutineScope(testDispatcher))

        manager.toggle(sampleTrack(isLoved = false, lovedCount = 7))
        advanceUntilIdle()

        assertThat(playback.favoriteUpdates).containsExactly(
            "39v49" to true,
            "39v49" to false,
        ).inOrder()
    }

    @Test
    fun `publish forwards externally-managed edits to the playback queue`() = runTest {
        val playback = RecordingSyncPlayback()
        val manager = FavoriteSyncManager(
            FailingMeRepository,
            playback,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        manager.publish(FavoriteEdit(trackId = "39v49", isLoved = true, lovedCountDelta = 1))

        assertThat(playback.favoriteUpdates).containsExactly("39v49" to true)
    }

    @Test
    fun `late revert from previous account cannot update the new account queue`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val playback = RecordingSyncPlayback()
        val repository = DeferredFavoriteRepository()
        val gate = AccountDataWriteGate()
        val manager = FavoriteSyncManager(
            repository,
            playback,
            CoroutineScope(testDispatcher),
            gate,
        )

        manager.toggle(sampleTrack(isLoved = false, lovedCount = 7))
        testScheduler.runCurrent()
        repository.started.await()
        gate.deactivate()
        gate.activate()
        repository.result.complete(null)
        advanceUntilIdle()

        assertThat(playback.favoriteUpdates).containsExactly("39v49" to true)
    }

    @Test
    fun `rapid taps derive the second intent from the pending optimistic state`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val playback = RecordingSyncPlayback()
        val repository = SequencedFavoriteRepository(listOf(true, false))
        val manager = FavoriteSyncManager(
            repository,
            playback,
            CoroutineScope(testDispatcher),
        )
        val track = sampleTrack(isLoved = false, lovedCount = 7)

        val first = manager.toggle(track)
        val second = manager.toggle(track)
        advanceUntilIdle()

        assertThat(first).isTrue()
        assertThat(second).isFalse()
        assertThat(playback.favoriteUpdates).containsExactly(
            "39v49" to true,
            "39v49" to false,
        ).inOrder()
        assertThat(manager.activeTrackProcessorCount).isEqualTo(0)
    }

    @Test
    fun `scoped publish is rejected after account generation changes`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val playback = RecordingSyncPlayback()
        val gate = AccountDataWriteGate()
        val manager = FavoriteSyncManager(
            FailingMeRepository,
            playback,
            CoroutineScope(testDispatcher),
            gate,
        )
        val operation = manager.captureAccountOperation()

        gate.deactivate()
        gate.activate()
        val published = manager.publish(
            FavoriteEdit("39v49", isLoved = true, lovedCountDelta = 1),
            operation,
        )
        advanceUntilIdle()

        assertThat(published).isFalse()
        assertThat(playback.favoriteUpdates).isEmpty()
    }

    @Test
    fun `server toggle uses token captured at intent start`() = runTest {
        val repository = RecordingScopedFavoriteRepository()
        val gate = AccountDataWriteGate()
        gate.activate("token-a")
        val manager = FavoriteSyncManager(
            repository,
            SyncTestNoOpPlayback,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            gate,
        )
        val operation = manager.captureAccountOperation()

        val result = manager.toggleFavoriteOnServer("39v49", operation)

        assertThat(result.isAccountCurrent).isTrue()
        assertThat(repository.authTokens).containsExactly("token-a")
    }

    @Test
    fun `account switch before server request rejects captured intent`() = runTest {
        val repository = RecordingScopedFavoriteRepository()
        val gate = AccountDataWriteGate()
        gate.activate("token-a")
        val manager = FavoriteSyncManager(
            repository,
            SyncTestNoOpPlayback,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            gate,
        )
        val operation = manager.captureAccountOperation()

        gate.deactivate()
        gate.activate("token-b")
        val result = manager.toggleFavoriteOnServer("39v49", operation)

        assertThat(result.isAccountCurrent).isFalse()
        assertThat(repository.authTokens).isEmpty()
    }

    @Test
    fun `switch between manager check and repository entry keeps token and writes scoped to old account`() = runTest {
        val gate = AccountDataWriteGate()
        gate.activate("token-a")
        val repository = SwitchingScopedFavoriteRepository(gate)
        val manager = FavoriteSyncManager(
            repository,
            SyncTestNoOpPlayback,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            gate,
        )
        val operation = manager.captureAccountOperation()

        val result = manager.toggleFavoriteOnServer("39v49", operation)

        assertThat(result.isAccountCurrent).isFalse()
        assertThat(repository.authTokens).containsExactly("token-a")
        assertThat(repository.localWriteCount).isEqualTo(0)
    }

    @Test
    fun `contradictory toggle response is not repeated when confirmation matches intent`() = runTest {
        val repository = ConfirmingScopedFavoriteRepository(
            toggleResults = listOf(false),
            confirmation = true,
        )
        val gate = AccountDataWriteGate()
        gate.activate("token")
        val manager = FavoriteSyncManager(
            repository,
            SyncTestNoOpPlayback,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            gate,
            FavoriteStateCoordinator(gate),
        )

        val outcome = manager.toggleWithResult(sampleTrack(isLoved = false, lovedCount = 7)).awaitOutcome()
        advanceUntilIdle()

        assertThat(outcome).isEqualTo(FavoriteToggleOutcome.CONFIRMED)
        assertThat(repository.toggleCalls).isEqualTo(1)
        assertThat(repository.confirmationCalls).isEqualTo(1)
    }

    @Test
    fun `contradictory toggle is repeated once only after confirmation remains opposite`() = runTest {
        val repository = ConfirmingScopedFavoriteRepository(
            toggleResults = listOf(false, true),
            confirmation = false,
        )
        val gate = AccountDataWriteGate()
        gate.activate("token")
        val manager = FavoriteSyncManager(
            repository,
            SyncTestNoOpPlayback,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            gate,
            FavoriteStateCoordinator(gate),
        )

        val outcome = manager.toggleWithResult(sampleTrack(isLoved = false, lovedCount = 7)).awaitOutcome()
        advanceUntilIdle()

        assertThat(outcome).isEqualTo(FavoriteToggleOutcome.CONFIRMED)
        assertThat(repository.toggleCalls).isEqualTo(2)
        assertThat(repository.confirmationCalls).isEqualTo(1)
    }

    @Test
    fun `failed confirmation never blindly repeats a non-idempotent toggle`() = runTest {
        val repository = ConfirmingScopedFavoriteRepository(
            toggleResults = listOf(false),
            confirmation = null,
        )
        val gate = AccountDataWriteGate()
        gate.activate("token")
        val manager = FavoriteSyncManager(
            repository,
            SyncTestNoOpPlayback,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            gate,
            FavoriteStateCoordinator(gate),
        )

        val outcome = manager.toggleWithResult(sampleTrack(isLoved = false, lovedCount = 7)).awaitOutcome()
        advanceUntilIdle()

        assertThat(outcome).isEqualTo(FavoriteToggleOutcome.RECONCILED)
        assertThat(repository.toggleCalls).isEqualTo(1)
        assertThat(repository.confirmationCalls).isEqualTo(1)
    }

    @Test
    fun `lost toggle response is accepted when idempotent confirmation matches intent`() = runTest {
        val repository = ConfirmingScopedFavoriteRepository(
            toggleResults = listOf(null),
            confirmation = true,
        )
        val gate = AccountDataWriteGate()
        gate.activate("token")
        val manager = FavoriteSyncManager(
            repository,
            SyncTestNoOpPlayback,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            gate,
            FavoriteStateCoordinator(gate),
        )

        val outcome = manager.toggleWithResult(sampleTrack(isLoved = false, lovedCount = 7)).awaitOutcome()
        advanceUntilIdle()

        assertThat(outcome).isEqualTo(FavoriteToggleOutcome.CONFIRMED)
        assertThat(repository.toggleCalls).isEqualTo(1)
        assertThat(repository.confirmationCalls).isEqualTo(1)
    }
}

private class ConfirmingScopedFavoriteRepository(
    toggleResults: List<Boolean?>,
    private val confirmation: Boolean?,
) : MeRepository by FailingMeRepository,
    AccountScopedFavoriteRepository {
    private val remainingToggleResults = ArrayDeque(toggleResults)
    var toggleCalls = 0
        private set
    var confirmationCalls = 0
        private set

    override suspend fun toggleFavoriteForAccount(
        trackId: String,
        authToken: String,
        accountGeneration: AccountDataWriteGate.Generation,
    ): Boolean? {
        toggleCalls += 1
        return remainingToggleResults.removeFirst()
    }

    override suspend fun favoriteStateForAccount(
        trackId: String,
        authToken: String,
        accountGeneration: AccountDataWriteGate.Generation,
    ): Boolean? {
        confirmationCalls += 1
        return confirmation
    }
}

private class RecordingScopedFavoriteRepository :
    MeRepository by FailingMeRepository,
    AccountScopedFavoriteRepository {
    val authTokens = mutableListOf<String>()

    override suspend fun toggleFavoriteForAccount(
        trackId: String,
        authToken: String,
        accountGeneration: AccountDataWriteGate.Generation,
    ): Boolean {
        authTokens += authToken
        return true
    }
}

private class SwitchingScopedFavoriteRepository(
    private val gate: AccountDataWriteGate,
) : MeRepository by FailingMeRepository,
    AccountScopedFavoriteRepository {
    val authTokens = mutableListOf<String>()
    var localWriteCount = 0
        private set

    override suspend fun toggleFavoriteForAccount(
        trackId: String,
        authToken: String,
        accountGeneration: AccountDataWriteGate.Generation,
    ): Boolean {
        authTokens += authToken
        gate.deactivate()
        gate.activate("token-b")
        gate.writeIfCurrent(accountGeneration) {
            localWriteCount += 1
        }
        return true
    }
}

private class DeferredFavoriteRepository : MeRepository by FailingMeRepository {
    val started = CompletableDeferred<Unit>()
    val result = CompletableDeferred<Boolean?>()

    override suspend fun toggleFavorite(trackId: String): Boolean? {
        started.complete(Unit)
        return result.await()
    }
}

private class SequencedFavoriteRepository(results: List<Boolean?>) : MeRepository by FailingMeRepository {
    private val remaining = ArrayDeque(results)

    override suspend fun toggleFavorite(trackId: String): Boolean? = remaining.removeFirst()
}

private class RecordingSyncPlayback : dev.josu.hypecar.core.model.repository.PlaybackRepository {
    val favoriteUpdates = mutableListOf<Pair<String, Boolean>>()
    override val queue: kotlinx.coroutines.flow.StateFlow<dev.josu.hypecar.core.model.PlaybackQueue> =
        kotlinx.coroutines.flow.MutableStateFlow(dev.josu.hypecar.core.model.PlaybackQueue())
    override suspend fun play(tracks: List<Track>, startIndex: Int) = Unit
    override suspend fun playFromTrack(track: Track) = Unit
    override suspend fun togglePlayPause() = Unit
    override suspend fun skipNext() = Unit
    override suspend fun skipPrevious() = Unit
    override suspend fun seekTo(positionMs: Long) = Unit
    override suspend fun toggleShuffle() = Unit
    override suspend fun cycleRepeatMode() = Unit
    override suspend fun updateFavorite(trackId: String, isLoved: Boolean) {
        favoriteUpdates += trackId to isLoved
    }
}

private object FailingMeRepository : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private fun sampleTrack(
    isLoved: Boolean,
    lovedCount: Int,
) = Track(
    id = "39v49",
    title = "Alpha",
    artist = "Beta",
    postedBy = "Blog",
    postedById = 1,
    postedCount = 2,
    postDescription = "Post",
    datePostedEpochSeconds = 1_700_000_000L,
    postUrl = "https://example.com/post",
    itunesUrl = "",
    isLoved = isLoved,
    lovedCount = lovedCount,
)

private object SyncTestNoOpPlayback : dev.josu.hypecar.core.model.repository.PlaybackRepository {
    override val queue: kotlinx.coroutines.flow.StateFlow<dev.josu.hypecar.core.model.PlaybackQueue> =
        kotlinx.coroutines.flow.MutableStateFlow(dev.josu.hypecar.core.model.PlaybackQueue())
    override suspend fun play(tracks: List<Track>, startIndex: Int) = Unit
    override suspend fun playFromTrack(track: Track) = Unit
    override suspend fun togglePlayPause() = Unit
    override suspend fun skipNext() = Unit
    override suspend fun skipPrevious() = Unit
    override suspend fun seekTo(positionMs: Long) = Unit
    override suspend fun toggleShuffle() = Unit
    override suspend fun cycleRepeatMode() = Unit
    override suspend fun updateFavorite(trackId: String, isLoved: Boolean) = Unit
}
