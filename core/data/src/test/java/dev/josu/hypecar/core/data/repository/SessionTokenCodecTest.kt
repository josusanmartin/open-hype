package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionTokenCodecTest {
    private val cipher = FakeSessionTokenCipher()
    private val codec = SessionTokenCodec(cipher)

    @Test
    fun `encode stores token through encrypted cipher`() {
        val stored = codec.encode("hm_test_token")

        assertThat(stored).isEqualTo("enc:hm_test_token")
    }

    @Test
    fun `decode reads encrypted token`() {
        val token = codec.decode("enc:hm_test_token")

        assertThat(token).isEqualTo("hm_test_token")
    }

    @Test
    fun `decode accepts legacy plaintext token`() {
        val token = codec.decode("hm_legacy_token")

        assertThat(token).isEqualTo("hm_legacy_token")
    }

    @Test
    fun `needsMigration is true only for readable plaintext tokens`() {
        assertThat(codec.needsMigration("hm_legacy_token")).isTrue()
        assertThat(codec.needsMigration("enc:hm_test_token")).isFalse()
        assertThat(codec.needsMigration("")).isFalse()
    }
}

private class FakeSessionTokenCipher : SessionTokenCipher {
    override fun encrypt(plainText: String): String = "enc:$plainText"

    override fun decrypt(cipherText: String): String? =
        cipherText.removePrefix("enc:").takeIf { cipherText.startsWith("enc:") }

    override fun isEncrypted(value: String): Boolean = value.startsWith("enc:")
}
