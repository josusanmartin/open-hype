package dev.josu.hypecar.core.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.AuthSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    fun `missing clean marker is conservatively wiped once before session access`() = runBlocking {
        val wipeCount = AtomicInteger()
        val store = HypeSessionStore(
            context = context,
            tokenCodec = SessionTokenCodec(cipher),
            accountDataWiper = AccountLocalDataWiper { wipeCount.incrementAndGet() },
        )

        assertThat(store.awaitSessionInitialized()).isNull()
        assertThat(store.awaitSessionInitialized()).isNull()

        assertThat(wipeCount.get()).isEqualTo(1)
    }

    @Test
    fun `corrupt session preferences cannot make existing account data look clean`() = runBlocking {
        context.preferencesDataStoreFile("session.preferences_pb").apply {
            parentFile?.mkdirs()
            // Truncated protobuf varint: PreferencesSerializer reports
            // corruption and the production handler replaces it with empty
            // preferences. Missing the durable clean marker must still wipe.
            writeBytes(byteArrayOf(0x80.toByte()))
        }
        val wipeCount = AtomicInteger()
        val store = HypeSessionStore(
            context = context,
            tokenCodec = SessionTokenCodec(cipher),
            accountDataWiper = AccountLocalDataWiper { wipeCount.incrementAndGet() },
        )

        assertThat(store.awaitSessionInitialized()).isNull()

        assertThat(wipeCount.get()).isEqualTo(1)
        assertThat(store.currentToken()).isNull()
    }

    @Test
    fun `signed-out initialization fails closed when account data cannot be wiped`() = runBlocking {
        val store = HypeSessionStore(
            context = context,
            tokenCodec = SessionTokenCodec(cipher),
            accountDataWiper = AccountLocalDataWiper { error("database unavailable") },
        )

        val result = runCatching { store.awaitSessionInitialized() }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(store.session.value).isNull()
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
    fun `clear wipes the in-memory session and account data`() = runBlocking {
        val wipeCount = AtomicInteger()
        val store = HypeSessionStore(
            context = context,
            tokenCodec = SessionTokenCodec(cipher),
            accountDataWiper = AccountLocalDataWiper { wipeCount.incrementAndGet() },
        )
        store.save(AuthSession("alice", "tok"))
        withTimeout(2_000L) { store.session.filterNotNull().first() }
        val wipesBeforeClear = wipeCount.get()

        store.clear()

        val drained = withTimeout(1_000L) { store.session.first() }
        assertThat(drained).isNull()
        assertThat(store.currentToken()).isNull()
        assertThat(wipeCount.get()).isEqualTo(wipesBeforeClear + 1)
    }

    @Test
    fun `failed cleanup is retried while memory is already signed out`() = runBlocking {
        val wipeCount = AtomicInteger()
        val failNextWipe = AtomicBoolean(false)
        val store = HypeSessionStore(
            context = context,
            tokenCodec = SessionTokenCodec(cipher),
            accountDataWiper = AccountLocalDataWiper {
                wipeCount.incrementAndGet()
                if (failNextWipe.compareAndSet(true, false)) error("disk busy")
            },
        )
        store.save(AuthSession("alice", "tok"))
        failNextWipe.set(true)

        val failedClear = runCatching { store.clear() }
        store.clear()
        store.clear()

        assertThat(failedClear.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(store.session.value).isNull()
        // Initial conservative cleanup, failed logout cleanup, successful retry.
        assertThat(wipeCount.get()).isEqualTo(3)
    }

    @Test
    fun `new account cannot be saved while previous account cleanup keeps failing`() = runBlocking {
        val wipeCount = AtomicInteger()
        val failWipes = AtomicBoolean(false)
        val store = HypeSessionStore(
            context = context,
            tokenCodec = SessionTokenCodec(cipher),
            accountDataWiper = AccountLocalDataWiper {
                wipeCount.incrementAndGet()
                if (failWipes.get()) error("disk busy")
            },
        )
        store.save(AuthSession("alice", "old-token"))
        failWipes.set(true)
        val clearFailure = runCatching { store.clear() }

        val failure = runCatching { store.save(AuthSession("bob", "new-token")) }

        assertThat(clearFailure.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(store.session.value).isNull()
        assertThat(store.currentToken()).isNull()
        // Initial conservative cleanup, failed logout, failed pre-login retry.
        assertThat(wipeCount.get()).isEqualTo(3)
    }

    @Test
    fun `persistence failure durably revokes credentials before allowing a new login`() = runBlocking {
        val failCleanupPersistence = AtomicBoolean(false)
        val wipeCount = AtomicInteger()
        val tombstoneObserved = AtomicBoolean(false)
        lateinit var store: HypeSessionStore
        store = HypeSessionStore(
            context = context,
            tokenCodec = SessionTokenCodec(cipher),
            accountDataWiper = AccountLocalDataWiper { wipeCount.incrementAndGet() },
            beforePersistenceOperation = { operation ->
                if (
                    failCleanupPersistence.get() &&
                    operation == SessionPersistenceOperation.MARK_CLEANUP_PENDING
                ) {
                    // This hook executes before DataStore is touched. The old
                    // token must already be unusable even if persistence fails.
                    assertThat(store.session.value).isNull()
                    assertThat(store.currentToken()).isNull()
                    tombstoneObserved.set(true)
                    throw IOException("session storage unavailable")
                }
                if (
                    failCleanupPersistence.get() &&
                    operation == SessionPersistenceOperation.MARK_ACCOUNT_DATA_CLEAN
                ) {
                    throw IOException("session storage unavailable")
                }
            },
        )
        store.save(AuthSession("alice", "old-token"))
        failCleanupPersistence.set(true)

        store.clear()

        assertThat(tombstoneObserved.get()).isTrue()
        assertThat(store.session.value).isNull()
        assertThat(store.currentToken()).isNull()
        assertThat(context.preferencesDataStoreFile("session.preferences_pb").exists()).isFalse()
        store.save(AuthSession("bob", "new-token"))

        assertThat(store.session.value).isEqualTo(AuthSession("bob", "new-token"))
        assertThat(store.currentToken()).isEqualTo("new-token")
        // Initial cleanup + logout; emergency credential revocation means no
        // third wipe is needed before the next safe in-process login.
        assertThat(wipeCount.get()).isEqualTo(2)
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

        store.invalidate("tok")

        val drained = withTimeout(500L) { store.session.first() }
        assertThat(drained).isNull()
    }

    @Test
    fun `invalidate clears the active session asynchronously`() = runBlocking {
        val wipeCount = AtomicInteger()
        val expirationCount = AtomicInteger()
        val store = HypeSessionStore(
            context = context,
            tokenCodec = SessionTokenCodec(cipher),
            accountDataWiper = AccountLocalDataWiper { wipeCount.incrementAndGet() },
            onSessionExpired = { expirationCount.incrementAndGet() },
        )
        store.save(AuthSession("alice", "tok"))
        withTimeout(2_000L) { store.session.filterNotNull().first() }
        wipeCount.set(0)

        store.invalidate("tok")

        withTimeout(2_000L) {
            while (expirationCount.get() == 0) {
                delay(10)
            }
        }
        assertThat(store.session.first()).isNull()
        assertThat(wipeCount.get()).isEqualTo(1)
        assertThat(expirationCount.get()).isEqualTo(1)
    }

    @Test
    fun `stale old-token invalidation cannot clear a newer login`() = runBlocking {
        val wipeCount = AtomicInteger()
        val expirationCount = AtomicInteger()
        val store = HypeSessionStore(
            context = context,
            tokenCodec = SessionTokenCodec(cipher),
            accountDataWiper = AccountLocalDataWiper { wipeCount.incrementAndGet() },
            onSessionExpired = { expirationCount.incrementAndGet() },
        )
        store.save(AuthSession("alice", "old-token"))
        store.save(AuthSession("alice", "new-token"))
        wipeCount.set(0)

        store.invalidate("old-token")
        delay(200)

        assertThat(store.session.value).isEqualTo(AuthSession("alice", "new-token"))
        assertThat(wipeCount.get()).isEqualTo(0)
        assertThat(expirationCount.get()).isEqualTo(0)
    }

    @Test
    fun `repeated invalidations clean up and emit expiration exactly once`() = runBlocking {
        val wipeCount = AtomicInteger()
        val expirationCount = AtomicInteger()
        val store = HypeSessionStore(
            context = context,
            tokenCodec = SessionTokenCodec(cipher),
            accountDataWiper = AccountLocalDataWiper { wipeCount.incrementAndGet() },
            onSessionExpired = { expirationCount.incrementAndGet() },
        )
        store.save(AuthSession("alice", "tok"))
        wipeCount.set(0)

        repeat(3) { store.invalidate("tok") }

        withTimeout(2_000L) {
            while (expirationCount.get() == 0) {
                delay(10)
            }
        }
        delay(100)
        assertThat(store.session.value).isNull()
        assertThat(wipeCount.get()).isEqualTo(1)
        assertThat(expirationCount.get()).isEqualTo(1)
    }

    @Test
    fun `logout racing expiration does not wipe account data twice`() = runBlocking {
        val wipeCount = AtomicInteger()
        val wipeStarted = CompletableDeferred<Unit>()
        val releaseWipe = CompletableDeferred<Unit>()
        val expirationCount = AtomicInteger()
        val blockWipe = AtomicBoolean(false)
        val store = HypeSessionStore(
            context = context,
            tokenCodec = SessionTokenCodec(cipher),
            accountDataWiper = AccountLocalDataWiper {
                wipeCount.incrementAndGet()
                if (blockWipe.get()) {
                    wipeStarted.complete(Unit)
                    releaseWipe.await()
                }
            },
            onSessionExpired = { expirationCount.incrementAndGet() },
        )
        store.save(AuthSession("alice", "tok"))
        wipeCount.set(0)
        blockWipe.set(true)
        store.invalidate("tok")
        withTimeout(2_000L) { wipeStarted.await() }

        val logout = async { store.clear() }
        releaseWipe.complete(Unit)
        logout.await()
        withTimeout(2_000L) {
            while (expirationCount.get() == 0) {
                delay(10)
            }
        }

        assertThat(wipeCount.get()).isEqualTo(1)
        assertThat(expirationCount.get()).isEqualTo(1)
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
