package com.jarves.mh.runtime

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

/** Local server URL detection shared by Terminal and supervised agent output. */
internal object LocalPreviewDiscovery {
    private val urls = Regex("https?://[^\\s<>\"']+")
    private val loopbackHosts = setOf("localhost", "127.0.0.1", "0.0.0.0", "[::1]")

    fun candidate(output: String): String? {
        return urls.findAll(output.takeLast(8_192)).mapNotNull { match ->
            val uri = runCatching { URI(match.value.trimEnd('.', ',', ')', ']', ';')) }.getOrNull() ?: return@mapNotNull null
            if (uri.host !in loopbackHosts || uri.userInfo != null || uri.port !in 1..65535) return@mapNotNull null
            "${uri.scheme}://127.0.0.1:${uri.port}/"
        }.lastOrNull()
    }

    /** One bounded HTTP probe, without proxies, redirects, or changes to TLS trust. */
    fun isReady(url: String): Boolean {
        if (candidate(url) != url) return false
        val connection = runCatching { URI(url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection }.getOrNull() ?: return false
        return try {
            connection.connectTimeout = 500
            connection.readTimeout = 500
            connection.instanceFollowRedirects = false
            connection.requestMethod = "HEAD"
            connection.responseCode in 100..599
        } catch (_: java.io.IOException) { false }
        finally { connection.disconnect() }
    }
}
