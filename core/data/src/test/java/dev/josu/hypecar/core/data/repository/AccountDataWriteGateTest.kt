package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.LatestMode
import dev.josu.hypecar.core.network.dto.TrackDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class AccountDataWriteGateTest {
    @Test
    fun `account boundary exposes active state and advances on every invalidation`() = runBlocking {
        val gate = AccountDataWriteGate(initiallyActive = true)

        assertThat(gate.accountBoundary.value).isEqualTo(AccountBoundary(0L, isActive = true))
        gate.deactivate()
        assertThat(gate.accountBoundary.value).isEqualTo(AccountBoundary(1L, isActive = false))
        gate.activate()
        assertThat(gate.accountBoundary.value).isEqualTo(AccountBoundary(2L, isActive = true))
        gate.wipe { }
        assertThat(gate.accountBoundary.value).isEqualTo(AccountBoundary(3L, isActive = false))
    }

    @Test
    fun `catalog fetch delayed across account invalidation cannot restore wiped rows`() = runBlocking {
        withTimeout(5_000) {
            val api = DelayedCatalogApi()
            val trackDao = FakeTrackDao()
            val trackListDao = FakeTrackListDao()
            val gate = AccountDataWriteGate(initiallyActive = true)
            val repository = DefaultCatalogRepository(api, trackDao, trackListDao, Json, gate)

            val fetch = async {
                repository.latest(
                    mode = LatestMode.ALL,
                    page = 1,
                    count = 30,
                    forceRefresh = true,
                )
            }
            api.requestStarted.await()

            gate.wipe {
                trackDao.tracks.clear()
                trackListDao.byKey.clear()
            }
            api.response.complete(listOf(sampleTrackDto("old-account-track")))

            val failure = runCatching { fetch.await() }.exceptionOrNull()

            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(trackDao.tracks).isEmpty()
            assertThat(trackListDao.byKey).isEmpty()
        }
    }

    @Test
    fun `write and wipe are serialized on the same boundary`() = runBlocking {
        withTimeout(5_000) {
            val gate = AccountDataWriteGate(initiallyActive = true)
            val generation = gate.captureGeneration()
            val releaseWrite = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()

            val write = async(start = CoroutineStart.UNDISPATCHED) {
                gate.writeIfCurrent(generation) {
                    events += "write-start"
                    releaseWrite.await()
                    events += "write-end"
                }
            }
            val wipe = async(start = CoroutineStart.UNDISPATCHED) {
                gate.wipe {
                    events += "wipe"
                }
            }

            assertThat(events).containsExactly("write-start")
            releaseWrite.complete(Unit)

            assertThat(write.await()).isTrue()
            wipe.await()
            assertThat(events).containsExactly("write-start", "write-end", "wipe").inOrder()
        }
    }

    @Test
    fun `history writes and api calls are rejected after the account is deactivated`() = runBlocking {
        val gate = AccountDataWriteGate(initiallyActive = true)
        val historyDao = FakeHistoryDao()
        val api = RecordingHistoryApi()
        val repository = DefaultHistoryRepository(
            api = api,
            historyDao = historyDao,
            trackDao = FakeTrackDao(),
            accountDataWriteGate = gate,
        )

        gate.deactivate()
        val acknowledged = repository.postListen("old-queue-after-logout", positionSeconds = 11)

        assertThat(acknowledged).isFalse()
        assertThat(historyDao.entries).isEmpty()
        assertThat(api.callCount).isEqualTo(0)
    }

    @Test
    fun `a write waiting at the gate preserves cancellation`() = runBlocking {
        withTimeout(5_000) {
            val gate = AccountDataWriteGate(initiallyActive = true)
            val generation = gate.captureGeneration()
            val releaseFirstWrite = CompletableDeferred<Unit>()
            val firstWrite = async(start = CoroutineStart.UNDISPATCHED) {
                gate.writeIfCurrent(generation) {
                    releaseFirstWrite.await()
                }
            }
            val waitingWrite = async(start = CoroutineStart.UNDISPATCHED) {
                gate.writeIfCurrent(generation) { }
            }

            waitingWrite.cancel()
            val cancellation = runCatching { waitingWrite.await() }.exceptionOrNull()
            releaseFirstWrite.complete(Unit)

            assertThat(cancellation).isInstanceOf(CancellationException::class.java)
            assertThat(firstWrite.await()).isTrue()
        }
    }
}

private class DelayedCatalogApi : FakeHypeApiService() {
    val requestStarted = CompletableDeferred<Unit>()
    val response = CompletableDeferred<List<TrackDto>>()

    override suspend fun tracks(params: Map<String, String>): List<TrackDto> {
        requestStarted.complete(Unit)
        return response.await()
    }
}

private class RecordingHistoryApi : FakeHypeApiService() {
    var callCount = 0
        private set

    override suspend fun postHistory(type: String, itemId: String, position: Int, authToken: String?) =
        "1".toResponseBody().also { callCount += 1 }
}
