package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderProfile
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Small loopback-only Anthropic-to-OpenAI compatibility bridge for Claude Code. */
internal class LocalFormatGateway(
    private val profile: ProviderProfile,
    private val apiKey: String,
    val gatewaySecret: String = AntigravityGatewayServer.generateGatewaySecret(),
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val upstreamConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val requests = java.util.concurrent.ThreadPoolExecutor(
        0, 8, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
        java.util.concurrent.ThreadFactory { task -> Thread(task, "mh-format-request").apply { isDaemon = true } },
    )
    private val responses = com.jarves.mh.model.providerProtocolForAgent(profile, com.jarves.mh.model.AgentKind.CLAUDE_CODE) ==
        com.jarves.mh.model.ProviderProtocol.OPENAI_RESPONSES
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url: String = "http://127.0.0.1:${server.localPort}"

    fun start(): LocalFormatGateway = apply {
        Thread({ acceptLoop() }, "mh-format-gateway").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                socket.soTimeout = 30_000
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
        val headers = mutableMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input) ?: return
            headerBytes += line.length
            if (headerBytes > 32_768 || headers.size >= 100) return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > 16 * 1024 * 1024) {
            writeJson(output, 413, errorJson("invalid_request_error", "Payload too large"))
            return
        }
        if (!isAuthorized(headers["authorization"])) {
            writeUnauthorized(output)
            return
        }
        val bodyBytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bodyBytes, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        if (offset < length) return
        val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        if (path == "/" || path == "/health" || path == "/v1" || path == "/v1/") {
            writeJson(output, 200, JSONObject().put("status", "ok").put("service", "format-gateway").toString())
            return
        }
        if (path.trimEnd('/').endsWith("/count_tokens")) {
            val approximate = bodyBytes.decodeToString().length / 4 + 1
            writeJson(output, 200, JSONObject().put("input_tokens", approximate).toString())
            return
        }
        if (!path.trimEnd('/').endsWith("/messages")) {
            writeJson(output, 404, errorJson("not_found", "Unsupported gateway endpoint"))
            return
        }
        runCatching {
            val anthropic = JSONObject(bodyBytes.decodeToString())
            val upstream = callProvider(if (responses) toResponses(anthropic) else toOpenAi(anthropic))
            if (upstream.first !in 200..299) {
                runCatching { Log.w("FormatGateway", "Provider returned HTTP ${upstream.first}") }
                writeJson(output, upstream.first, errorJson("api_error", providerError(upstream.second)))
            } else {
                val translated = if (responses) fromResponses(JSONObject(upstream.second), anthropic.optString("model", profile.model))
                    else fromOpenAi(JSONObject(upstream.second), anthropic.optString("model", profile.model))
                if (anthropic.optBoolean("stream", false)) writeStream(output, translated) else writeJson(output, 200, translated.toString())
            }
        }.onFailure { error ->
            writeJson(output, 502, errorJson("api_error", com.jarves.mh.provider.ProviderFailureClassifier.redact(error.message ?: "Provider request failed", listOf(apiKey))))
        }
    }

    private fun toOpenAi(source: JSONObject): JSONObject {
        val target = JSONObject()
            .put("model", normalizeModel(profile.model))
            .put("stream", false)
            .put("max_tokens", source.optInt("max_tokens", 4096))
        if (source.has("temperature")) target.put("temperature", source.get("temperature"))
        val messages = JSONArray()
        source.opt("system")?.let { system ->
            val text = when (system) {
                is JSONArray -> contentText(system)
                else -> system.toString()
            }
            if (text.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", text))
        }
        val sourceMessages = source.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until sourceMessages.length()) {
            val message = sourceMessages.getJSONObject(index)
            val role = message.optString("role")
            val content = message.opt("content")
            if (content !is JSONArray) {
                messages.put(JSONObject().put("role", role).put("content", content ?: ""))
                continue
            }
            val text = contentText(content)
            val toolCalls = JSONArray()
            val toolResults = mutableListOf<JSONObject>()
            for (partIndex in 0 until content.length()) {
                val part = content.optJSONObject(partIndex) ?: continue
                when (part.optString("type")) {
                    "tool_use" -> toolCalls.put(
                        JSONObject().put("id", part.optString("id"))
                            .put("type", "function")
                            .put("function", JSONObject().put("name", part.optString("name")).put("arguments", part.optJSONObject("input")?.toString() ?: "{}")),
                    )
                    "tool_result" -> toolResults += JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", part.optString("tool_use_id"))
                        .put("content", valueText(part.opt("content")))
                }
            }
            if (text.isNotBlank() || toolCalls.length() > 0) {
                val converted = JSONObject().put("role", role).put("content", text.ifBlank { JSONObject.NULL })
                if (toolCalls.length() > 0) converted.put("tool_calls", toolCalls)
                messages.put(converted)
            }
            toolResults.forEach(messages::put)
        }
        target.put("messages", messages)
        source.optJSONArray("tools")?.let { tools ->
            val converted = JSONArray()
            for (index in 0 until tools.length()) {
                val tool = tools.getJSONObject(index)
                converted.put(JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", tool.optString("name"))
                    .put("description", tool.optString("description"))
                    .put("parameters", tool.optJSONObject("input_schema") ?: JSONObject().put("type", "object"))))
            }
            target.put("tools", converted).put("tool_choice", "auto")
        }
        return target
    }

    internal fun toResponses(source: JSONObject): JSONObject {
        val chat = toOpenAi(source)
        val input = JSONArray()
        val messages = chat.getJSONArray("messages")
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            if (message.optString("role") == "tool") {
                input.put(JSONObject().put("type", "function_call_output")
                    .put("call_id", message.getString("tool_call_id")).put("output", message.getString("content")))
                continue
            }
            if (!message.isNull("content")) input.put(JSONObject().put("role", message.getString("role"))
                .put("content", message.get("content")))
            val calls = message.optJSONArray("tool_calls")
            for (callIndex in 0 until (calls?.length() ?: 0)) {
                val call = calls!!.getJSONObject(callIndex)
                val function = call.getJSONObject("function")
                input.put(JSONObject().put("type", "function_call").put("call_id", call.getString("id"))
                    .put("name", function.getString("name")).put("arguments", function.getString("arguments")))
            }
        }
        val target = JSONObject().put("model", chat.getString("model")).put("input", input)
            .put("stream", false).put("store", false).put("max_output_tokens", chat.getInt("max_tokens"))
        if (chat.has("temperature")) target.put("temperature", chat.get("temperature"))
        chat.optJSONArray("tools")?.let { tools ->
            val converted = JSONArray()
            for (index in 0 until tools.length()) {
                converted.put(JSONObject(tools.getJSONObject(index).getJSONObject("function").toString())
                    .put("type", "function").put("strict", false))
            }
            target.put("tools", converted).put("tool_choice", "auto")
        }
        return target
    }

    internal fun fromResponses(source: JSONObject, model: String): JSONObject {
        check(source.optString("status") == "completed" && source.isNull("error")) { "Responses provider did not complete the response" }
        val message = JSONObject()
        val text = StringBuilder()
        val calls = JSONArray()
        val items = source.getJSONArray("output")
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            when (item.optString("type")) {
                "message" -> {
                    val content = item.optJSONArray("content") ?: JSONArray()
                    for (partIndex in 0 until content.length()) {
                        val part = content.getJSONObject(partIndex)
                        when (part.optString("type")) {
                            "output_text" -> text.append(part.optString("text"))
                            "refusal" -> text.append(part.optString("refusal"))
                        }
                    }
                }
                "function_call" -> calls.put(JSONObject().put("id", item.getString("call_id"))
                    .put("function", JSONObject().put("name", item.getString("name")).put("arguments", item.getString("arguments"))))
            }
        }
        message.put("content", text.toString()).put("tool_calls", calls)
        val usage = source.optJSONObject("usage") ?: JSONObject()
        val translated = JSONObject().put("id", source.optString("id"))
            .put("choices", JSONArray().put(JSONObject().put("message", message)))
            .put("usage", JSONObject().put("prompt_tokens", usage.optInt("input_tokens"))
                .put("completion_tokens", usage.optInt("output_tokens")))
        return fromOpenAi(translated, model)
    }

    private fun fromOpenAi(source: JSONObject, model: String): JSONObject {
        val message = source.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: JSONObject()
        val content = JSONArray()
        val text = message.optString("content")
        if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
        val calls = message.optJSONArray("tool_calls") ?: JSONArray()
        for (index in 0 until calls.length()) {
            val call = calls.getJSONObject(index)
            val function = call.optJSONObject("function") ?: JSONObject()
            val arguments = runCatching { JSONObject(function.optString("arguments", "{}")) }.getOrDefault(JSONObject())
            content.put(JSONObject().put("type", "tool_use")
                .put("id", call.optString("id").ifBlank { "tool_${UUID.randomUUID()}" })
                .put("name", function.optString("name"))
                .put("input", arguments))
        }
        val usage = source.optJSONObject("usage") ?: JSONObject()
        return JSONObject().put("id", source.optString("id").ifBlank { "msg_${UUID.randomUUID()}" })
            .put("type", "message").put("role", "assistant").put("model", model)
            .put("content", content).put("stop_reason", if (calls.length() > 0) "tool_use" else "end_turn")
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", usage.optInt("prompt_tokens")).put("output_tokens", usage.optInt("completion_tokens")))
    }

    private fun callProvider(body: JSONObject): Pair<Int, String> {
        if (com.jarves.mh.provider.isRevokedProvider(profile.baseUrl)) {
            return 403 to "Connection blocked: ${profile.baseUrl} is a revoked phishing provider"
        }
        val endpoint = com.jarves.mh.provider.ProviderEndpointNormalizer.normalize(profile.baseUrl, profile.dshApi).baseUrl +
            if (responses) "/responses" else "/chat/completions"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        upstreamConnections.add(connection)
        return try {
            check(running.get()) { "Gateway closed" }
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to stream?.use { input ->
                val result = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(8192)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    check(result.size() + count <= 16 * 1024 * 1024) { "Provider response too large" }
                    result.write(bytes, 0, count)
                }
                result.toString("UTF-8")
            }.orEmpty()
        } finally {
            upstreamConnections.remove(connection)
            connection.disconnect()
        }
    }

    private fun writeStream(output: BufferedOutputStream, message: JSONObject) {
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
        output.write(headers.toByteArray())
        fun event(name: String, data: JSONObject) { output.write("event: $name\ndata: $data\n\n".toByteArray()) }
        val content = message.getJSONArray("content")
        event("message_start", JSONObject().put("type", "message_start").put("message", JSONObject(message.toString()).put("content", JSONArray()).put("stop_reason", JSONObject.NULL)))
        for (index in 0 until content.length()) {
            val block = content.getJSONObject(index)
            val type = block.getString("type")
            val start = if (type == "text") JSONObject().put("type", "text").put("text", "") else JSONObject().put("type", "tool_use").put("id", block.getString("id")).put("name", block.getString("name")).put("input", JSONObject())
            event("content_block_start", JSONObject().put("type", "content_block_start").put("index", index).put("content_block", start))
            val delta = if (type == "text") JSONObject().put("type", "text_delta").put("text", block.getString("text")) else JSONObject().put("type", "input_json_delta").put("partial_json", block.getJSONObject("input").toString())
            event("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", index).put("delta", delta))
            event("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", index))
        }
        event("message_delta", JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", message.getString("stop_reason")).put("stop_sequence", JSONObject.NULL)).put("usage", message.getJSONObject("usage")))
        event("message_stop", JSONObject().put("type", "message_stop"))
        output.flush()
    }

    private fun isAuthorized(authHeader: String?): Boolean {
        if (authHeader == null || !authHeader.startsWith("Bearer ", ignoreCase = true)) {
            return false
        }
        val token = authHeader.substring(7).trim()
        if (token.isEmpty()) return false
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        val secretBytes = gatewaySecret.toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        val digestToken = md.digest(tokenBytes)
        md.reset()
        val digestSecret = md.digest(secretBytes)
        return MessageDigest.isEqual(digestToken, digestSecret)
    }

    private fun writeUnauthorized(output: BufferedOutputStream) {
        writeJson(output, 401, errorJson("authentication_error", "Missing or invalid authorization token"))
    }

    private fun writeJson(output: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            502 -> "Bad Gateway"
            else -> if (code in 200..299) "OK" else "Error"
        }
        val authHeader = if (code == 401) "WWW-Authenticate: Bearer\r\n" else ""
        output.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n${authHeader}Connection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
            if (value == '\n'.code) return bytes.toByteArray().decodeToString().trimEnd('\r')
            require(bytes.size < 8192) { "HTTP line too long" }
            bytes += value.toByte()
        }
    }

    private fun contentText(array: JSONArray): String = buildString {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("type") == "text") append(item.optString("text"))
        }
    }

    private fun valueText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> contentText(value).ifBlank { value.toString() }
        null, JSONObject.NULL -> ""
        else -> value.toString()
    }

    private fun providerError(body: String): String = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty().ifBlank { body.take(500) }
    }.getOrDefault(body.take(500))

    private fun normalizeModel(model: String): String = model
        .removePrefix("models/")
        .removePrefix("anthropic/")
        .substringBefore('[')
        .trim()

    private fun errorJson(type: String, message: String) = JSONObject().put("type", "error").put("error", JSONObject().put("type", type).put("message", message)).toString()

    override fun close() {
        running.set(false)
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        upstreamConnections.forEach { runCatching { it.disconnect() } }
        requests.shutdownNow()
    }
}
