package com.jarves.mh.runtime

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeInstallerDnsTest {

    @Test
    fun computeResolvConfContent_filtersOutIPv6Addresses_andProvidesFallbacks() {
        val ipv6DnsServers = listOf(
            InetAddress.getByName("2405:200:800::11"),
            InetAddress.getByName("2405:200:800::25"),
        )
        val content = RuntimeInstaller.computeResolvConfContent(ipv6DnsServers)

        // Must not contain any IPv6 nameservers
        assertFalse(content.contains("2405:200:800::11"))
        assertFalse(content.contains("2405:200:800::25"))
        assertFalse(content.contains(":"))

        // Must contain IPv4 standard fallbacks
        assertEquals(
            """
            nameserver 8.8.8.8
            nameserver 1.1.1.1
            nameserver 8.8.4.4
            """.trimIndent() + "\n",
            content,
        )
    }

    @Test
    fun computeResolvConfContent_preservesIPv4AndFiltersIPv6() {
        val mixedDnsServers = listOf(
            InetAddress.getByName("2405:200:800::11"),
            InetAddress.getByName("192.168.1.1"),
            InetAddress.getByName("2405:200:800::25"),
        )
        val content = RuntimeInstaller.computeResolvConfContent(mixedDnsServers)

        assertFalse(content.contains("2405:200:800::11"))
        assertFalse(content.contains("2405:200:800::25"))

        assertEquals(
            """
            nameserver 192.168.1.1
            nameserver 8.8.8.8
            nameserver 1.1.1.1
            """.trimIndent() + "\n",
            content,
        )
    }

    @Test
    fun computeResolvConfContent_handlesEmptyDnsList() {
        val content = RuntimeInstaller.computeResolvConfContent(emptyList())

        assertEquals(
            """
            nameserver 8.8.8.8
            nameserver 1.1.1.1
            nameserver 8.8.4.4
            """.trimIndent() + "\n",
            content,
        )
    }

    @Test
    fun computeResolvConfContent_deduplicatesAndCapsToThreeServers() {
        val dnsServers = listOf(
            InetAddress.getByName("8.8.8.8"),
            InetAddress.getByName("10.0.0.1"),
            InetAddress.getByName("172.16.0.1"),
        )
        val content = RuntimeInstaller.computeResolvConfContent(dnsServers)

        assertEquals(
            """
            nameserver 8.8.8.8
            nameserver 10.0.0.1
            nameserver 172.16.0.1
            """.trimIndent() + "\n",
            content,
        )
    }
}
