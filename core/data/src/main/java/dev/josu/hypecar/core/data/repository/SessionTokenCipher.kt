package dev.josu.hypecar.core.data.repository

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SessionTokenCipher {
    fun encrypt(plainText: String): String

    fun decrypt(cipherText: String): String?

    fun isEncrypted(value: String): Boolean

    /** Invalidates encrypted credentials when their preference file cannot be edited. */
    fun invalidate() = Unit
}

class SessionTokenCodec(
    private val cipher: SessionTokenCipher,
) {
    fun encode(token: String): String = cipher.encrypt(token)

    fun decode(storedToken: String): String? =
        when {
            storedToken.isBlank() -> null
            cipher.isEncrypted(storedToken) -> cipher.decrypt(storedToken)
            else -> storedToken
        }

    fun needsMigration(storedToken: String): Boolean =
        storedToken.isNotBlank() && !cipher.isEncrypted(storedToken)

    fun invalidate() = cipher.invalidate()
}

class AndroidKeystoreSessionTokenCipher : SessionTokenCipher {
    private companion object {
        const val AndroidKeyStore = "AndroidKeyStore"
        const val Alias = "hype_car_session_token"
        const val Transformation = "AES/GCM/NoPadding"
        const val Prefix = "keystore-aes-gcm-v1:"
        const val AuthTagBits = 128
    }

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.getEncoder().encodeToString(cipher.iv)
        val encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)))
        return "$Prefix$iv:$encrypted"
    }

    override fun decrypt(cipherText: String): String? =
        runCatching {
            val payload = cipherText.removePrefix(Prefix)
            val separatorIndex = payload.indexOf(':')
            if (separatorIndex <= 0 || separatorIndex == payload.lastIndex) return@runCatching null

            val iv = Base64.getDecoder().decode(payload.substring(0, separatorIndex))
            val encrypted = Base64.getDecoder().decode(payload.substring(separatorIndex + 1))
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(AuthTagBits, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()

    override fun isEncrypted(value: String): Boolean = value.startsWith(Prefix)

    override fun invalidate() {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        if (keyStore.containsAlias(Alias)) keyStore.deleteEntry(Alias)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        val existing = keyStore.getEntry(Alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        val keySpec = KeyGenParameterSpec.Builder(
            Alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }
}
