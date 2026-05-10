package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AuthDeviceIdFactoryTest {
    @Test
    fun `create returns lowercase md5 string for seed`() {
        val value = AuthDeviceIdFactory.create("JSMDN")

        assertThat(value).isEqualTo("b5a0155e4942a8a10134bfefcdfbefd5")
    }
}
