package dev.josu.hypecar.core.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.model.SessionEvent
import dev.josu.hypecar.core.model.SessionEventBus
import dev.josu.hypecar.core.network.AuthTokenProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HypeSessionStore internal constructor(
    context: Context,
    private val tokenCodec: SessionTokenCodec = SessionTokenCodec(AndroidKeystoreSessionTokenCipher()),
    private val accountDataWiper: AccountLocalDataWiper = AccountLocalDataWiper { },
    private val accountDataWriteGate: AccountDataWriteGate = AccountDataWriteGate(initiallyActive = false),
    private val onSessionExpired: () -> Unit = { SessionEventBus.emit(SessionEvent.Expired) },
    private val beforePersistenceOperation: suspend (SessionPersistenceOperation) -> Unit = { },
) : AuthTokenProvider,
    SessionGateway {
    private companion object {
        const val Tag = "HypeSessionStore"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionFile = context.preferencesDataStoreFile("session.preferences_pb")
    private val dataStore = PreferenceDataStoreFactory.create(
        // A corrupt session file must degrade to "signed out" instead of
        // poisoning every read/edit until the user clears app data.
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { sessionFile },
    )

    private val usernameKey = stringPreferencesKey("username")
    private val tokenKey = stringPreferencesKey("token")
    private val cleanupPendingKey = booleanPreferencesKey("account_cleanup_pending")
    private val accountDataCleanKey = booleanPreferencesKey("account_data_clean")

    private val _session = MutableStateFlow<AuthSession?>(null)
    override val session: StateFlow<AuthSession?> = _session
    private val initialization = CompletableDeferred<Unit>()
    private val lifecycleMutex = Mutex()

    // This is true only after accountDataCleanKey has been observed or written.
    // Missing/corrupt preferences are conservatively dirty until a full wipe
    // durably records the clean marker.
    private var accountDataIsClean = false

    init {
        scope.launch {
            try {
                val loaded = sessionValues().first()
                if (loaded.cleanupPending) {
                    accountDataWriteGate.deactivate()
                    _session.value = null
                    accountDataIsClean = false
                } else if (loaded.session == null && loaded.hadStoredCredentials) {
                    // Partial, blank, or undecryptable credentials cannot be
                    // used safely. Drop them now; account data is wiped by the
                    // first post-construction session/API access below. Doing
                    // that work here could resolve a lazy repository while the
                    // dependency graph that owns this store is still building.
                    markAccountCleanupPending()
                } else {
                    migrateTokenIfNeeded(loaded)
                    if (loaded.session == null) {
                        accountDataWriteGate.deactivate()
                    } else {
                        accountDataWriteGate.activate(loaded.session.token)
                    }
                    _session.value = loaded.session
                    // Absence of credentials is not proof that the Room DB,
                    // HTTP cache, or downloads are empty. In particular, the
                    // corruption handler above replaces an unreadable session
                    // file with empty preferences. Only a marker written after
                    // a successful full wipe is trustworthy.
                    accountDataIsClean = loaded.session == null && loaded.accountDataClean
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                Log.w(Tag, "Failed to initialize persisted session", exception)
                _session.value = null
            } finally {
                initialization.complete(Unit)
            }
        }
    }

    private fun sessionValues() = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val username = prefs[usernameKey]
            val storedToken = prefs[tokenKey]
            val cleanupPending = prefs[cleanupPendingKey] == true
            val accountDataClean = prefs[accountDataCleanKey] == true
            val token = storedToken?.let(tokenCodec::decode)
            val hadStoredCredentials = username != null || storedToken != null
            if (cleanupPending || username.isNullOrBlank() || token.isNullOrBlank()) {
                LoadedSession(
                    hadStoredCredentials = hadStoredCredentials,
                    cleanupPending = cleanupPending,
                    accountDataClean = accountDataClean,
                )
            } else {
                LoadedSession(
                    session = AuthSession(username = username, token = token),
                    storedToken = storedToken,
                    needsMigration = tokenCodec.needsMigration(storedToken),
                    hadStoredCredentials = true,
                    accountDataClean = accountDataClean,
                )
            }
        }

    private suspend fun migrateTokenIfNeeded(loaded: LoadedSession) {
        val activeSession = loaded.session ?: return
        val storedToken = loaded.storedToken ?: return
        if (!loaded.needsMigration) return

        try {
            editPreferences(SessionPersistenceOperation.MIGRATE_TOKEN) { prefs ->
                if (prefs[tokenKey] == storedToken) {
                    prefs[tokenKey] = tokenCodec.encode(activeSession.token)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            // A migration failure must not discard an otherwise valid legacy
            // session. It can be retried on the next process start.
            Log.w(Tag, "Failed to migrate persisted session token", exception)
        }
    }

    override suspend fun awaitSessionInitialized(): AuthSession? {
        initialization.await()
        ensureSignedOutAccountDataIsClean()
        return _session.value
    }

    override suspend fun save(session: AuthSession) {
        initialization.await()
        lifecycleMutex.withLock {
            // Once session persistence begins, complete the durable write,
            // gate activation, and in-memory publication as one non-cancellable
            // lifecycle transition. Otherwise a cancelled login could leave a
            // credential on disk that remains invisible until process restart.
            withContext(NonCancellable) {
                val previousSession = _session.value
                val requiresCleanup =
                    (previousSession == null && !accountDataIsClean) ||
                        (previousSession != null && previousSession.username != session.username)
                if (requiresCleanup) {
                    val cleanupSucceeded = clearSessionAndAccountData()
                    check(cleanupSucceeded) {
                        "Cannot persist a new session until previous account data is cleared"
                    }
                }
                editPreferences(SessionPersistenceOperation.SAVE_SESSION) { prefs ->
                    prefs[usernameKey] = session.username
                    prefs[tokenKey] = tokenCodec.encode(session.token)
                    prefs.remove(cleanupPendingKey)
                    prefs.remove(accountDataCleanKey)
                }
                accountDataWriteGate.activate(session.token)
                accountDataIsClean = false
                _session.value = session
            }
        }
    }

    override suspend fun clear() {
        initialization.await()
        lifecycleMutex.withLock {
            if (accountDataIsClean) return@withLock
            withContext(NonCancellable) {
                check(clearSessionAndAccountData()) {
                    "Logout could not durably clear the previous account"
                }
            }
        }
    }

    /**
     * Schedules a conditional asynchronous clear. A delayed 401 can only clear
     * the exact token sent with that request, never a newer login.
     */
    override fun invalidate(expectedToken: String) {
        if (expectedToken.isBlank()) return
        scope.launch {
            initialization.await()
            lifecycleMutex.withLock {
                if (_session.value?.token != expectedToken) return@withLock
                withContext(NonCancellable) {
                    if (clearSessionAndAccountData()) {
                        onSessionExpired()
                    }
                }
            }
        }
    }

    private suspend fun markAccountCleanupPending() {
        // Close the write boundary before publishing signed-out state or
        // touching persistence. This ordering is also important when the
        // DataStore edit itself fails: logout must still tombstone the token in
        // memory immediately, and old callbacks must not be able to write.
        tombstoneSessionInMemory()
        persistAccountCleanupPending()
    }

    private suspend fun tombstoneSessionInMemory() {
        accountDataWriteGate.deactivate()
        _session.value = null
        accountDataIsClean = false
    }

    private suspend fun persistAccountCleanupPending() {
        editPreferences(SessionPersistenceOperation.MARK_CLEANUP_PENDING) { prefs ->
            prefs.remove(usernameKey)
            prefs.remove(tokenKey)
            prefs[cleanupPendingKey] = true
            prefs.remove(accountDataCleanKey)
        }
    }

    private suspend fun clearSessionAndAccountData(): Boolean {
        // Publish the in-memory tombstone first, then make every persistent
        // step best-effort. A failed marker write must not skip the actual wipe,
        // and a second persistence attempt closes transient I/O failures.
        var firstFailure: Exception? = null
        fun recordFailure(exception: Exception) {
            val existing = firstFailure
            if (existing == null) {
                firstFailure = exception
            } else if (existing !== exception) {
                existing.addSuppressed(exception)
            }
        }

        tombstoneSessionInMemory()

        try {
            persistAccountCleanupPending()
        } catch (exception: Exception) {
            recordFailure(exception)
        }

        val wipeSucceeded = try {
            accountDataWiper.wipe()
            true
        } catch (exception: Exception) {
            recordFailure(exception)
            false
        }

        val persistenceSucceeded = try {
            if (wipeSucceeded) {
                editPreferences(SessionPersistenceOperation.MARK_ACCOUNT_DATA_CLEAN) { prefs ->
                    // Repeat credential removal so a transient failure of the
                    // first marker write cannot resurrect the old session.
                    prefs.remove(usernameKey)
                    prefs.remove(tokenKey)
                    prefs.remove(cleanupPendingKey)
                    prefs[accountDataCleanKey] = true
                }
            } else {
                // The wipe failed, so retain a durable dirty marker. Repeating
                // this edit also retries credential invalidation if the first
                // persistence attempt failed.
                persistAccountCleanupPending()
            }
            true
        } catch (exception: Exception) {
            recordFailure(exception)
            false
        }

        val emergencyRevocationSucceeded = if (!persistenceSucceeded) {
            try {
                emergencyRevokePersistedCredentials()
            } catch (exception: Exception) {
                recordFailure(exception)
                false
            }
        } else {
            false
        }
        val cleanupSucceeded = wipeSucceeded && (persistenceSucceeded || emergencyRevocationSucceeded)
        accountDataIsClean = cleanupSucceeded
        if (!cleanupSucceeded) {
            Log.w(
                Tag,
                "Failed to clear all account-derived local data",
                firstFailure ?: IllegalStateException("Account cleanup did not complete"),
            )
        }
        return cleanupSucceeded
    }

    /**
     * Last-resort durable logout for storage failures. Removing the preference
     * file handles legacy plaintext tokens; rotating the keystore alias makes
     * any surviving encrypted copy undecryptable after process restart.
     * Missing the clean marker is intentional: the next process performs one
     * conservative account-data wipe before allowing access.
     */
    private suspend fun emergencyRevokePersistedCredentials(): Boolean =
        withContext(Dispatchers.IO) {
            tokenCodec.invalidate()
            val parent = sessionFile.parentFile
            val candidates = parent
                ?.listFiles()
                ?.filter { it.name == sessionFile.name || it.name.startsWith("${sessionFile.name}.") }
                .orEmpty()
            candidates.all { file -> !file.exists() || file.delete() }
        }

    private suspend fun ensureSignedOutAccountDataIsClean() {
        lifecycleMutex.withLock {
            if (_session.value != null || accountDataIsClean) return@withLock
            val cleanupSucceeded = withContext(NonCancellable) {
                clearSessionAndAccountData()
            }
            check(cleanupSucceeded) {
                "Account data cleanup must finish before signed-out data can be accessed"
            }
        }
    }

    override fun awaitTokenInitialization() {
        runBlocking {
            initialization.await()
            ensureSignedOutAccountDataIsClean()
        }
    }

    override fun currentToken(): String? = session.value?.token

    private suspend fun editPreferences(
        operation: SessionPersistenceOperation,
        transform: suspend (MutablePreferences) -> Unit,
    ) {
        beforePersistenceOperation(operation)
        dataStore.edit(transform)
    }

    private data class LoadedSession(
        val session: AuthSession? = null,
        val storedToken: String? = null,
        val needsMigration: Boolean = false,
        val hadStoredCredentials: Boolean = false,
        val cleanupPending: Boolean = false,
        val accountDataClean: Boolean = false,
    )
}

internal enum class SessionPersistenceOperation {
    MIGRATE_TOKEN,
    SAVE_SESSION,
    MARK_CLEANUP_PENDING,
    MARK_ACCOUNT_DATA_CLEAN,
}
