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
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.OfflineDownloadStatus
import dev.josu.hypecar.core.model.repository.OfflineRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class DefaultOfflineRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val meRepository: MeRepository,
    private val client: OkHttpClient,
    private val json: Json,
) : OfflineRepository {
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
    private val syncMutex = Mutex()

    @Volatile
    private var activeSyncJob: Job? = null

    override val status: StateFlow<OfflineDownloadStatus> = _status

    init {
        scope.launch {
            dataStore.data
                .catch { emit(emptyPreferences()) }
                .collect { prefs ->
                    val records = decodeRecords(prefs[recordsKey])
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
    }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[enabledKey] = enabled
            if (prefs[quotaBytesKey] == null) {
                prefs[quotaBytesKey] = DefaultQuotaBytes
            }
        }
        if (enabled) {
            enqueuePeriodicSync()
            enqueueImmediateSync()
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(OneTimeWorkName)
            WorkManager.getInstance(context).cancelUniqueWork(PeriodicWorkName)
            activeSyncJob?.cancel()
        }
    }

    override suspend fun setQuotaBytes(quotaBytes: Long) {
        val sanitized = quotaBytes.coerceAtLeast(50L * 1024L * 1024L)
        dataStore.edit { prefs -> prefs[quotaBytesKey] = sanitized }
        // Serialize with any running sync: a concurrent sync's final save would
        // otherwise resurrect records for the files trim just deleted.
        activeSyncJob?.cancelAndJoin()
        syncMutex.withLock { trimToQuota(sanitized) }
        if (_status.value.isEnabled) {
            enqueueImmediateSync()
        }
    }

    override suspend fun syncFavorites() {
        if (!syncMutex.tryLock()) return
        try {
            coroutineScope {
                val job = launch { syncFavoritesLocked() }
                activeSyncJob = job
                job.join()
            }
        } finally {
            activeSyncJob = null
            syncMutex.unlock()
        }
    }

    private suspend fun syncFavoritesLocked() {
        val prefs = dataStore.data.first()
        if (prefs[enabledKey] != true) return
        val quotaBytes = prefs[quotaBytesKey] ?: DefaultQuotaBytes

        _status.update { it.copy(isSyncing = true, error = null) }
        try {
            val result = runSuspendCatchingPreservingCancellation {
                val existingRecords = decodeRecords(prefs[recordsKey])
                cleanupStrayFiles(existingRecords)
                val accumulator = OfflineSyncAccumulator(
                    quotaBytes = quotaBytes,
                    existingRecords = existingRecords,
                )
                val recordPersistBatcher = OfflineRecordPersistBatcher(batchSize = RecordPersistBatchSize)
                val seenFavorites = mutableSetOf<String>()
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
                            val record = downloadTrack(track, remainingBytes) ?: continue
                            val records = accumulator.recordDownloaded(record)
                            if (records != null) {
                                if (recordPersistBatcher.recordDownloaded()) {
                                    saveRecords(records, lastSyncedAt = null)
                                    recordPersistBatcher.markPersisted()
                                }
                            } else {
                                File(cacheDir(), record.fileName).delete()
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
                saveRecords(trimmed, lastSyncedAt = nowSeconds())
            }

            _status.update { it.copy(isSyncing = false, error = result.exceptionOrNull()?.message) }
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

    private suspend fun isStillEnabled(): Boolean =
        runCatching { dataStore.data.first()[enabledKey] == true }.getOrDefault(false)

    override suspend fun clearDownloads() {
        activeSyncJob?.cancelAndJoin()
        syncMutex.withLock {
            withContext(Dispatchers.IO) {
                cacheDir().listFiles()?.forEach { it.delete() }
            }
            saveRecords(emptyList(), lastSyncedAt = _status.value.lastSyncedAtEpochSeconds)
        }
    }

    override fun cachedAudioUri(trackId: String): String? {
        if (!_status.value.isEnabled) return null
        val record = recordsByTrackId[trackId] ?: return null
        val file = File(cacheDir(), record.fileName)
        return file.takeIf { it.exists() && it.length() > 0L }?.let { Uri.fromFile(it).toString() }
    }

    private fun enqueueImmediateSync() {
        val request = OneTimeWorkRequestBuilder<OfflineFavoritesSyncWorker>()
            .setConstraints(SyncConstraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(OneTimeWorkName, ExistingWorkPolicy.REPLACE, request)
    }

    private fun enqueuePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<OfflineFavoritesSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(SyncConstraints)
            .setInitialDelay(6, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PeriodicWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
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

    private fun trimRecordsToQuota(
        records: List<OfflineTrackRecord>,
        quotaBytes: Long,
        staleTrackIds: Set<String> = emptySet(),
    ): List<OfflineTrackRecord> {
        val plan = OfflineEvictionPlanner.plan(records, quotaBytes, staleTrackIds)
        plan.evicted.forEach { File(cacheDir(), it.fileName).delete() }
        return plan.kept
    }

    private suspend fun downloadTrack(track: Track, maxBytes: Long): OfflineTrackRecord? =
        withContext(Dispatchers.IO) {
            if (maxBytes <= 0L) return@withContext null
            val target = File(cacheDir(), "${track.id.safeFileName()}.audio")
            val temp = File(cacheDir(), "${target.name}.tmp")
            temp.delete()
            val request = Request.Builder().url(track.streamUrl()).build()
            runSuspendCatchingPreservingCancellation {
                try {
                    downloadTo(temp, request, maxBytes)
                } catch (cancellation: CancellationException) {
                    temp.delete()
                    throw cancellation
                }?.let { bytesWritten ->
                    if (target.exists()) target.delete()
                    if (!temp.renameTo(target)) {
                        temp.delete()
                        return@runSuspendCatchingPreservingCancellation null
                    }
                    val now = nowSeconds()
                    OfflineTrackRecord(
                        trackId = track.id,
                        fileName = target.name,
                        byteSize = bytesWritten,
                        downloadedAtEpochSeconds = now,
                    )
                }
            }.onFailure {
                temp.delete()
                Log.w(Tag, "Failed to download offline track ${track.id}", it)
            }.getOrNull()
        }

    /** Streams [request]'s body into [temp]; returns bytes written, or null when skipped/aborted. */
    private suspend fun downloadTo(temp: File, request: Request, maxBytes: Long): Long? =
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            var bytesWritten = 0L
            temp.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        // Without this check, cancelling the sync (toggle off,
                        // quota change) would silently wait out the transfer.
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        bytesWritten += read
                        if (bytesWritten > maxBytes) {
                            temp.delete()
                            return@use null
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (bytesWritten <= 0L) {
                temp.delete()
                null
            } else {
                bytesWritten
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

    private fun decodeRecords(raw: String?): List<OfflineTrackRecord> =
        raw?.let {
            runCatching {
                json.decodeFromString(ListSerializer(OfflineTrackRecord.serializer()), it)
            }.getOrNull()
        }.orEmpty()
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

private fun String.safeFileName(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L
