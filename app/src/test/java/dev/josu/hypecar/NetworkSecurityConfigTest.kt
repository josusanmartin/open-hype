package dev.josu.hypecar

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class NetworkSecurityConfigTest {
    @Test
    fun mainNetworkSecurityConfigDoesNotAllowCleartextDevHosts() {
        val config = File("src/main/res/xml/network_security_config.xml").readText()

        assertThat(config).doesNotContain("cleartextTrafficPermitted=\"true\"")
        assertThat(config).doesNotContain("10.0.2.2")
        assertThat(config).doesNotContain("localhost")
    }
}
