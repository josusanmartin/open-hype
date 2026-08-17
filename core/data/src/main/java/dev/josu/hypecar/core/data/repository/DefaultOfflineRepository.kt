package dev.josu.hypecar.core.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.OfflineDownloadStatus
import dev.josu.hypecar.core.model.repository.OfflineRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
internal class DefaultOfflineRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val meRepository: MeRepository,
    private val client: OkHttpClient,
    private val json: Json,
    private val accountDataWriteGate: AccountDataWriteGate = AccountDataWriteGate(),
) : OfflineRepository,
    OfflineSyncAttemptRunner {
    private companion object {
        const val Tag = "OfflineRepository"
        const val OneTimeWorkName = "offline-favorites-sync-now"
        const val PeriodicWorkName = "offline-favorites-periodic-sync"
        const val DefaultQuotaBytes = 500L * 1024L * 1024L
        const val FavoritesPageSize = 50
        const val MaxFavoritePages = 40
        const val RecordPersistBatchSize = 10
        val SyncConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore = PreferenceDataStoreFactory.create(
        // A corrupt preferences file must degrade to "no downloads recorded",
        // not poison every subsequent read/edit until app data is cleared.
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { context.preferencesDataStoreFile("offline.preferences_pb") },
    )

    /** Streams whole-file downloads without the API client's short call timeout. */
    private val downloadClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    }
    private val enabledKey = booleanPreferencesKey("offline_enabled")
    private val quotaBytesKey = longPreferencesKey("quota_bytes")
    private val recordsKey = stringPreferencesKey("download_records")
    private val lastSyncedAtKey = longPreferencesKey("last_synced_at")
    private val _status = MutableStateFlow(OfflineDownloadStatus())

    @Volatile
    private var recordsByTrackId: Map<String, OfflineTrackRecord> = emptyMap()

    /** Serializes filesystem/DataStore mutations against quota trims and clears. */
    private val syncMutex = Mutex()

    /** Keeps explicit settings and filesystem mutations in one total order. */
    private val adminMutex = Mutex()

    /** Serializes unique-work repair without delaying foreground sync waiters. */
    private val scheduledWorkMutex = Mutex()

    /** Protects the shared attempt reference without holding a lock during IO. */
    private val activeSyncMutex = Mutex()
    private var activeSyncAttempt: SharedSyncAttempt? = null

    private class SharedSyncAttempt(
        val deferred: Deferred<OfflineSyncAttemptResult>,
        var waiterCount: Int = 0,
    )

    override val status: StateFlow<OfflineDownloadStatus> = _status

    init {
        scope.launch {
            dataStore.data
                .catch { emit(emptyPreferences()) }
                .combine(accountDataWriteGate.accountBoundary) { prefs, boundary -> prefs to boundary }
                .collect { (prefs, boundary) ->
                    if (!boundary.isActive) {
                        recordsByTrackId = emptyMap()
                        _status.value = OfflineDownloadStatus()
                        return@collect
                    }
                    val records = reconcileRecords(decodeRecords(prefs[recordsKey]))
                    recordsByTrackId = records.associateBy { it.trackId }
                    _status.update { current ->
                        current.copy(
                            isEnabled = prefs[enabledKey] ?: false,
                            quotaBytes = prefs[quotaBytesKey] ?: DefaultQuotaBytes,
                            usedBytes = records.sumOf { it.byteSize },
                            downloadedTrackCount = records.size,
                            lastSyncedAtEpochSeconds = prefs[lastSyncedAtKey],
                        )
                    }
                }
        }
        scope.launch {
            dataStore.data
                .map { prefs -> Result.success(prefs[enabledKey] == true) }
                .catch { error ->
                    // An unreadable preference is not proof that offline mode
                    // is disabled. Preserve existing work and retry after the
                    // next state transition/process start.
                    Log.w(Tag, "Could not read offline state for work reconciliation", error)
                    emit(Result.failure(error))
                }
                .combine(accountDataWriteGate.accountBoundary) { enabledResult, boundary ->
                    // The production gate starts inactive until session
                    // persistence has been read. Do not cancel valid periodic
                    // work during that brief, uninitialized boundary.
                    val persistedEnabled = enabledResult.getOrNull()
                    if (persistedEnabled == null || (!boundary.isActive && boundary.generation == 0L)) {
                        null
                    } else {
                        boundary.isActive && persistedEnabled
                    }
                }
                .filterNotNull()
                .distinctUntilChanged()
                .collect {
                    try {
                        reconcileScheduledWork()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        // WorkManager normally initializes before repositories,
                        // but a failed reconciliation must not take down status.
                        // A later preference/account transition or process start
                        // will retry the idempotent unique-work operation.
                        Log.w(Tag, "Failed to reconcile offline sync work", error)
                    }
                }
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        val generation = accountDataWriteGate.captureGeneration()
        runAdminTransaction {
            requireCurrentAccount(generation)
            dataStore.edit { prefs ->
                prefs[enabledKey] = enabled
                if (prefs[quotaBytesKey] == null) {
                    prefs[quotaBytesKey] = DefaultQuotaBytes
                }
            }
            _status.update { current -> current.copy(isEnabled = enabled) }
            scheduledWorkMutex.withLock {
                if (enabled) {
                    enqueuePeriodicSync()
                    enqueueImmediateSync()
                } else {
                    cancelScheduledSync()
                }
            }
        }
    }

    override suspend fun setQuotaBytes(quotaBytes: Long) {
        val generation = accountDataWriteGate.captureGeneration()
        val sanitized = quotaBytes.coerceAtLeast(50L * 1024L * 1024L)
        runAdminTransaction {
            requireCurrentAccount(generation)
            val updatedPreferences = dataStore.edit { prefs -> prefs[quotaBytesKey] = sanitized }
            _status.update { current -> current.copy(quotaBytes = sanitized) }
            trimToQuota(sanitized)
            if (updatedPreferences[enabledKey] == true) {
                scheduledWorkMutex.withLock { enqueueImmediateSync() }
            }
        }
    }

    override suspend fun syncFavorites() {
        runSyncAttempt()
    }

    /** Concurrent callers await the same in-flight attempt and receive its exact result. */
    override suspend fun runSyncAttempt(): OfflineSyncAttemptResult {
        val generation = accountDataWriteGate.captureGeneration()
        if (!accountDataWriteGate.isCurrentAccount(generation)) {
            return OfflineSyncAttemptResult.Failure(
                message = "Offline sync requires an active account",
                retryable = false,
            )
        }
        val attempt = adminMutex.withLock {
            requireCurrentAccount(generation)
            activeSyncMutex.withLock {
                val shared = activeSyncAttempt
                    ?.takeUnless { it.deferred.isCompleted }
                    ?: SharedSyncAttempt(
                        deferred = scope.async(start = CoroutineStart.LAZY) {
                            syncMutex.withLock { syncFavoritesLocked() }
                        },
                    ).also { created ->
                        activeSyncAttempt = created
                    }
                shared.waiterCount += 1
                shared
            }
        }

        attempt.deferred.start()
        return try {
            attempt.deferred.await()
        } finally {
            releaseSyncWaiter(attempt)
        }
    }

    /** A repository-owned attempt only outlives a caller while another caller still awaits it. */
    private suspend fun releaseSyncWaiter(attempt: SharedSyncAttempt) =
        withContext(NonCancellable) {
            activeSyncMutex.withLock {
                check(attempt.waiterCount > 0) { "Offline sync waiter released more than once" }
                attempt.waiterCount -= 1
                if (attempt.waiterCount == 0) {
                    if (activeSyncAttempt === attempt) {
                        activeSyncAttempt = null
                    }
                    if (!attempt.deferred.isCompleted) {
                        attempt.deferred.cancelAndJoin()
                    }
                }
            }
        }

    /**
     * Blocks new registrations for the whole transaction, but never waits for
     * cancellation while holding activeSyncMutex: a cancelled worker must be
     * able to unregister its waiter before WorkManager reports cancellation.
     */
    private suspend fun <T> runAdminTransaction(block: suspend () -> T): T =
        adminMutex.withLock {
            val detachedAttempt = activeSyncMutex.withLock {
                activeSyncAttempt.also { activeSyncAttempt = null }
            }
            detachedAttempt?.deferred?.cancelAndJoin()
            syncMutex.withLock { block() }
        }

    private suspend fun syncFavoritesLocked(): OfflineSyncAttemptResult {
        val prefsResult = runSuspendCatchingPreservingCancellation { dataStore.data.first() }
        val prefs = prefsResult.getOrElse { error ->
            return publishSyncFailure(error)
        }
        if (prefs[enabledKey] != true) return OfflineSyncAttemptResult.Success
        val quotaBytes = prefs[quotaBytesKey] ?: DefaultQuotaBytes

        _status.update { it.copy(isSyncing = true, error = null) }
        try {
            val result = runSuspendCatchingPreservingCancellation {
                val decodedRecords = decodeRecords(prefs[recordsKey])
                val existingRecords = reconcileRecords(decodedRecords)
                if (existingRecords != decodedRecords) {
                    saveRecords(existingRecords, lastSyncedAt = null)
                }
                cleanupStrayFiles(existingRecords)
                val accumulator = OfflineSyncAccumulator(
                    quotaBytes = quotaBytes,
                    existingRecords = existingRecords,
                )
                val recordPersistBatcher = OfflineRecordPersistBatcher(batchSize = RecordPersistBatchSize)
                val seenFavorites = mutableSetOf<String>()
                val downloadFailures = mutableListOf<OfflineTrackDownloadResult.Failed>()
                var allFavoritesScanned = false

                try {
                    // The full favorites membership is scanned even when the
                    // quota has no headroom — stale-favorite eviction below
                    // needs it, and an exactly-full cache would otherwise
                    // freeze forever (nothing scanned → nothing evicted →
                    // nothing downloadable).
                    var page = 1
                    paging@ while (true) {
                        if (!isStillEnabled()) break
                        val favorites = meRepository.favorites(page = page, count = FavoritesPageSize, forceRefresh = true)
                        if (favorites.isEmpty()) {
                            allFavoritesScanned = true
                            break
                        }

                        for (track in favorites) {
                            if (!isStillEnabled()) break@paging
                            seenFavorites += track.id
                            if (track.audioUnavailable) continue
                            if (accumulator.contains(track.id)) continue
                            if (!accumulator.shouldDownloadNext()) continue
                            val remainingBytes = (quotaBytes - accumulator.usedBytes).coerceAtLeast(0L)
                            when (val download = downloadTrack(track, remainingBytes)) {
                                is OfflineTrackDownloadResult.Downloaded -> {
                                    val records = accumulator.recordDownloaded(download.record)
                                    if (records != null) {
                                        if (recordPersistBatcher.recordDownloaded()) {
                                            saveRecords(records, lastSyncedAt = null)
                                            recordPersistBatcher.markPersisted()
                                        }
                                    } else {
                                        File(cacheDir(), download.record.fileName).delete()
                                    }
                                }

                                is OfflineTrackDownloadResult.Failed -> downloadFailures += download
                                OfflineTrackDownloadResult.SkippedForQuota -> Unit
                            }
                        }

                        if (favorites.size < FavoritesPageSize) {
                            allFavoritesScanned = true
                            break
                        }
                        page += 1
                        if (page > MaxFavoritePages) break
                    }
                } finally {
                    if (recordPersistBatcher.hasPendingWrites) {
                        withContext(NonCancellable) {
                            saveRecords(accumulator.records, lastSyncedAt = null)
                        }
                    }
                }

                val trimmed = trimRecordsToQuota(
                    records = accumulator.records,
                    quotaBytes = quotaBytes,
                    staleTrackIds = if (allFavoritesScanned) {
                        accumulator.records.map { it.trackId }.filterNot { it in seenFavorites }.toSet()
                    } else {
                        emptySet()
                    },
                )
                if (downloadFailures.isEmpty()) {
                    saveRecords(trimmed, lastSyncedAt = nowSeconds())
                    OfflineSyncAttemptResult.Success
                } else {
                    // Successful downloads remain useful, but the attempt did
                    // not fully synchronize the eligible favorites. Leaving
                    // lastSyncedAt untouched prevents a partial cache from
                    // being presented as a completed sync.
                    saveRecords(trimmed, lastSyncedAt = null)
                    downloadFailures.toAttemptFailure()
                }
            }

            return result.fold(
                onSuccess = { attempt ->
                    when (attempt) {
                        OfflineSyncAttemptResult.Success -> {
                            _status.update { it.copy(isSyncing = false, error = null) }
                        }

                        is OfflineSyncAttemptResult.Failure -> {
                            _status.update { it.copy(isSyncing = false, error = attempt.message) }
                        }
                    }
                    attempt
                },
                onFailure = ::publishSyncFailure,
            )
        } finally {
            // Cancellation rethrows past the block above (navigating away from
            // Settings, toggling offline off, a REPLACEd worker); without this
            // reset the UI would show "Syncing…" and keep the button disabled
            // forever.
            if (_status.value.isSyncing) {
                withContext(NonCancellable) {
                    _status.update { it.copy(isSyncing = false) }
                }
            }
        }
    }

    private fun publishSyncFailure(error: Throwable): OfflineSyncAttemptResult.Failure {
        val message = error.offlineSyncFailureMessage()
        _status.update { it.copy(isSyncing = false, error = message) }
        return OfflineSyncAttemptResult.Failure(
            message = message,
            retryable = error.isRetryableOfflineSyncFailure(),
        )
    }

    private suspend fun isStillEnabled(): Boolean =
        runSuspendCatchingPreservingCancellation {
            dataStore.data.first()[enabledKey] == true
        }.getOrDefault(false)

    override suspend fun clearDownloads() {
        val generation = accountDataWriteGate.captureGeneration()
        runAdminTransaction {
            requireCurrentAccount(generation)
            deleteAllCachedFiles()
            saveRecords(emptyList(), lastSyncedAt = _status.value.lastSyncedAtEpochSeconds)
        }
    }

    override suspend fun clearAccountData() {
        runAdminTransaction {
            val workManager = WorkManager.getInstance(context)
            scheduledWorkMutex.withLock { cancelScheduledSync(workManager) }
            // Keep the manifest and settings intact until every file is gone.
            // The session wiper treats this exception as cleanup-pending and
            // retries before another account can become active.
            deleteAllCachedFiles()
            dataStore.edit { prefs -> prefs.clear() }
            recordsByTrackId = emptyMap()
            _status.value = OfflineDownloadStatus()
        }
    }

    override fun cachedAudioUri(trackId: String): String? {
        if (!accountDataWriteGate.isActive()) return null
        if (!_status.value.isEnabled) return null
        val record = recordsByTrackId[trackId] ?: return null
        val file = File(cacheDir(), record.fileName)
        return file.takeIf { it.exists() && it.length() > 0L }?.let { Uri.fromFile(it).toString() }
    }

    private suspend fun enqueueImmediateSync() {
        val request = OneTimeWorkRequestBuilder<OfflineFavoritesSyncWorker>()
            .setConstraints(SyncConstraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(OneTimeWorkName, ExistingWorkPolicy.REPLACE, request)
            .await()
    }

    private suspend fun enqueuePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<OfflineFavoritesSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(SyncConstraints)
            .setInitialDelay(6, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                PeriodicWorkName,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            ).await()
    }

    /** Repairs preference/work registration after process death without starting network IO. */
    private suspend fun reconcileScheduledWork() {
        scheduledWorkMutex.withLock {
            // Re-read while holding the same lock as explicit scheduling. The
            // flow event may be stale by the time WorkManager becomes writable.
            val enabled = accountDataWriteGate.isActive() && dataStore.data.first()[enabledKey] == true
            if (enabled) {
                // Periodic work has a six-hour initial delay. Startup never
                // recreates immediate work merely by constructing this repo.
                enqueuePeriodicSync()
            } else {
                cancelScheduledSync()
            }
        }
    }

    private suspend fun cancelScheduledSync(
        workManager: WorkManager = WorkManager.getInstance(context),
    ) {
        workManager.cancelUniqueWork(OneTimeWorkName).await()
        workManager.cancelUniqueWork(PeriodicWorkName).await()
    }

    private suspend fun trimToQuota(quotaBytes: Long) {
        val records = trimRecordsToQuota(recordsByTrackId.values.toList(), quotaBytes)
        saveRecords(records, lastSyncedAt = _status.value.lastSyncedAtEpochSeconds)
    }

    private suspend fun saveRecords(records: List<OfflineTrackRecord>, lastSyncedAt: Long?) {
        dataStore.edit { prefs ->
            prefs[recordsKey] = json.encodeToString(ListSerializer(OfflineTrackRecord.serializer()), records)
            lastSyncedAt?.let { prefs[lastSyncedAtKey] = it }
        }
        publishRecordState(records, lastSyncedAt = lastSyncedAt)
    }

    private fun publishRecordState(records: List<OfflineTrackRecord>, lastSyncedAt: Long?) {
        recordsByTrackId = records.associateBy { it.trackId }
        _status.update { current ->
            current.copy(
                usedBytes = records.sumOf { it.byteSize },
                downloadedTrackCount = records.size,
                lastSyncedAtEpochSeconds = lastSyncedAt ?: current.lastSyncedAtEpochSeconds,
            )
        }
    }

    private fun requireCurrentAccount(generation: AccountDataWriteGate.Generation) {
        check(accountDataWriteGate.isCurrentAccount(generation)) {
            "Offline settings require the same active account"
        }
    }

    private fun trimRecordsToQuota(
        records: List<OfflineTrackRecord>,
        quotaBytes: Long,
        staleTrackIds: Set<String> = emptySet(),
    ): List<OfflineTrackRecord> {
        val plan = OfflineEvictionPlanner.plan(records, quotaBytes, staleTrackIds)
        return OfflineEvictionPlanCommitter.commit(plan) { record ->
            val file = File(cacheDir(), record.fileName)
            runCatching { file.delete() }
            // File.delete() can report success incorrectly on unusual storage;
            // the manifest may only forget bytes proven to be gone.
            !runCatching { file.exists() }.getOrDefault(true)
        }
    }

    private suspend fun downloadTrack(track: Track, maxBytes: Long): OfflineTrackDownloadResult =
        withContext(Dispatchers.IO) {
            if (maxBytes <= 0L) return@withContext OfflineTrackDownloadResult.SkippedForQuota
            val target = File(cacheDir(), "${track.id.safeFileName()}.audio")
            val temp = File(cacheDir(), "${target.name}.tmp")
            temp.delete()
            val request = Request.Builder().url(track.streamUrl()).build()
            try {
                when (val download = downloadTo(temp, request, maxBytes)) {
                    is OfflineBodyDownloadResult.Complete -> {
                        if (target.exists() && !target.delete()) {
                            temp.delete()
                            return@withContext OfflineTrackDownloadResult.Failed(
                                message = "Offline audio could not be replaced",
                                retryable = true,
                            )
                        }
                        if (!temp.renameTo(target)) {
                            temp.delete()
                            return@withContext OfflineTrackDownloadResult.Failed(
                                message = "Offline audio could not be stored",
                                retryable = true,
                            )
                        }
                        val now = nowSeconds()
                        OfflineTrackDownloadResult.Downloaded(
                            OfflineTrackRecord(
                                trackId = track.id,
                                fileName = target.name,
                                byteSize = download.bytesWritten,
                                downloadedAtEpochSeconds = now,
                            ),
                        )
                    }

                    is OfflineBodyDownloadResult.Failed -> download.toTrackFailure()
                    OfflineBodyDownloadResult.ExceedsQuota -> OfflineTrackDownloadResult.SkippedForQuota
                }
            } catch (cancellation: CancellationException) {
                temp.delete()
                throw cancellation
            } catch (error: Throwable) {
                temp.delete()
                // OkHttp reports Call.cancel() as IOException; restore the
                // coroutine's cancellation instead of publishing it as a sync failure.
                coroutineContext.ensureActive()
                Log.w(Tag, "Failed to download offline track ${track.id}", error)
                OfflineTrackDownloadResult.Failed(
                    message = error.offlineSyncFailureMessage(),
                    retryable = error.isRetryableOfflineSyncFailure(),
                )
            }
        }

    /** Streams [request]'s body into [temp] without conflating quota skips with failures. */
    private suspend fun downloadTo(
        temp: File,
        request: Request,
        maxBytes: Long,
    ): OfflineBodyDownloadResult {
        val call = downloadClient.newCall(request)
        coroutineContext.ensureActive()
        // execute() and ResponseBody reads are blocking. Canceling the Call is
        // what closes the socket and lets the IO thread observe Job cancellation.
        // A child Job completes as soon as its parent starts cancelling; a
        // regular parent completion handler would run too late for blocked IO.
        val cancellationHook = Job(parent = coroutineContext[Job])
        val cancellationHandle = cancellationHook.invokeOnCompletion {
            if (cancellationHook.isCancelled) call.cancel()
        }
        try {
            return call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@use OfflineBodyDownloadResult.Failed(
                        message = "Offline audio request failed (HTTP ${response.code})",
                        retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                    )
                }
                val body = response.body
                    ?: return@use OfflineBodyDownloadResult.Failed(
                        message = "Offline audio response was missing a body",
                        retryable = false,
                    )
                var bytesWritten = 0L
                temp.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            bytesWritten += read
                            if (bytesWritten > maxBytes) {
                                temp.delete()
                                return@use OfflineBodyDownloadResult.ExceedsQuota
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (bytesWritten <= 0L) {
                    temp.delete()
                    OfflineBodyDownloadResult.Failed(
                        message = "Offline audio response was empty",
                        retryable = false,
                    )
                } else {
                    OfflineBodyDownloadResult.Complete(bytesWritten)
                }
            }
        } finally {
            cancellationHandle.dispose()
            cancellationHook.complete()
        }
    }

    /** Deletes the cache as an all-or-retry manifest transition. */
    private suspend fun deleteAllCachedFiles() =
        withContext(Dispatchers.IO) {
            val files = cacheDir().listFiles()
                ?: throw IOException("Offline audio directory could not be read")
            var failedDeletionCount = 0
            files.forEach { file ->
                coroutineContext.ensureActive()
                runCatching { file.delete() }
                val stillExists = runCatching { file.exists() }.getOrDefault(true)
                if (stillExists) failedDeletionCount += 1
            }
            if (failedDeletionCount > 0) {
                throw IOException("Could not delete $failedDeletionCount offline audio item(s)")
            }
        }

    private fun cacheDir(): File =
        File(context.filesDir, "offline_audio").apply { mkdirs() }

    /**
     * Deletes leftover `.tmp` files and orphaned `.audio` files that no record
     * references. Orphans appear when the process dies between a completed
     * download and the next batched record save; without this sweep they
     * consume disk invisibly forever.
     */
    private fun cleanupStrayFiles(records: List<OfflineTrackRecord>) {
        val recordedNames = records.mapTo(mutableSetOf()) { it.fileName }
        cacheDir().listFiles()
            ?.filter { it.name.endsWith(".tmp") || it.name !in recordedNames }
            ?.forEach { it.delete() }
    }

    /**
     * Preferences are only a manifest, not proof that audio still exists.
     * Remove records whose file was deleted, truncated, tampered with, or
     * points outside the private offline directory so the next sync can
     * download the track again.
     */
    private fun reconcileRecords(records: List<OfflineTrackRecord>): List<OfflineTrackRecord> {
        val directory = cacheDir().canonicalFile
        val seenTrackIds = mutableSetOf<String>()
        return records.filter { record ->
            val file = try {
                File(directory, record.fileName).canonicalFile
            } catch (exception: Exception) {
                Log.w(Tag, "Ignoring malformed offline record for ${record.trackId}", exception)
                return@filter false
            }
            val isSafePath = file.parentFile == directory
            val hasValidFile = isSafePath &&
                record.trackId.isNotBlank() &&
                record.byteSize > 0L &&
                file.isFile &&
                file.length() == record.byteSize
            val isUnique = hasValidFile && seenTrackIds.add(record.trackId)
            if (!hasValidFile && isSafePath) {
                file.delete()
            }
            hasValidFile && isUnique
        }
    }

    private fun decodeRecords(raw: String?): List<OfflineTrackRecord> =
        raw?.let {
            runCatching {
                json.decodeFromString(ListSerializer(OfflineTrackRecord.serializer()), it)
            }.getOrNull()
        }.orEmpty()

    /** Stops repository-owned observers; production keeps the singleton alive for the process. */
    internal suspend fun shutdown() {
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
}

private sealed interface OfflineBodyDownloadResult {
    data class Complete(val bytesWritten: Long) : OfflineBodyDownloadResult

    data class Failed(
        val message: String,
        val retryable: Boolean,
    ) : OfflineBodyDownloadResult

    data object ExceedsQuota : OfflineBodyDownloadResult
}

private sealed interface OfflineTrackDownloadResult {
    data class Downloaded(val record: OfflineTrackRecord) : OfflineTrackDownloadResult

    data class Failed(
        val message: String,
        val retryable: Boolean,
    ) : OfflineTrackDownloadResult

    data object SkippedForQuota : OfflineTrackDownloadResult
}

private fun OfflineBodyDownloadResult.Failed.toTrackFailure() =
    OfflineTrackDownloadResult.Failed(
        message = message,
        retryable = retryable,
    )

private fun List<OfflineTrackDownloadResult.Failed>.toAttemptFailure(): OfflineSyncAttemptResult.Failure {
    val firstFailure = first()
    val message = if (size == 1) {
        firstFailure.message
    } else {
        "$size offline audio downloads failed; ${firstFailure.message}"
    }
    return OfflineSyncAttemptResult.Failure(
        message = message,
        retryable = any(OfflineTrackDownloadResult.Failed::retryable),
    )
}

internal class OfflineDownloadPlanner(
    private val quotaBytes: Long,
    usedBytes: Long,
) {
    var usedBytes: Long = usedBytes
        private set

    fun shouldDownloadNext(): Boolean = usedBytes < quotaBytes

    fun recordDownloaded(byteSize: Long): Boolean {
        if (byteSize <= 0L || usedBytes + byteSize > quotaBytes) return false
        usedBytes += byteSize
        return true
    }
}

internal class OfflineSyncAccumulator(
    quotaBytes: Long,
    existingRecords: List<OfflineTrackRecord>,
) {
    private val recordsByTrackId = existingRecords.associateBy { it.trackId }.toMutableMap()
    private val planner = OfflineDownloadPlanner(
        quotaBytes = quotaBytes,
        usedBytes = existingRecords.sumOf { it.byteSize },
    )

    val usedBytes: Long
        get() = planner.usedBytes

    val records: List<OfflineTrackRecord>
        get() = recordsByTrackId.values.toList()

    fun contains(trackId: String): Boolean = recordsByTrackId.containsKey(trackId)

    fun shouldDownloadNext(): Boolean = planner.shouldDownloadNext()

    fun recordDownloaded(record: OfflineTrackRecord): List<OfflineTrackRecord>? {
        if (!planner.recordDownloaded(record.byteSize)) return null
        recordsByTrackId[record.trackId] = record
        return records
    }
}

internal class OfflineRecordPersistBatcher(
    private val batchSize: Int,
) {
    private var pendingDownloads = 0

    val hasPendingWrites: Boolean
        get() = pendingDownloads > 0

    /** Returns true when the batch is full and the caller should persist now. */
    fun recordDownloaded(): Boolean {
        pendingDownloads += 1
        return pendingDownloads >= batchSize.coerceAtLeast(1)
    }

    /**
     * Call after a successful persist. Kept separate from [recordDownloaded]
     * so a failed save leaves [hasPendingWrites] true and the final flush
     * still covers those records.
     */
    fun markPersisted() {
        pendingDownloads = 0
    }
}

@Serializable
internal data class OfflineTrackRecord(
    val trackId: String,
    val fileName: String,
    val byteSize: Long,
    val downloadedAtEpochSeconds: Long,
)

internal object OfflineEvictionPlanner {
    data class Plan(
        val kept: List<OfflineTrackRecord>,
        val evicted: List<OfflineTrackRecord>,
    )

    fun plan(
        records: List<OfflineTrackRecord>,
        quotaBytes: Long,
        staleTrackIds: Set<String> = emptySet(),
    ): Plan {
        var usedBytes = records.sumOf { it.byteSize }
        // Stale (no-longer-favorited) records sort first; tie-break by oldest download time.
        val keep = records.sortedWith(
            compareBy<OfflineTrackRecord> { it.trackId !in staleTrackIds }
                .thenBy { it.downloadedAtEpochSeconds },
        ).toMutableList()
        val evicted = mutableListOf<OfflineTrackRecord>()

        // Eagerly drop stale records even if quota would otherwise be respected.
        val iterator = keep.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.trackId !in staleTrackIds) break
            iterator.remove()
            evicted += candidate
            usedBytes -= candidate.byteSize
        }
        while (usedBytes > quotaBytes && keep.isNotEmpty()) {
            val removed = keep.removeAt(0)
            evicted += removed
            usedBytes -= removed.byteSize
        }
        return Plan(kept = keep, evicted = evicted)
    }
}

internal object OfflineEvictionPlanCommitter {
    fun commit(
        plan: OfflineEvictionPlanner.Plan,
        delete: (OfflineTrackRecord) -> Boolean,
    ): List<OfflineTrackRecord> {
        val undeleted = plan.evicted.filterNot(delete)
        return plan.kept + undeleted
    }
}

private fun String.safeFileName(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L
