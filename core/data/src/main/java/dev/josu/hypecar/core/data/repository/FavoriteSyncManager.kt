package dev.josu.hypecar.core.data.repository

import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralizes the optimistic-favorite logic that used to be copy-pasted into
 * `LatestRoute`, `PopularRoute`, and `LibraryRoute`. Each route now subscribes
 * to [edits] and folds incoming [FavoriteEdit]s onto its local track list via
 * [applyTo]. Toggling routes through [toggle], which:
 *
 *  1. Emits the *optimistic* edit immediately so all subscribed lists update.
 *  2. Fires the server request via [MeRepository.toggleFavorite].
 *  3. If the request fails or the server contradicts the optimistic value,
 *     emits a *revert* edit that undoes the optimistic delta and applies the
 *     confirmed/original value.
 *
 * Stays @Singleton so a single in-process [SharedFlow] is the source of truth
 * for the heart icon across every screen — when a user loves a track in
 * Latest, the same icon flips in Library, Feed, the player queue, and the
 * Android Auto session without each surface needing to know about the others.
 * Every emission is also folded into [PlaybackRepository] so the now-playing
 * queue (player screen, mini player, car Now Playing) stays in step with the
 * lists.
 */
@Singleton
class FavoriteSyncManager(
    private val meRepository: MeRepository,
    private val playbackRepository: PlaybackRepository,
    private val scope: CoroutineScope,
    private val accountDataWriteGate: AccountDataWriteGate = AccountDataWriteGate(),
    private val favoriteStateCoordinator: FavoriteStateCoordinator = FavoriteStateCoordinator(accountDataWriteGate),
) {
    @Inject
    constructor(
        meRepository: MeRepository,
        playbackRepository: PlaybackRepository,
        accountDataWriteGate: AccountDataWriteGate,
        favoriteStateCoordinator: FavoriteStateCoordinator,
    ) : this(
        meRepository = meRepository,
        playbackRepository = playbackRepository,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        accountDataWriteGate = accountDataWriteGate,
        favoriteStateCoordinator = favoriteStateCoordinator,
    )

    /** Convenience constructor for screen/unit-test fakes outside this module. */
    constructor(meRepository: MeRepository, playbackRepository: PlaybackRepository) : this(
        meRepository = meRepository,
        playbackRepository = playbackRepository,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /** Convenience constructor for callers that need to drive account boundaries. */
    constructor(
        meRepository: MeRepository,
        playbackRepository: PlaybackRepository,
        accountDataWriteGate: AccountDataWriteGate,
    ) : this(
        meRepository = meRepository,
        playbackRepository = playbackRepository,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        accountDataWriteGate = accountDataWriteGate,
    )

    /** Deterministic-scope constructor for fakes outside the data module. */
    constructor(
        meRepository: MeRepository,
        playbackRepository: PlaybackRepository,
        scope: CoroutineScope,
    ) : this(
        meRepository = meRepository,
        playbackRepository = playbackRepository,
        scope = scope,
        accountDataWriteGate = AccountDataWriteGate(),
    )

    private val _edits = MutableSharedFlow<FavoriteEdit>(
        // Buffer optimistic + revert pairs without dropping them if a slow
        // consumer is mid-fold.
        extraBufferCapacity = 64,
    )

    /** One-shot edits that consumers fold into their local lists. */
    val edits: SharedFlow<FavoriteEdit> = _edits.asSharedFlow()

    /** Credential-free account lifecycle used to invalidate retained UI state. */
    val accountBoundary: StateFlow<AccountBoundary> = accountDataWriteGate.accountBoundary

    private val requestSequence = AtomicLong(0L)
    private val stateLock = Any()
    private val pendingStates = mutableMapOf<String, PendingFavoriteState>()

    /**
     * Toggles the loved state of [track] with optimistic UI.
     *
     * The track passed in must carry its current `isLoved` and `lovedCount`
     * so the optimistic delta is computed correctly. Returns the optimistic
     * value. When another tap for the same track is already pending, that
     * pending intent is toggled instead of the stale [Track.isLoved] snapshot.
     * A reconciliation edit is emitted later if the server disagrees.
     */
    fun toggle(track: Track): Boolean = toggleWithResult(track).optimisticLoved

    /**
     * Starts the same optimistic synchronization as [toggle] and also returns
     * an awaitable result for surfaces that need to report a failed request.
     * Awaiting is optional and does not own or cancel the singleton sync work.
     */
    fun toggleWithResult(track: Track): FavoriteToggleRequest {
        val accountOperation = captureAccountOperation()
        return toggleWithResult(
            trackId = track.id,
            currentLoved = track.isLoved,
            accountOperation = accountOperation,
        )
    }

    /**
     * Joins the same per-account/per-track intent pipeline with an operation
     * captured by another surface (for example Android Auto). This prevents
     * two non-idempotent toggle requests from racing across surfaces.
     */
    fun toggleWithResult(
        trackId: String,
        currentLoved: Boolean,
        accountOperation: AccountOperation,
    ): FavoriteToggleRequest {
        val accountGeneration = accountOperation.accountGeneration
        val sequence = requestSequence.incrementAndGet()
        val completion = CompletableDeferred<FavoriteToggleOutcome>()
        val optimistic: Boolean
        synchronized(stateLock) {
            val pending = pendingStates[trackId]
                ?.takeIf { it.accountGeneration == accountGeneration }
            optimistic = !(pending?.desiredLoved ?: currentLoved)
            favoriteStateCoordinator.record(
                trackId = trackId,
                isLoved = optimistic,
                isPending = true,
                generation = accountGeneration,
            )
            pendingStates[trackId] = PendingFavoriteState(
                accountGeneration = accountGeneration,
                confirmedLoved = pending?.confirmedLoved ?: currentLoved,
                desiredLoved = optimistic,
                latestSequence = sequence,
            )
            val optimisticPublication = enqueuePublicationLocked(
                accountGeneration,
                FavoriteEdit(trackId, optimistic, if (optimistic) 1 else -1),
            )
            processorLocked(AccountTrackKey(accountGeneration, trackId)).trySend(
                FavoriteToggleWork(
                    trackId = trackId,
                    accountGeneration = accountGeneration,
                    sequence = sequence,
                    optimisticLoved = optimistic,
                    optimisticPublication = optimisticPublication,
                    accountOperation = accountOperation,
                    completion = completion,
                ),
            ).onFailure {
                clearPendingStateLocked(trackId, accountGeneration, sequence)
                completion.complete(FavoriteToggleOutcome.ACCOUNT_CHANGED)
            }
        }
        return FavoriteToggleRequest(optimistic, completion)
    }

    private val publicationCommands = Channel<FavoritePublication>(Channel.UNLIMITED)
    private val trackProcessors = mutableMapOf<AccountTrackKey, Channel<FavoriteToggleWork>>()
    internal val activeTrackProcessorCount: Int
        get() = synchronized(stateLock) { trackProcessors.size }

    init {
        scope.launch {
            for (publication in publicationCommands) {
                val accepted = runSuspendCatchingPreservingCancellation {
                    publishIfCurrent(
                        publication.accountGeneration,
                        publication.edit,
                    )
                }.getOrDefault(false)
                publication.completion.complete(accepted)
            }
        }
    }

    private fun processorLocked(key: AccountTrackKey): Channel<FavoriteToggleWork> =
        trackProcessors.getOrPut(key) {
            Channel<FavoriteToggleWork>(Channel.UNLIMITED).also { requests ->
                scope.launch {
                    try {
                        var request = requests.receive()
                        while (true) {
                            processToggle(request)
                            request = synchronized(stateLock) {
                                requests.tryReceive().getOrNull()
                                    ?: run {
                                        trackProcessors.remove(key, requests)
                                        requests.close()
                                        return@launch
                                    }
                            }
                        }
                    } finally {
                        synchronized(stateLock) {
                            trackProcessors.remove(key, requests)
                            requests.close()
                        }
                    }
                }
            }
        }

    private suspend fun processToggle(work: FavoriteToggleWork) {
        if (!work.optimisticPublication.await()) {
            synchronized(stateLock) {
                clearPendingStateLocked(work.trackId, work.accountGeneration, work.sequence)
            }
            work.completion.complete(FavoriteToggleOutcome.ACCOUNT_CHANGED)
            return
        }

        val noRequestOutcome = synchronized(stateLock) {
            val pending = pendingStateForLocked(work)
            when {
                pending == null || pending.latestSequence != work.sequence ->
                    FavoriteToggleOutcome.SUPERSEDED
                pending.desiredLoved == pending.confirmedLoved -> {
                    favoriteStateCoordinator.record(
                        trackId = work.trackId,
                        isLoved = pending.confirmedLoved,
                        isPending = false,
                        generation = work.accountGeneration,
                    )
                    clearPendingStateLocked(work.trackId, work.accountGeneration, work.sequence)
                    FavoriteToggleOutcome.CONFIRMED
                }
                else -> null
            }
        }
        if (noRequestOutcome != null) {
            work.completion.complete(noRequestOutcome)
            return
        }

        if (!isAccountCurrent(work.accountGeneration)) {
            synchronized(stateLock) {
                clearPendingStateLocked(work.trackId, work.accountGeneration, work.sequence)
            }
            work.completion.complete(FavoriteToggleOutcome.ACCOUNT_CHANGED)
            return
        }

        var mutation = toggleFavoriteOnServer(work.trackId, work.accountOperation)
        if (!mutation.isAccountCurrent) {
            synchronized(stateLock) {
                clearPendingStateLocked(work.trackId, work.accountGeneration, work.sequence)
            }
            work.completion.complete(FavoriteToggleOutcome.ACCOUNT_CHANGED)
            return
        }
        var confirmed = mutation.confirmedLoved
        rememberConfirmedState(work, confirmed)
        if (!isLatest(work)) {
            work.completion.complete(FavoriteToggleOutcome.SUPERSEDED)
            return
        }
        if (!isAccountCurrent(work.accountGeneration)) {
            synchronized(stateLock) {
                clearPendingStateLocked(work.trackId, work.accountGeneration, work.sequence)
            }
            work.completion.complete(FavoriteToggleOutcome.ACCOUNT_CHANGED)
            return
        }

        // The historical API occasionally returned the pre-toggle value. Do
        // not blindly issue another non-idempotent toggle: first confirm with
        // an idempotent GET, and retry only when that authoritative read still
        // shows the opposite state.
        if (confirmed != work.optimisticLoved) {
            val confirmation = confirmFavoriteOnServer(work.trackId, work.accountOperation)
            if (!confirmation.isAccountCurrent) {
                synchronized(stateLock) {
                    clearPendingStateLocked(work.trackId, work.accountGeneration, work.sequence)
                }
                work.completion.complete(FavoriteToggleOutcome.ACCOUNT_CHANGED)
                return
            }
            when (confirmation.confirmedLoved) {
                work.optimisticLoved -> {
                    confirmed = work.optimisticLoved
                    rememberConfirmedState(work, confirmed)
                }
                null -> Unit
                else -> {
                    mutation = toggleFavoriteOnServer(work.trackId, work.accountOperation)
                    if (!mutation.isAccountCurrent) {
                        synchronized(stateLock) {
                            clearPendingStateLocked(work.trackId, work.accountGeneration, work.sequence)
                        }
                        work.completion.complete(FavoriteToggleOutcome.ACCOUNT_CHANGED)
                        return
                    }
                    confirmed = mutation.confirmedLoved
                    rememberConfirmedState(work, confirmed)
                }
            }
        }

        val publication: Deferred<Boolean>?
        val outcome: FavoriteToggleOutcome
        synchronized(stateLock) {
            if (!isLatestLocked(work)) {
                publication = null
                outcome = FavoriteToggleOutcome.SUPERSEDED
            } else {
                val knownConfirmed = pendingStateForLocked(work)?.confirmedLoved
                    ?: !work.optimisticLoved
                clearPendingStateLocked(work.trackId, work.accountGeneration, work.sequence)
                if (confirmed == work.optimisticLoved) {
                    favoriteStateCoordinator.record(
                        trackId = work.trackId,
                        isLoved = work.optimisticLoved,
                        isPending = false,
                        generation = work.accountGeneration,
                    )
                    publication = null
                    outcome = FavoriteToggleOutcome.CONFIRMED
                } else {
                    val optimisticDelta = if (work.optimisticLoved) 1 else -1
                    val reconciledLoved = confirmed ?: knownConfirmed
                    favoriteStateCoordinator.record(
                        trackId = work.trackId,
                        isLoved = reconciledLoved,
                        isPending = false,
                        generation = work.accountGeneration,
                    )
                    publication = enqueuePublicationLocked(
                        work.accountGeneration,
                        FavoriteEdit(
                            trackId = work.trackId,
                            isLoved = reconciledLoved,
                            lovedCountDelta = -optimisticDelta,
                        ),
                    )
                    outcome = if (confirmed == null) {
                        FavoriteToggleOutcome.FAILED
                    } else {
                        FavoriteToggleOutcome.RECONCILED
                    }
                }
            }
        }

        if (publication != null && !publication.await()) {
            work.completion.complete(FavoriteToggleOutcome.ACCOUNT_CHANGED)
        } else {
            work.completion.complete(outcome)
        }
    }

    /**
     * Executes one server toggle using the credential captured with
     * [accountOperation]. A stale operation never falls through to the newly
     * active account's credential.
     */
    internal suspend fun toggleFavoriteOnServer(
        trackId: String,
        accountOperation: AccountOperation,
    ): AccountScopedFavoriteResult {
        if (!isAccountCurrent(accountOperation.accountGeneration)) {
            return AccountScopedFavoriteResult(confirmedLoved = null, isAccountCurrent = false)
        }
        val confirmed = runSuspendCatchingPreservingCancellation {
            val scopedRepository = meRepository as? AccountScopedFavoriteRepository
            if (scopedRepository != null) {
                accountOperation.authToken?.let { token ->
                    scopedRepository.toggleFavoriteForAccount(
                        trackId = trackId,
                        authToken = token,
                        accountGeneration = accountOperation.accountGeneration,
                    )
                }
            } else {
                meRepository.toggleFavorite(trackId)
            }
        }.getOrNull()
        val isCurrent = isAccountCurrent(accountOperation.accountGeneration)
        return AccountScopedFavoriteResult(
            confirmedLoved = confirmed,
            isAccountCurrent = isCurrent,
        )
    }

    private suspend fun confirmFavoriteOnServer(
        trackId: String,
        accountOperation: AccountOperation,
    ): AccountScopedFavoriteResult {
        if (!isAccountCurrent(accountOperation.accountGeneration)) {
            return AccountScopedFavoriteResult(confirmedLoved = null, isAccountCurrent = false)
        }
        val confirmed = runSuspendCatchingPreservingCancellation {
            val repository = meRepository as? AccountScopedFavoriteRepository
            val token = accountOperation.authToken
            if (repository != null && token != null) {
                repository.favoriteStateForAccount(
                    trackId = trackId,
                    authToken = token,
                    accountGeneration = accountOperation.accountGeneration,
                )
            } else {
                null
            }
        }.getOrNull()
        return AccountScopedFavoriteResult(
            confirmedLoved = confirmed,
            isAccountCurrent = isAccountCurrent(accountOperation.accountGeneration),
        )
    }

    private fun isLatest(work: FavoriteToggleWork): Boolean = synchronized(stateLock) {
        isLatestLocked(work)
    }

    private fun isLatestLocked(work: FavoriteToggleWork): Boolean =
        pendingStateForLocked(work)?.latestSequence == work.sequence

    private fun pendingStateForLocked(work: FavoriteToggleWork): PendingFavoriteState? =
        pendingStates[work.trackId]
            ?.takeIf { it.accountGeneration == work.accountGeneration }

    private fun rememberConfirmedState(work: FavoriteToggleWork, confirmed: Boolean?) {
        if (confirmed == null) return
        synchronized(stateLock) {
            pendingStateForLocked(work)?.confirmedLoved = confirmed
        }
    }

    private suspend fun isAccountCurrent(accountGeneration: AccountDataWriteGate.Generation): Boolean =
        accountDataWriteGate.writeIfCurrent(accountGeneration) {}

    /**
     * Broadcasts an [edit] produced by a surface that manages its own server
     * sync so lists and the playback queue stay consistent with it. Prefer the
     * account-scoped overload for operations that suspend between intent and
     * publication. The playback update is a no-op when the track isn't in the
     * current queue.
     */
    suspend fun publish(edit: FavoriteEdit) {
        publish(edit, captureAccountOperation())
    }

    /** Captures the account boundary for a multi-step favorite operation. */
    fun captureAccountOperation(): AccountOperation {
        val accountAccess = accountDataWriteGate.captureAccountAccess()
        return AccountOperation(
            accountGeneration = accountAccess.generation,
            authToken = accountAccess.authToken?.takeUnless(String::isBlank),
        )
    }

    /**
     * Publishes only if [accountOperation] still belongs to the active account.
     * Returns false after logout or an account switch without emitting an edit.
     */
    suspend fun publish(
        edit: FavoriteEdit,
        accountOperation: AccountOperation,
    ): Boolean {
        val publication = synchronized(stateLock) {
            favoriteStateCoordinator.record(
                trackId = edit.trackId,
                isLoved = edit.isLoved,
                isPending = false,
                generation = accountOperation.accountGeneration,
            )
            enqueuePublicationLocked(accountOperation.accountGeneration, edit)
        }
        return publication.await()
    }

    class AccountOperation internal constructor(
        internal val accountGeneration: AccountDataWriteGate.Generation,
        internal val authToken: String?,
    )

    private suspend fun publishIfCurrent(
        accountGeneration: AccountDataWriteGate.Generation,
        edit: FavoriteEdit,
    ): Boolean = accountDataWriteGate.writeIfCurrent(accountGeneration) {
        _edits.emit(edit)
        playbackRepository.updateFavorite(edit.trackId, edit.isLoved)
    }

    private fun enqueuePublicationLocked(
        accountGeneration: AccountDataWriteGate.Generation,
        edit: FavoriteEdit,
    ): Deferred<Boolean> = CompletableDeferred<Boolean>().also { completion ->
        publicationCommands.trySend(
            FavoritePublication(accountGeneration, edit, completion),
        ).onFailure {
            completion.complete(false)
        }
    }

    private fun clearPendingStateLocked(
        trackId: String,
        accountGeneration: AccountDataWriteGate.Generation,
        sequence: Long,
    ) {
        val pending = pendingStates[trackId]
        if (
            pending?.accountGeneration == accountGeneration &&
            pending.latestSequence == sequence
        ) {
            pendingStates.remove(trackId)
        }
    }

    /**
     * Folds [edit] onto [tracks], returning a new list. The matching track's
     * `isLoved` is replaced with the edit's value and its `lovedCount` is
     * shifted by the edit's delta (clamped at 0).
     */
    fun applyTo(tracks: List<Track>, edit: FavoriteEdit): List<Track> =
        tracks.map { track ->
            if (track.id == edit.trackId) {
                track.copy(
                    isLoved = edit.isLoved,
                    lovedCount = (track.lovedCount + edit.lovedCountDelta).coerceAtLeast(0),
                )
            } else {
                track
            }
        }

    /**
     * Reapplies the latest accepted favorite truth to a newly fetched list.
     * This closes the race where an older GET response arrives after an
     * optimistic edit and would otherwise overwrite the newer heart state.
     */
    fun applyCurrentState(tracks: List<Track>): List<Track> =
        favoriteStateCoordinator.applyToCached(tracks)

    /** Captures the mutation watermark immediately before a track-list read. */
    fun captureFavoriteRead(): FavoriteRead = FavoriteRead(favoriteStateCoordinator.captureRead())

    /** Protects rows from edits that occurred while their read was in flight. */
    fun applyToFetched(tracks: List<Track>, read: FavoriteRead): List<Track> =
        favoriteStateCoordinator.applyToFetchedForUi(tracks, read.token)

    class FavoriteRead internal constructor(
        internal val token: FavoriteReadToken,
    )
}

private data class PendingFavoriteState(
    val accountGeneration: AccountDataWriteGate.Generation,
    var confirmedLoved: Boolean,
    val desiredLoved: Boolean,
    val latestSequence: Long,
)

private data class AccountTrackKey(
    val accountGeneration: AccountDataWriteGate.Generation,
    val trackId: String,
)

private data class FavoritePublication(
    val accountGeneration: AccountDataWriteGate.Generation,
    val edit: FavoriteEdit,
    val completion: CompletableDeferred<Boolean>,
)

private data class FavoriteToggleWork(
    val trackId: String,
    val accountGeneration: AccountDataWriteGate.Generation,
    val sequence: Long,
    val optimisticLoved: Boolean,
    val optimisticPublication: Deferred<Boolean>,
    val accountOperation: FavoriteSyncManager.AccountOperation,
    val completion: CompletableDeferred<FavoriteToggleOutcome>,
)

/** Account-scoped result for callers with their own reconciliation policy. */
data class AccountScopedFavoriteResult(
    val confirmedLoved: Boolean?,
    val isAccountCurrent: Boolean,
)

internal interface AccountScopedFavoriteRepository {
    suspend fun toggleFavoriteForAccount(
        trackId: String,
        authToken: String,
        accountGeneration: AccountDataWriteGate.Generation,
    ): Boolean?

    suspend fun favoriteStateForAccount(
        trackId: String,
        authToken: String,
        accountGeneration: AccountDataWriteGate.Generation,
    ): Boolean? = null
}

/** Result of a favorite request started through [FavoriteSyncManager]. */
enum class FavoriteToggleOutcome {
    CONFIRMED,
    RECONCILED,
    FAILED,
    SUPERSEDED,
    ACCOUNT_CHANGED,
}

/** An optional completion handle for an optimistic favorite request. */
class FavoriteToggleRequest internal constructor(
    val optimisticLoved: Boolean,
    private val completion: Deferred<FavoriteToggleOutcome>,
) {
    suspend fun awaitOutcome(): FavoriteToggleOutcome = completion.await()
}

/**
 * A single optimistic-or-confirmed favorite change emitted by
 * [FavoriteSyncManager]. [lovedCountDelta] is the *incremental* shift to
 * apply (e.g. +1 on optimistic love, -1 on revert).
 */
data class FavoriteEdit(
    val trackId: String,
    val isLoved: Boolean,
    val lovedCountDelta: Int,
)
