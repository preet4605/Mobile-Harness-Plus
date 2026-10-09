package com.jarves.mh.runtime

import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class LocalPreviewDiscoveryTest {
    @Test fun detectsLocalServersAndPreservesTheirScheme() {
        assertEquals("http://127.0.0.1:3000/", LocalPreviewDiscovery.candidate("Local: http://0.0.0.0:3000/page"))
        assertEquals("https://127.0.0.1:8443/", LocalPreviewDiscovery.candidate("Local: https://localhost:8443/"))
    }

    @Test fun refusesRemoteHostsCredentialsAndInvalidPorts() {
        for (url in listOf("http://localhost:3000@evil.example/", "http://localhost:3000.evil.example/", "http://example.com:3000/", "http://127.0.0.1:0/", "http://localhost:70000/")) {
            assertNull(url, LocalPreviewDiscovery.candidate(url))
        }
    }

    @Test fun aClosedServerIsNotReady() {
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(LocalPreviewDiscovery.isReady("http://127.0.0.1:$port/"))
        assertFalse(LocalPreviewDiscovery.isReady("http://example.com:3000/"))
    }

    @Test fun readinessDoesNotFollowRedirectsToRemoteHosts() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            server.soTimeout = 2_000
            val task = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://example.com/\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
            }
            try {
                assertTrue(LocalPreviewDiscovery.isReady("http://127.0.0.1:${server.localPort}/"))
                task.get(3, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun aStalledHttpServerCannotBlockDiscovery() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            server.soTimeout = 2_000
            val task = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    Thread.sleep(1_200)
                }
            }
            try {
                val start = System.nanoTime()
                assertFalse(LocalPreviewDiscovery.isReady("http://127.0.0.1:${server.localPort}/"))
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000)
                task.get(3, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
}
