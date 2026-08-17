package dev.josu.hypecar.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.TrackThumbnails
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.OfflineDownloadStatus
import dev.josu.hypecar.core.model.repository.OfflineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * End-to-end orchestration test for [DefaultOfflineRepository]. Uses a real
 * Robolectric Context with DataStore + filesystem, MockWebServer for the audio
 * download host, and a scripted [MeRepository] for favorites.
 *
 * Real-IO timing means these tests use [runBlocking] (not virtual-time runTest)
 * so DataStore writes and OkHttp calls actually complete.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultOfflineRepositoryTest {
    private companion object {
        const val OneTimeWorkName = "offline-favorites-sync-now"
        const val PeriodicWorkName = "offline-favorites-periodic-sync"
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val repositories = mutableListOf<DefaultOfflineRepository>()

    @Before
    fun setUp() {
        server.start()
        // Wipe any state left by other tests.
        context.preferencesDataStoreFile("offline.preferences_pb").delete()
        File(context.filesDir, "offline_audio").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build(),
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            repositories.forEach { it.shutdown() }
            repositories.clear()
        }
        WorkManagerTestInitHelper.closeWorkDatabase()
        server.shutdown()
        context.preferencesDataStoreFile("offline.preferences_pb").delete()
        File(context.filesDir, "offline_audio").deleteRecursively()
    }

    @Test
    fun `setEnabled true publishes enabled status`() = runBlocking {
        val repo = newRepo(me = EmptyMe)

        repo.setEnabled(true)

        val status = withTimeout(2_000L) {
            repo.status.first { it.isEnabled }
        }
        assertThat(status.isEnabled).isTrue()
        assertThat(status.quotaBytes).isEqualTo(500L * 1024L * 1024L)
    }

    @Test
    fun `startup restores delayed periodic work when persisted offline mode is enabled`() = runBlocking {
        writePersistedEnabled(true)
        val me = CountingMe()
        val gate = AccountDataWriteGate(initiallyActive = false)

        val repo = newRepo(me = me, accountDataWriteGate = gate)
        gate.activate()

        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        val periodic = awaitUniqueWork(PeriodicWorkName) { infos ->
            infos.any { it.state == WorkInfo.State.ENQUEUED }
        }
        assertThat(periodic.count { it.state == WorkInfo.State.ENQUEUED }).isEqualTo(1)
        assertThat(uniqueWork(OneTimeWorkName)).isEmpty()
        assertThat(me.favoriteCalls).isEqualTo(0)
    }

    @Test
    fun `startup cancels zombie unique work when persisted offline mode is disabled`() = runBlocking {
        writePersistedEnabled(false)
        enqueueZombieOfflineWork()
        val me = CountingMe()
        val gate = AccountDataWriteGate(initiallyActive = false)

        val repo = newRepo(me = me, accountDataWriteGate = gate)
        gate.deactivate()

        withTimeout(2_000L) { repo.status.first { !it.isEnabled } }
        val oneTime = awaitUniqueWork(OneTimeWorkName) { infos ->
            infos.isNotEmpty() && infos.all { it.state == WorkInfo.State.CANCELLED }
        }
        val periodic = awaitUniqueWork(PeriodicWorkName) { infos ->
            infos.isNotEmpty() && infos.all { it.state == WorkInfo.State.CANCELLED }
        }
        assertThat(oneTime).isNotEmpty()
        assertThat(periodic).isNotEmpty()
        assertThat(me.favoriteCalls).isEqualTo(0)
    }

    @Test
    fun `zombie worker no-ops while persisted offline mode is disabled`() = runBlocking {
        writePersistedEnabled(false)
        val me = CountingMe()
        val repo = newRepo(me = me)

        val result = buildWorker(repo).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(me.favoriteCalls).isEqualTo(0)
    }

    @Test
    fun `syncFavorites downloads tracks and writes records`() = runBlocking {
        val tracks = listOf(track("a"), track("b"))
        val repo = newRepo(me = StaticMe(favorites = tracks))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        // Two successful downloads, then empty page to terminate paging.
        server.enqueue(MockResponse().setBody("aaaa"))
        server.enqueue(MockResponse().setBody("bbbbbb"))

        repo.syncFavorites()

        val status = withTimeout(2_000L) { repo.status.first { it.downloadedTrackCount == 2 } }
        assertThat(status.downloadedTrackCount).isEqualTo(2)
        assertThat(status.usedBytes).isEqualTo(10L)
        assertThat(status.error).isNull()
        assertThat(repo.cachedAudioUri("a")).isNotNull()
        assertThat(repo.cachedAudioUri("b")).isNotNull()
    }

    @Test
    fun `sync attempt persists partial downloads and retries transient http failure without advancing timestamp`() = runBlocking {
        val repo = newRepo(me = StaticMe(favorites = listOf(track("a"), track("b"))))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setBody("downloaded"))
        server.enqueue(MockResponse().setResponseCode(503))

        val result = repo.runSyncAttempt()

        assertThat(result).isEqualTo(
            OfflineSyncAttemptResult.Failure(
                message = "Offline audio request failed (HTTP 503)",
                retryable = true,
            ),
        )
        assertThat(repo.status.value.downloadedTrackCount).isEqualTo(1)
        assertThat(repo.status.value.usedBytes).isEqualTo(10L)
        assertThat(repo.status.value.lastSyncedAtEpochSeconds).isNull()
        assertThat(repo.cachedAudioUri("a")).isNotNull()
        assertThat(repo.cachedAudioUri("b")).isNull()
    }

    @Test
    fun `sync attempt permanently fails an empty eligible audio response`() = runBlocking {
        val repo = newRepo(me = StaticMe(favorites = listOf(track("empty"))))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setBody(""))

        val result = repo.runSyncAttempt()

        assertThat(result).isEqualTo(
            OfflineSyncAttemptResult.Failure(
                message = "Offline audio response was empty",
                retryable = false,
            ),
        )
        assertThat(repo.status.value.downloadedTrackCount).isEqualTo(0)
        assertThat(repo.status.value.lastSyncedAtEpochSeconds).isNull()
    }

    @Test
    fun `sync attempt permanently fails a non-retryable http response`() = runBlocking {
        val repo = newRepo(me = StaticMe(favorites = listOf(track("missing"))))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repo.runSyncAttempt()

        assertThat(result).isEqualTo(
            OfflineSyncAttemptResult.Failure(
                message = "Offline audio request failed (HTTP 404)",
                retryable = false,
            ),
        )
        assertThat(repo.status.value.lastSyncedAtEpochSeconds).isNull()
    }

    @Test
    fun `sync attempt retries transport failure without advancing timestamp`() = runBlocking {
        val repo = newRepo(me = StaticMe(favorites = listOf(track("offline"))))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = repo.runSyncAttempt()

        assertThat(result).isInstanceOf(OfflineSyncAttemptResult.Failure::class.java)
        result as OfflineSyncAttemptResult.Failure
        assertThat(result.retryable).isTrue()
        assertThat(repo.status.value.downloadedTrackCount).isEqualTo(0)
        assertThat(repo.status.value.lastSyncedAtEpochSeconds).isNull()
    }

    @Test
    fun `cancelling sync cancels an audio call stalled before response headers`() = runBlocking {
        val requestSent = CompletableDeferred<Unit>()
        val callCancelled = CompletableDeferred<Unit>()
        val repo = newRepo(
            me = StaticMe(favorites = listOf(track("stalled"))),
            clientEventListener = object : EventListener() {
                override fun requestHeadersEnd(call: Call, request: Request) {
                    requestSent.complete(Unit)
                }

                override fun canceled(call: Call) {
                    callCancelled.complete(Unit)
                }
            },
        )
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.STALL_SOCKET_AT_START))
        val waiter = async { repo.runSyncAttempt() }
        withTimeout(2_000L) { repo.status.first { it.isSyncing } }
        withTimeout(2_000L) { requestSent.await() }

        withTimeout(2_000L) { waiter.cancelAndJoin() }

        withTimeout(2_000L) { callCancelled.await() }
        assertThat(repo.status.value.isSyncing).isFalse()
        assertThat(File(context.filesDir, "offline_audio/stalled.audio.tmp").exists()).isFalse()
    }

    @Test
    fun `syncFavorites skips audioUnavailable tracks`() = runBlocking {
        val available = track("ok")
        val unavailable = track("nope").copy(audioUnavailable = true)
        val repo = newRepo(me = StaticMe(favorites = listOf(unavailable, available)))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setBody("audio"))

        repo.syncFavorites()

        val status = withTimeout(2_000L) { repo.status.first { it.downloadedTrackCount == 1 } }
        assertThat(status.downloadedTrackCount).isEqualTo(1)
        assertThat(repo.cachedAudioUri("ok")).isNotNull()
        assertThat(repo.cachedAudioUri("nope")).isNull()
        // Only one HTTP call — no request was made for the unavailable track.
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `concurrent sync callers await the same attempt`() = runBlocking {
        val me = BlockingMe()
        val repo = newRepo(me = me)
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }

        val first = async { repo.runSyncAttempt() }
        withTimeout(2_000L) { me.started.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { repo.runSyncAttempt() }

        assertThat(second.isCompleted).isFalse()
        assertThat(me.favoriteCalls).isEqualTo(1)
        me.release.complete(Unit)

        assertThat(withTimeout(2_000L) { first.await() }).isEqualTo(OfflineSyncAttemptResult.Success)
        assertThat(withTimeout(2_000L) { second.await() }).isEqualTo(OfflineSyncAttemptResult.Success)
        assertThat(me.favoriteCalls).isEqualTo(1)
    }

    @Test
    fun `cancelling the sole waiter cancels the repository owned attempt`() = runBlocking {
        val me = BlockingMe()
        val repo = newRepo(me = me)
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }

        val waiter = async { repo.runSyncAttempt() }
        withTimeout(2_000L) { me.started.await() }

        waiter.cancelAndJoin()

        withTimeout(2_000L) { me.cancelled.await() }
        assertThat(repo.status.value.isSyncing).isFalse()
        assertThat(me.favoriteCalls).isEqualTo(1)
    }

    @Test
    fun `cancelling one waiter does not cancel the shared sync attempt`() = runBlocking {
        val me = BlockingMe()
        val repo = newRepo(me = me)
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }

        val cancelledWaiter = async { repo.runSyncAttempt() }
        withTimeout(2_000L) { me.started.await() }
        val survivingWaiter = async(start = CoroutineStart.UNDISPATCHED) { repo.runSyncAttempt() }

        cancelledWaiter.cancelAndJoin()
        assertThat(survivingWaiter.isCompleted).isFalse()
        assertThat(me.cancelled.isCompleted).isFalse()
        assertThat(me.favoriteCalls).isEqualTo(1)
        me.release.complete(Unit)

        assertThat(withTimeout(2_000L) { survivingWaiter.await() })
            .isEqualTo(OfflineSyncAttemptResult.Success)
        assertThat(me.favoriteCalls).isEqualTo(1)
    }

    @Test
    fun `stopping a worker cancels its sole repository sync attempt`() = runBlocking {
        val me = BlockingMe()
        val repo = newRepo(me = me)
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        val worker = buildWorker(repo)

        val workerRun = async { worker.doWork() }
        withTimeout(2_000L) { me.started.await() }

        workerRun.cancelAndJoin()

        withTimeout(2_000L) { me.cancelled.await() }
        assertThat(repo.status.value.isSyncing).isFalse()
    }

    @Test
    fun `concurrent admin calls wait for cancellation and finish in one transaction order`() = runBlocking {
        val me = CancellationResistantMe()
        val repo = newRepo(me = me)
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        val syncWaiter = async { repo.runSyncAttempt() }
        withTimeout(2_000L) { me.started.await() }

        val disable = async(start = CoroutineStart.UNDISPATCHED) { repo.setEnabled(false) }
        withTimeout(2_000L) { me.cancellationObserved.await() }
        val targetQuota = 800L * 1024L * 1024L
        val quotaChange = async(start = CoroutineStart.UNDISPATCHED) { repo.setQuotaBytes(targetQuota) }
        val clear = async(start = CoroutineStart.UNDISPATCHED) { repo.clearDownloads() }

        assertThat(disable.isCompleted).isFalse()
        assertThat(quotaChange.isCompleted).isFalse()
        assertThat(clear.isCompleted).isFalse()
        assertThat(repo.status.value.quotaBytes).isEqualTo(500L * 1024L * 1024L)

        me.release.complete(Unit)
        withTimeout(2_000L) {
            disable.await()
            quotaChange.await()
            clear.await()
        }
        syncWaiter.cancelAndJoin()

        val finalStatus = withTimeout(2_000L) {
            repo.status.first { !it.isEnabled && it.quotaBytes == targetQuota }
        }
        assertThat(finalStatus.downloadedTrackCount).isEqualTo(0)
        assertThat(finalStatus.usedBytes).isEqualTo(0L)
    }

    @Test
    fun `sync failure without an exception message publishes a stable marker`() = runBlocking {
        val repo = newRepo(me = ThrowingMe(RuntimeException()))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }

        val result = repo.runSyncAttempt()

        assertThat(result).isEqualTo(OfflineSyncAttemptResult.Failure("Offline sync failed"))
        assertThat(repo.status.value.error).isEqualTo("Offline sync failed")
        assertThat(repo.status.value.isSyncing).isFalse()
    }

    @Test
    fun `syncFavorites redownloads a record whose file was deleted`() = runBlocking {
        val repo = newRepo(me = StaticMe(favorites = listOf(track("a"))))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setBody("old"))
        repo.syncFavorites()
        val cachedFile = File(checkNotNull(Uri.parse(repo.cachedAudioUri("a")).path))
        assertThat(cachedFile.delete()).isTrue()
        server.enqueue(MockResponse().setBody("replacement"))

        repo.syncFavorites()

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(File(checkNotNull(Uri.parse(repo.cachedAudioUri("a")).path)).length()).isEqualTo(11L)
        assertThat(repo.status.value.usedBytes).isEqualTo(11L)
    }

    @Test
    fun `syncFavorites redownloads a record whose file was truncated`() = runBlocking {
        val repo = newRepo(me = StaticMe(favorites = listOf(track("a"))))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setBody("old"))
        repo.syncFavorites()
        val cachedFile = File(checkNotNull(Uri.parse(repo.cachedAudioUri("a")).path))
        cachedFile.outputStream().use { }
        assertThat(cachedFile.length()).isEqualTo(0L)
        server.enqueue(MockResponse().setBody("new-data"))

        repo.syncFavorites()

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(File(checkNotNull(Uri.parse(repo.cachedAudioUri("a")).path)).length()).isEqualTo(8L)
        assertThat(repo.status.value.usedBytes).isEqualTo(8L)
    }

    @Test
    fun `clearDownloads removes cached files and records`() = runBlocking {
        val repo = newRepo(me = StaticMe(favorites = listOf(track("a"))))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setBody("data"))
        repo.syncFavorites()
        withTimeout(2_000L) { repo.status.first { it.downloadedTrackCount == 1 } }
        assertThat(repo.cachedAudioUri("a")).isNotNull()

        repo.clearDownloads()

        val status = withTimeout(2_000L) { repo.status.first { it.downloadedTrackCount == 0 } }
        assertThat(status.usedBytes).isEqualTo(0L)
        assertThat(repo.cachedAudioUri("a")).isNull()
    }

    @Test
    fun `clearDownloads retains records for retry when any cache entry cannot be deleted`() = runBlocking {
        val repo = newRepo(me = StaticMe(favorites = listOf(track("a"))))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setBody("data"))
        repo.syncFavorites()
        withTimeout(2_000L) { repo.status.first { it.downloadedTrackCount == 1 } }
        val undeletable = nonEmptyCacheDirectory("undeletable")

        val failure = runCatching { repo.clearDownloads() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(repo.status.value.downloadedTrackCount).isEqualTo(1)
        assertThat(repo.status.value.usedBytes).isEqualTo(4L)
        assertThat(undeletable.exists()).isTrue()

        assertThat(undeletable.deleteRecursively()).isTrue()
        repo.clearDownloads()
        val cleared = withTimeout(2_000L) { repo.status.first { it.downloadedTrackCount == 0 } }
        assertThat(cleared.usedBytes).isEqualTo(0L)
    }

    @Test
    fun `cachedAudioUri returns null when offline mode is disabled`() = runBlocking {
        val repo = newRepo(me = StaticMe(favorites = listOf(track("a"))))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setBody("x"))
        repo.syncFavorites()
        withTimeout(2_000L) { repo.status.first { it.downloadedTrackCount == 1 } }
        assertThat(repo.cachedAudioUri("a")).isNotNull()

        repo.setEnabled(false)
        withTimeout(2_000L) { repo.status.first { !it.isEnabled } }

        assertThat(repo.cachedAudioUri("a")).isNull()
    }

    @Test
    fun `account deactivation immediately neutralizes status and cached audio`() = runBlocking {
        val gate = AccountDataWriteGate()
        val repo = newRepo(
            me = StaticMe(favorites = listOf(track("a"))),
            accountDataWriteGate = gate,
        )
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setBody("audio"))
        repo.syncFavorites()
        withTimeout(2_000L) { repo.status.first { it.downloadedTrackCount == 1 } }

        gate.deactivate()

        val neutral = withTimeout(2_000L) {
            repo.status.first { !it.isEnabled && it.downloadedTrackCount == 0 }
        }
        assertThat(neutral).isEqualTo(OfflineDownloadStatus())
        assertThat(repo.cachedAudioUri("a")).isNull()
    }

    @Test
    fun `clearAccountData resets settings records files and sync metadata`() = runBlocking {
        val gate = AccountDataWriteGate()
        val repo = newRepo(
            me = StaticMe(favorites = listOf(track("a"))),
            accountDataWriteGate = gate,
        )
        repo.setQuotaBytes(800L * 1024L * 1024L)
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setBody("audio"))
        repo.syncFavorites()
        val populated = withTimeout(2_000L) {
            repo.status.first { it.downloadedTrackCount == 1 && it.lastSyncedAtEpochSeconds != null }
        }
        assertThat(populated.quotaBytes).isEqualTo(800L * 1024L * 1024L)
        val cachedFile = File(checkNotNull(Uri.parse(repo.cachedAudioUri("a")).path))

        repo.clearAccountData()

        val cleared = withTimeout(2_000L) {
            repo.status.first {
                !it.isEnabled &&
                    it.quotaBytes == 500L * 1024L * 1024L &&
                    it.downloadedTrackCount == 0 &&
                    it.lastSyncedAtEpochSeconds == null
            }
        }
        assertThat(cleared).isEqualTo(OfflineDownloadStatus())
        assertThat(cachedFile.exists()).isFalse()
        assertThat(repo.cachedAudioUri("a")).isNull()
    }

    @Test
    fun `clearAccountData fails and remains retryable when a cache entry cannot be deleted`() = runBlocking {
        val repo = newRepo(me = StaticMe(favorites = listOf(track("a"))))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setBody("audio"))
        repo.syncFavorites()
        withTimeout(2_000L) { repo.status.first { it.downloadedTrackCount == 1 } }
        val undeletable = nonEmptyCacheDirectory("account-cleanup-blocker")

        val failure = runCatching { repo.clearAccountData() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(repo.status.value.isEnabled).isTrue()
        assertThat(repo.status.value.downloadedTrackCount).isEqualTo(1)
        assertThat(undeletable.exists()).isTrue()

        assertThat(undeletable.deleteRecursively()).isTrue()
        repo.clearAccountData()
        val cleared = withTimeout(2_000L) { repo.status.first { it == OfflineDownloadStatus() } }
        assertThat(cleared).isEqualTo(OfflineDownloadStatus())
    }

    @Test
    fun `quota trim retains manifest bytes when an eviction cannot be deleted`() {
        val oldest = OfflineTrackRecord("oldest", "oldest.audio", 100L, 1L)
        val newest = OfflineTrackRecord("newest", "newest.audio", 100L, 2L)
        val plan = OfflineEvictionPlanner.plan(
            records = listOf(oldest, newest),
            quotaBytes = 100L,
        )

        val committed = OfflineEvictionPlanCommitter.commit(plan) { false }

        assertThat(committed.map { it.trackId }).containsExactly("newest", "oldest")
        assertThat(committed.sumOf { it.byteSize }).isEqualTo(200L)
    }

    private suspend fun writePersistedEnabled(enabled: Boolean) {
        val dataStoreJob = SupervisorJob()
        val testDataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dataStoreJob + Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile("offline.preferences_pb") },
        )
        testDataStore.edit { prefs ->
            prefs[booleanPreferencesKey("offline_enabled")] = enabled
        }
        dataStoreJob.cancelAndJoin()
    }

    private suspend fun enqueueZombieOfflineWork() {
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(
            OneTimeWorkName,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<OfflineFavoritesSyncWorker>()
                .setInitialDelay(1L, TimeUnit.DAYS)
                .build(),
        ).await()
        workManager.enqueueUniquePeriodicWork(
            PeriodicWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<OfflineFavoritesSyncWorker>(6L, TimeUnit.HOURS)
                .setInitialDelay(6L, TimeUnit.HOURS)
                .build(),
        ).await()
    }

    private suspend fun awaitUniqueWork(
        name: String,
        predicate: (List<WorkInfo>) -> Boolean,
    ): List<WorkInfo> = withTimeout(2_000L) {
        while (true) {
            val infos = uniqueWork(name)
            if (predicate(infos)) return@withTimeout infos
            delay(10L)
        }
        error("unreachable")
    }

    private suspend fun uniqueWork(name: String): List<WorkInfo> =
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(name)
                .get(2L, TimeUnit.SECONDS)
        }

    private fun nonEmptyCacheDirectory(name: String): File =
        File(context.filesDir, "offline_audio/$name").apply {
            assertThat(mkdirs()).isTrue()
            File(this, "child").writeText("keeps parent deletion from succeeding")
        }

    private fun newRepo(
        me: MeRepository,
        accountDataWriteGate: AccountDataWriteGate = AccountDataWriteGate(),
        clientEventListener: EventListener? = null,
    ): DefaultOfflineRepository {
        val rewritingClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor(StreamUrlRewriter(server.url("/serve/")))
            .apply { clientEventListener?.let(::eventListener) }
            .build()
        return DefaultOfflineRepository(
            context = context,
            meRepository = me,
            client = rewritingClient,
            json = Json,
            accountDataWriteGate = accountDataWriteGate,
        ).also(repositories::add)
    }

    private fun buildWorker(repo: OfflineRepository): OfflineFavoritesSyncWorker =
        TestListenableWorkerBuilder<OfflineFavoritesSyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = OfflineFavoritesSyncWorker(appContext, workerParameters, repo)
            }).build()

    private fun track(id: String) = Track(
        id = id,
        artist = "x",
        title = "y",
        lovedCount = 0,
        postedBy = "z",
        postedById = 0,
        postedCount = 0,
        postDescription = "",
        datePostedEpochSeconds = 0L,
        postUrl = "",
        itunesUrl = "",
        thumbnails = TrackThumbnails(),
    )
}

/** Rewrites every outbound `https://hypem.com/serve/public/...` request at the
 *  network boundary so MockWebServer can answer it. */
private class StreamUrlRewriter(private val mockBase: HttpUrl) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host == "hypem.com") {
            val rewritten = request.newBuilder()
                .url(mockBase.newBuilder().addPathSegments(request.url.encodedPath.removePrefix("/serve/")).build())
                .build()
            return chain.proceed(rewritten)
        }
        return chain.proceed(request)
    }
}

private class StaticMe(private val favorites: List<Track>) : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> =
        if (page == 1) favorites else emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private object EmptyMe : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private class CountingMe : MeRepository {
    var favoriteCalls: Int = 0
        private set

    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> {
        favoriteCalls += 1
        return emptyList()
    }

    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private class BlockingMe : MeRepository {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    var favoriteCalls: Int = 0
        private set

    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> {
        favoriteCalls += 1
        started.complete(Unit)
        try {
            release.await()
        } catch (cancellation: CancellationException) {
            cancelled.complete(Unit)
            throw cancellation
        }
        return emptyList()
    }

    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private class CancellationResistantMe : MeRepository {
    val started = CompletableDeferred<Unit>()
    val cancellationObserved = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> {
        started.complete(Unit)
        try {
            release.await()
        } catch (cancellation: CancellationException) {
            cancellationObserved.complete(Unit)
            withContext(NonCancellable) { release.await() }
            throw cancellation
        }
        return emptyList()
    }

    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private class ThrowingMe(private val failure: Throwable) : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = throw failure
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}
