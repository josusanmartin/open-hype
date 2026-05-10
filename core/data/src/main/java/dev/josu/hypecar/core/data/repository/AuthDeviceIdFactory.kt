package dev.josu.hypecar.core.data.repository

import java.security.MessageDigest

object AuthDeviceIdFactory {
    fun create(seed: String): String {
        val normalized = seed.ifBlank { "guest" }.trim()
        val digest = MessageDigest.getInstance("MD5").digest(normalized.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
