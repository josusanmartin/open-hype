package dev.josu.hypecar.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.network.AuthTokenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class HypeSessionStore(
    context: Context,
    private val tokenCodec: SessionTokenCodec = SessionTokenCodec(AndroidKeystoreSessionTokenCipher()),
) : AuthTokenProvider,
    SessionGateway {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { context.preferencesDataStoreFile("session.preferences_pb") },
    )

    private val usernameKey = stringPreferencesKey("username")
    private val tokenKey = stringPreferencesKey("token")

    private val _session = MutableStateFlow<AuthSession?>(null)
    override val session: StateFlow<AuthSession?> = _session

    init {
        scope.launch {
            dataStore.data
                .catch { emit(emptyPreferences()) }
                .map { prefs ->
                    val username = prefs[usernameKey]
                    val storedToken = prefs[tokenKey]
                    val token = storedToken?.let(tokenCodec::decode)
                    if (username.isNullOrBlank() || token.isNullOrBlank()) {
                        LoadedSession()
                    } else {
                        LoadedSession(
                            session = AuthSession(username = username, token = token),
                            needsMigration = tokenCodec.needsMigration(storedToken),
                        )
                    }
                }
                .collect { loaded ->
                    _session.value = loaded.session
                    if (loaded.session != null && loaded.needsMigration) {
                        save(loaded.session)
                    }
                }
        }
    }

    override suspend fun save(session: AuthSession) {
        dataStore.edit { prefs ->
            prefs[usernameKey] = session.username
            prefs[tokenKey] = tokenCodec.encode(session.token)
        }
        _session.value = session
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(usernameKey)
            prefs.remove(tokenKey)
        }
        _session.value = null
    }

    /**
     * Schedules an asynchronous clear of the session. Safe to call from any thread,
     * including OkHttp interceptors that observe a 401 response.
     */
    override fun invalidate() {
        if (_session.value == null) return
        if (!invalidating.compareAndSet(false, true)) return
        scope.launch {
            try {
                clear()
            } finally {
                invalidating.set(false)
            }
        }
    }

    override fun currentToken(): String? = session.value?.token

    private val invalidating = AtomicBoolean(false)

    private data class LoadedSession(
        val session: AuthSession? = null,
        val needsMigration: Boolean = false,
    )
}
