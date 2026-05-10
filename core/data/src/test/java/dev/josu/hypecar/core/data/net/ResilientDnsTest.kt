package dev.josu.hypecar.core.data.net

import com.google.common.truth.Truth.assertThat
import okhttp3.Dns
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class ResilientDnsTest {
    @Test
    fun `delegates to system dns when it succeeds`() {
        val expected = listOf(InetAddress.getByName("127.0.0.1"))
        val dns = ResilientDns(
            systemDns = StubDns(success = expected),
            fallbackProviders = listOf(FailingDns),
        )

        val result = dns.lookup("api.hypem.com")

        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `falls back to next provider when system dns fails`() {
        val fallbackResult = listOf(InetAddress.getByName("8.8.8.8"))
        val dns = ResilientDns(
            systemDns = FailingDns,
            fallbackProviders = listOf(StubDns(success = fallbackResult)),
        )

        val result = dns.lookup("api.hypem.com")

        assertThat(result).isEqualTo(fallbackResult)
    }

    @Test
    fun `tries fallbacks in order and skips failing ones`() {
        val fallbackResult = listOf(InetAddress.getByName("1.1.1.1"))
        val dns = ResilientDns(
            systemDns = FailingDns,
            fallbackProviders = listOf(FailingDns, StubDns(success = fallbackResult)),
        )

        val result = dns.lookup("api.hypem.com")

        assertThat(result).isEqualTo(fallbackResult)
    }

    @Test(expected = UnknownHostException::class)
    fun `rethrows the original UnknownHostException when every fallback fails`() {
        val dns = ResilientDns(
            systemDns = FailingDns,
            fallbackProviders = listOf(FailingDns, FailingDns),
        )
        dns.lookup("api.hypem.com")
    }

    private class StubDns(private val success: List<InetAddress>) : Dns {
        override fun lookup(hostname: String): List<InetAddress> = success
    }

    private object FailingDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            throw UnknownHostException("can't resolve $hostname")
    }
}
