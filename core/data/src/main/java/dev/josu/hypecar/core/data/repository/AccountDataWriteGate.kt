package dev.josu.hypecar.core.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * Serializes account-derived local writes with account-data deletion.
 *
 * Repository calls capture a generation before starting potentially slow work.
 * A wipe advances the generation while holding the same mutex used by writes,
 * so a write either completes before (and is then deleted by) the wipe or is
 * rejected afterward. This prevents an old in-flight response from restoring
 * data belonging to the previous account. The private access snapshot also
 * binds a credential to that generation so delayed mutations cannot pick up a
 * later account's token.
 */
class AccountDataWriteGate(initiallyActive: Boolean = true) {
    @JvmInline
    value class Generation internal constructor(internal val value: Long)

    private val accountAccess = AtomicReference(
        AccountAccess(
            generation = Generation(0L),
            isActive = initiallyActive,
            authToken = null,
        ),
    )
    private val mutex = Mutex()
    private val _accountBoundary = MutableStateFlow(
        AccountBoundary(generation = 0L, isActive = initiallyActive),
    )

    /** Credential-free signal for invalidating account-derived UI state. */
    val accountBoundary: StateFlow<AccountBoundary> = _accountBoundary.asStateFlow()

    fun captureGeneration(): Generation = accountAccess.get().generation

    /** Whether account-derived local data may be read at this instant. */
    fun isActive(): Boolean = accountAccess.get().isActive

    /**
     * Checks both identity generation and active-session state from one atomic
     * snapshot. Repositories use this before returning account-derived cache.
     */
    fun isCurrentAccount(capturedGeneration: Generation): Boolean {
        val current = accountAccess.get()
        return current.isActive && current.generation == capturedGeneration
    }

    /**
     * Checks only that no login, logout, or account switch crossed the call.
     * Public network responses are valid while signed out, but not after a
     * boundary change that may have changed their personalization context.
     */
    fun isCurrentBoundary(capturedGeneration: Generation): Boolean =
        accountAccess.get().generation == capturedGeneration

    internal fun captureAccountAccess(): AccountAccess = accountAccess.get()

    /** Returns false without invoking [block] when [capturedGeneration] is stale. */
    suspend fun writeIfCurrent(
        capturedGeneration: Generation,
        block: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        val current = accountAccess.get()
        if (!current.isActive || capturedGeneration != current.generation) {
            false
        } else {
            block()
            true
        }
    }

    /**
     * Starts a new signed-in account generation. Work captured while signed out
     * (or for a previous account) remains stale after activation.
     */
    suspend fun activate() = activate(authToken = null)

    internal suspend fun activate(authToken: String?) {
        mutex.withLock {
            val nextGeneration = Generation(accountAccess.get().generation.value + 1L)
            accountAccess.set(
                AccountAccess(
                    generation = nextGeneration,
                    isActive = true,
                    authToken = authToken,
                ),
            )
            _accountBoundary.value = AccountBoundary(nextGeneration.value, isActive = true)
        }
    }

    /**
     * Immediately closes the account write boundary before session persistence
     * and slower cleanup work begin.
     */
    suspend fun deactivate() {
        mutex.withLock {
            val nextGeneration = Generation(accountAccess.get().generation.value + 1L)
            accountAccess.set(
                AccountAccess(
                    generation = nextGeneration,
                    isActive = false,
                    authToken = null,
                ),
            )
            _accountBoundary.value = AccountBoundary(nextGeneration.value, isActive = false)
        }
    }

    /**
     * Invalidates previously captured generations and runs the complete wipe
     * while excluding repository writes.
     */
    suspend fun <T> wipe(block: suspend () -> T): T = mutex.withLock {
        val nextGeneration = Generation(accountAccess.get().generation.value + 1L)
        accountAccess.set(
            AccountAccess(
                generation = nextGeneration,
                isActive = false,
                authToken = null,
            ),
        )
        _accountBoundary.value = AccountBoundary(nextGeneration.value, isActive = false)
        block()
    }
}

internal class AccountAccess(
    val generation: AccountDataWriteGate.Generation,
    val isActive: Boolean,
    val authToken: String?,
)

/** Public account boundary without any identity or credential material. */
data class AccountBoundary(
    val generation: Long,
    val isActive: Boolean,
)
