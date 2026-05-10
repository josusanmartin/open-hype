package dev.josu.hypecar.core.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.AuthSession
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * NOTE: DataStore allows only one active instance per file per process. Each test
 * therefore constructs exactly one [HypeSessionStore] and never re-opens the file
 * from a second instance. The deletes in [Before]/[After] keep tests independent.
 */
@RunWith(RobolectricTestRunner::class)
class HypeSessionStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cipher = FakeReversibleCipher()

    @Before
    fun clearStore() {
        context.preferencesDataStoreFile("session.preferences_pb").delete()
    }

    @After
    fun cleanup() {
        context.preferencesDataStoreFile("session.preferences_pb").delete()
    }

    @Test
    fun `currentToken is null before any session loads`() {
        val store = HypeSessionStore(context, SessionTokenCodec(cipher))
        assertThat(store.currentToken()).isNull()
    }

    @Test
    fun `save publishes session through state flow and exposes the token`() = runBlocking {
        val store = HypeSessionStore(context, SessionTokenCodec(cipher))

        store.save(AuthSession(username = "alice", token = "secret-tok"))

        val loaded = withTimeout(2_000L) { store.session.filterNotNull().first() }
        assertThat(loaded.username).isEqualTo("alice")
        assertThat(loaded.token).isEqualTo("secret-tok")
        assertThat(store.currentToken()).isEqualTo("secret-tok")
    }

    @Test
    fun `clear wipes the in-memory session`() = runBlocking {
        val store = HypeSessionStore(context, SessionTokenCodec(cipher))
        store.save(AuthSession("alice", "tok"))
        withTimeout(2_000L) { store.session.filterNotNull().first() }

        store.clear()

        val drained = withTimeout(1_000L) { store.session.first() }
        assertThat(drained).isNull()
        assertThat(store.currentToken()).isNull()
    }

    @Test
    fun `save uses the cipher to encrypt the token at rest`() = runBlocking {
        val store = HypeSessionStore(context, SessionTokenCodec(cipher))
        val plain = "secret-tok"

        store.save(AuthSession(username = "alice", token = plain))
        withTimeout(2_000L) { store.session.filterNotNull().first() }

        // Cipher saw the plaintext during encryption.
        assertThat(cipher.encryptCalls).contains(plain)
    }

    @Test
    fun `invalidate is a no-op when no session is loaded`() = runBlocking {
        val store = HypeSessionStore(context, SessionTokenCodec(cipher))

        store.invalidate()

        val drained = withTimeout(500L) { store.session.first() }
        assertThat(drained).isNull()
    }

    @Test
    fun `invalidate clears the active session asynchronously`() = runBlocking {
        val store = HypeSessionStore(context, SessionTokenCodec(cipher))
        store.save(AuthSession("alice", "tok"))
        withTimeout(2_000L) { store.session.filterNotNull().first() }

        store.invalidate()

        // Spin until the async clear lands.
        withTimeout(2_000L) {
            while (store.session.first() != null) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertThat(store.session.first()).isNull()
    }
}

private class FakeReversibleCipher : SessionTokenCipher {
    val encryptCalls = mutableListOf<String>()
    override fun encrypt(plainText: String): String {
        encryptCalls += plainText
        return "enc:$plainText"
    }
    override fun decrypt(cipherText: String): String? =
        cipherText.removePrefix("enc:").takeIf { cipherText.startsWith("enc:") }
    override fun isEncrypted(value: String): Boolean = value.startsWith("enc:")
}
