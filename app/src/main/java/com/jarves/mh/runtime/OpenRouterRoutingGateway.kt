package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderProfile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Adds Mobile Harness' saved OpenRouter routing policy to Anthropic Messages requests. */
internal fun applyOpenRouterRouting(source: JSONObject, profile: ProviderProfile): JSONObject {
    val providers = profile.openRouterProviders
    if (providers.isEmpty()) return source
    val routing = JSONObject()
        .put("order", JSONArray(providers))
        .put("allow_fallbacks", profile.openRouterAllowFallbacks)
    return source.put("provider", routing)
}

/** Loopback proxy used because coding-agent CLIs do not expose OpenRouter's provider body field. */
internal class OpenRouterRoutingGateway(
    private val profile: ProviderProfile,
    private val apiKey: String,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val upstreamConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val requests = java.util.concurrent.ThreadPoolExecutor(
        0, 8, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
        java.util.concurrent.ThreadFactory { task -> Thread(task, "mh-openrouter-request").apply { isDaemon = true } },
    )
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url: String = "http://127.0.0.1:${server.localPort}"

    fun start(): OpenRouterRoutingGateway = apply {
        Thread({ acceptLoop() }, "mh-openrouter-routing").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                runCatching { socket.soTimeout = SOCKET_TIMEOUT_MILLIS }
                sockets.add(socket)
                try {
                    if (!running.get()) { sockets.remove(socket); socket.close(); return }
                    requests.execute {
                        try { socket.use { runCatching { handle(it) } } }
                        finally { sockets.remove(socket) }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    sockets.remove(socket)
                    socket.close()
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?')
        val headers = mutableMapOf<String, String>()
        var headerBytes = 0
        var headerCount = 0
        while (true) {
            val line = readLine(input) ?: return
            headerBytes += line.length
            if (headerBytes > 32_768 || ++headerCount > 100) return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val output = BufferedOutputStream(socket.getOutputStream())
        if (!isAuthorized(headers)) {
            writeJson(output, 401, errorJson("Missing or invalid gateway credentials"))
            return
        }
        if (length !in 0..MAX_REQUEST_BYTES) {
            writeJson(output, 413, errorJson("Request is too large"))
            return
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        if (offset < length) return
        if (path.endsWith("/count_tokens")) {
            writeJson(output, 200, JSONObject().put("input_tokens", body.decodeToString().length / 4 + 1).toString())
            return
        }
        if (method != "POST" || !path.endsWith("/messages")) {
            writeJson(output, 404, errorJson("Unsupported OpenRouter endpoint"))
            return
        }

        runCatching {
            val routedBody = applyOpenRouterRouting(JSONObject(body.decodeToString()), profile).toString().toByteArray()
            val endpoint = profile.resolvedBaseUrl.trimEnd('/') + path
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            upstreamConnections.add(connection)
            try {
                check(running.get()) { "Gateway is closed" }
                connection.requestMethod = "POST"
                connection.connectTimeout = 20_000
                connection.readTimeout = 180_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", headers["accept"] ?: "application/json")
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("anthropic-version", headers["anthropic-version"] ?: "2023-06-01")
                headers["anthropic-beta"]?.let { connection.setRequestProperty("anthropic-beta", it) }
                connection.outputStream.use { it.write(routedBody) }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val reason = if (code in 200..299) "OK" else "Error"
                val contentType = connection.contentType ?: "application/json"
                output.write(
                    "HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nConnection: close\r\n\r\n".toByteArray(),
                )
                stream?.use { it.copyTo(output, DEFAULT_BUFFER_SIZE) }
                output.flush()
            } finally {
                upstreamConnections.remove(connection)
                connection.disconnect()
            }
        }.onFailure { error ->
            writeJson(output, 502, errorJson(error.message ?: "OpenRouter request failed"))
        }
    }

    /** Only the CLI launched with this gateway's key may use the loopback port; other apps share 127.0.0.1. */
    private fun isAuthorized(headers: Map<String, String>): Boolean {
        val presented = headers["x-api-key"]
            ?: headers["authorization"]?.removePrefix("Bearer ")?.trim()
            ?: return false
        return MessageDigest.isEqual(presented.toByteArray(), apiKey.toByteArray())
    }

    private fun writeJson(output: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray()
        output.write(
            "HTTP/1.1 $code Error\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= MAX_HEADER_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
            if (value == '\n'.code) return bytes.toByteArray().decodeToString().trimEnd('\r')
            bytes += value.toByte()
        }
        return null
    }

    private fun errorJson(message: String): String = JSONObject()
        .put("type", "error")
        .put("error", JSONObject().put("type", "api_error").put("message", message))
        .toString()

    override fun close() {
        running.set(false)
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        upstreamConnections.forEach { runCatching { it.disconnect() } }
        requests.shutdownNow()
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 16 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 16 * 1024
        const val SOCKET_TIMEOUT_MILLIS = 30_000
    }
}
