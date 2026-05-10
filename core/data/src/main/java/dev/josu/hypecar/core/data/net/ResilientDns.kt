package dev.josu.hypecar.core.data.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Automotive emulator images can bring up a working IP route while the system DNS
 * resolver still fails. Fall back to bootstrapped DoH when the platform lookup
 * throws UnknownHostException so public endpoints remain reachable.
 */
class ResilientDns(
    private val systemDns: Dns = Dns.SYSTEM,
    fallbackProviders: List<Dns> = defaultFallbackProviders(),
) : Dns {
    private val fallbackProviders = fallbackProviders

    override fun lookup(hostname: String): List<InetAddress> = try {
        systemDns.lookup(hostname)
    } catch (original: UnknownHostException) {
        fallbackProviders.firstNotNullOfOrNull { fallback ->
            runCatching { fallback.lookup(hostname) }.getOrNull()
        } ?: throw original
    }

    companion object {
        private fun defaultFallbackProviders(): List<Dns> {
            val bootstrapClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()

            return listOf(
                DnsOverHttps.Builder()
                    .client(bootstrapClient)
                    .url("https://dns.google/dns-query".toHttpUrl())
                    .bootstrapDnsHosts(
                        listOf(
                            InetAddress.getByName("8.8.8.8"),
                            InetAddress.getByName("8.8.4.4"),
                        ),
                    )
                    .includeIPv6(false)
                    .post(true)
                    .build(),
                DnsOverHttps.Builder()
                    .client(bootstrapClient)
                    .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                    .bootstrapDnsHosts(
                        listOf(
                            InetAddress.getByName("1.1.1.1"),
                            InetAddress.getByName("1.0.0.1"),
                        ),
                    )
                    .includeIPv6(false)
                    .post(true)
                    .build(),
            )
        }
    }
}
