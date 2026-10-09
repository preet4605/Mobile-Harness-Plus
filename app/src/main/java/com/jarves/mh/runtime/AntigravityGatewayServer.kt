package com.jarves.mh.runtime

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loopback-only unified Antigravity Gateway Server.
 * Exposes Anthropic Messages and OpenAI Chat Completions APIs backed by
 * Google Cloud Code PA / Antigravity upstream with multi-account OAuth failover.
 */
class AntigravityGatewayServer(
    private val accountManager: AntigravityAccountManager,
    private val targetModel: () -> String = { "gemini-3.8-pro" },
    private val upstreamBaseUrl: String = DEFAULT_CLOUDCODE_URL,
    private val transportOverride: ((url: String, method: String, headers: Map<String, String>, body: String?) -> GatewayHttpResult)? = null,
    val gatewaySecret: String = generateGatewaySecret(),
) : AutoCloseable {

    companion object {
        const val DEFAULT_CLOUDCODE_URL = "https://daily-cloudcode-pa.googleapis.com"
        private const val TAG = "AntigravityGateway"

        fun generateGatewaySecret(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val hex = bytes.joinToString("") { "%02x".format(it) }
            return "sk-ant-oat-$hex"
        }
    }

    data class GatewayHttpResult(
        val code: Int,
        val body: String,
        val stream: InputStream? = null,
    )

    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    val url: String = "http://127.0.0.1:${server.localPort}"

    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val upstreamConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val requestExecutor = java.util.concurrent.ThreadPoolExecutor(
        0, 16, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
        java.util.concurrent.ThreadFactory { task -> Thread(task, "antigravity-req").apply { isDaemon = true } },
    )

    fun start(): AntigravityGatewayServer = apply {
        Thread({ acceptLoop() }, "antigravity-gateway").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (running.compareAndSet(true, false)) {
            runCatching { server.close() }
            sockets.forEach { runCatching { it.close() } }
            upstreamConnections.forEach { runCatching { it.disconnect() } }
            runCatching { requestExecutor.shutdownNow() }
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                socket.soTimeout = 30_000
                socket.tcpNoDelay = true
                if (!running.get()) {
                    runCatching { socket.close() }
                    return
                }
                sockets.add(socket)
                try {
                    requestExecutor.execute {
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
        val output = BufferedOutputStream(socket.getOutputStream())
        try {
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

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > 16 * 1024 * 1024) {
                writeJson(output, 413, AntigravityProtocolAdapter.anthropicErrorJson("invalid_request_error", "Payload too large"))
                return
            }

            if (!isAuthorized(headers["authorization"])) {
                writeUnauthorized(output, requestLine)
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

        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty().uppercase()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?')

        when {
            method == "GET" && (path == "/v1/models" || path == "/models" || path == "/v1/models/" || path == "/models/") -> {
                handleModelsCatalog(output)
            }
            method == "GET" && (path == "/" || path == "/health" || path == "/v1" || path == "/v1/") -> {
                writeJson(output, 200, JSONObject().put("status", "ok").put("service", "antigravity-gateway").toString())
            }
            method == "POST" && path.endsWith("/count_tokens") -> {
                val approximate = bodyBytes.decodeToString().length / 4 + 1
                writeJson(output, 200, JSONObject().put("input_tokens", approximate).toString())
            }
            method == "POST" && (path.endsWith("/messages") || path.endsWith("/messages/")) -> {
                handleInference(output, bodyBytes.decodeToString(), WireFormat.ANTHROPIC)
            }
            method == "POST" && (path.endsWith("/chat/completions") || path.endsWith("/chat/completions/")) -> {
                handleInference(output, bodyBytes.decodeToString(), WireFormat.OPENAI)
            }
            else -> {
                writeJson(output, 404, AntigravityProtocolAdapter.anthropicErrorJson("not_found", "Endpoint not supported: $path"))
            }
        }
        } catch (t: Throwable) {
            runCatching {
                writeJson(output, 500, AntigravityProtocolAdapter.anthropicErrorJson("api_error", t.message ?: "Internal gateway error"))
            }
        }
    }

    private fun handleModelsCatalog(output: BufferedOutputStream) {
        val modelsArray = JSONArray()
        val primaryAccount = accountManager.getPrimaryAccount()

        for (modelId in AntigravityProtocolAdapter.SUPPORTED_MODELS) {
            val remainingPct = primaryAccount?.remainingPercentageFor(modelId)
            val obj = JSONObject()
                .put("id", modelId)
                .put("object", "model")
                .put("name", modelDisplayName(modelId))
                .put("description", "Antigravity hosted $modelId")
            if (remainingPct != null) {
                obj.put("remaining_fraction", remainingPct / 100.0)
                obj.put("quota_percentage", "$remainingPct%")
            }
            modelsArray.put(obj)
        }

        val response = JSONObject()
            .put("object", "list")
            .put("data", modelsArray)
        writeJson(output, 200, response.toString())
    }

    private fun modelDisplayName(modelId: String): String = when (modelId) {
        "gemini-3.8-pro" -> "Gemini 3.8 Pro"
        "gemini-3.8-flash" -> "Gemini 3.8 Flash"
        "gemini-2.5-pro" -> "Gemini 2.5 Pro"
        "gemini-2.5-flash" -> "Gemini 2.5 Flash"
        "claude-3-7-sonnet" -> "Claude 3.7 Sonnet (Antigravity)"
        "claude-3-5-sonnet" -> "Claude 3.5 Sonnet (Antigravity)"
        else -> modelId
    }

    private fun handleInference(output: BufferedOutputStream, body: String, format: WireFormat) {
        val clientRequest = runCatching { JSONObject(body) }.getOrElse {
            val err = if (format == WireFormat.ANTHROPIC) {
                AntigravityProtocolAdapter.anthropicErrorJson("invalid_request_error", "Malformed JSON body")
            } else {
                AntigravityProtocolAdapter.openAiErrorJson("Malformed JSON body")
            }
            writeJson(output, 400, err)
            return
        }

        val stream = clientRequest.optBoolean("stream", false)
        val defaultModelId = targetModel()
        val geminiPayload = AntigravityProtocolAdapter.toGeminiRequest(clientRequest, format, defaultModelId)
        val model = geminiPayload.optString("model", defaultModelId)
        val upstreamPayload = AntigravityProtocolAdapter.toUpstreamPayload(geminiPayload)

        val excludedAccountIds = mutableSetOf<String>()
        var attempts = 0
        val maxAttempts = maxOf(accountManager.accountsList().size, 1)

        while (attempts < maxAttempts) {
            val account = accountManager.selectAccountForTurn(excludeAccountIds = excludedAccountIds)
            if (account == null) {
                val message = if (accountManager.accountsList().isEmpty()) {
                    "Google account not signed in. Sign in under Antigravity settings to use Antigravity models."
                } else {
                    "All Antigravity accounts have exhausted their quota. Please wait for cooldown or add another account."
                }
                val code = if (accountManager.accountsList().isEmpty()) 401 else 429
                val err = if (format == WireFormat.ANTHROPIC) {
                    AntigravityProtocolAdapter.anthropicErrorJson(if (code == 401) "authentication_error" else "rate_limit_error", message)
                } else {
                    AntigravityProtocolAdapter.openAiErrorJson(message, if (code == 401) "authentication_error" else "rate_limit_error", code)
                }
                writeJson(output, code, err)
                return
            }

            val token = accountManager.getAccountAccessToken(account.id)
            if (token.isNullOrBlank()) {
                accountManager.markAuthError(account.id, "No OAuth token available")
                excludedAccountIds.add(account.id)
                attempts++
                continue
            }
            val upstreamUrl = if (stream) {
                "$upstreamBaseUrl/v1internal:streamGenerateContent?alt=sse"
            } else {
                "$upstreamBaseUrl/v1internal:generateContent"
            }

            val headers = mapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json",
                "User-Agent" to "AntigravityCLI/1.1.27",
            )

            val httpResult = executeUpstream(upstreamUrl, "POST", headers, upstreamPayload.toString())
            when (httpResult.code) {
                in 200..299 -> {
                    accountManager.recordUsage(account.id)
                    if (stream) {
                        relayStreamingResponse(output, httpResult, format, model)
                    } else {
                        relayNonStreamingResponse(output, httpResult, format, model)
                    }
                    return
                }
                429 -> {
                    logW("Account ${account.email} hit 429 rate limit, rotating account...")
                    accountManager.markQuotaExhausted(account.id, model)
                    excludedAccountIds.add(account.id)
                    attempts++
                    continue
                }
                401, 403 -> {
                    logW("Account ${account.email} returned HTTP ${httpResult.code}, attempting token refresh...")
                    val refreshedToken = accountManager.getAccountAccessToken(account.id, forceRefresh = true)
                    if (refreshedToken != null && refreshedToken != token) {
                        val retryHeaders = headers + ("Authorization" to "Bearer $refreshedToken")
                        val retryResult = executeUpstream(upstreamUrl, "POST", retryHeaders, upstreamPayload.toString())
                        if (retryResult.code in 200..299) {
                            accountManager.recordUsage(account.id)
                            if (stream) {
                                relayStreamingResponse(output, retryResult, format, model)
                            } else {
                                relayNonStreamingResponse(output, retryResult, format, model)
                            }
                            return
                        }
                    }
                    logW("Account ${account.email} returned HTTP ${httpResult.code}, rotating account...")
                    accountManager.markAuthError(account.id, "Authentication expired (${httpResult.code})")
                    excludedAccountIds.add(account.id)
                    attempts++
                    continue
                }
                else -> {
                    logE("Upstream error HTTP ${httpResult.code}: ${httpResult.body}")
                    val err = if (format == WireFormat.ANTHROPIC) {
                        AntigravityProtocolAdapter.anthropicErrorJson("api_error", "Antigravity upstream returned HTTP ${httpResult.code}: ${httpResult.body.take(200)}")
                    } else {
                        AntigravityProtocolAdapter.openAiErrorJson("Antigravity upstream returned HTTP ${httpResult.code}: ${httpResult.body.take(200)}", "api_error", httpResult.code)
                    }
                    writeJson(output, httpResult.code, err)
                    return
                }
            }
        }

        // Exceeded all available accounts
        val message = "All Antigravity accounts encountered errors or exhausted quota."
        val err = if (format == WireFormat.ANTHROPIC) {
            AntigravityProtocolAdapter.anthropicErrorJson("rate_limit_error", message)
        } else {
            AntigravityProtocolAdapter.openAiErrorJson(message, "rate_limit_error", 429)
        }
        writeJson(output, 429, err)
    }

    private fun executeUpstream(urlStr: String, method: String, headers: Map<String, String>, body: String?): GatewayHttpResult {
        transportOverride?.let { override ->
            return override(urlStr, method, headers, body)
        }

        var lastError: Throwable? = null
        for (attempt in 0..1) {
            var currentConnection: HttpURLConnection? = null
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 15_000
                    readTimeout = 90_000
                    doOutput = body != null
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                currentConnection = conn
                upstreamConnections.add(conn)
                check(running.get()) { "Gateway closed" }
                if (body != null) {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                return if (code in 200..299) {
                    GatewayHttpResult(code, "", object : java.io.FilterInputStream(conn.inputStream) {
                        override fun close() {
                            try { super.close() }
                            finally { upstreamConnections.remove(conn); conn.disconnect() }
                        }
                    })
                } else {
                    val errBody = (conn.errorStream ?: conn.inputStream)?.use { readBoundedBody(it) }.orEmpty()
                    upstreamConnections.remove(conn)
                    conn.disconnect()
                    GatewayHttpResult(code, errBody)
                }
            } catch (t: Throwable) {
                currentConnection?.let { upstreamConnections.remove(it); it.disconnect() }
                lastError = t
                if (attempt == 0) {
                    runCatching { Thread.sleep(1000L) }
                    continue
                }
                break
            }
        }
        return GatewayHttpResult(502, lastError?.message ?: "Failed to connect to Antigravity upstream")
    }

    private fun relayStreamingResponse(output: BufferedOutputStream, httpResult: GatewayHttpResult, format: WireFormat, model: String) {
        output.write("HTTP/1.1 200 OK\r\n".toByteArray(Charsets.UTF_8))
        output.write("Content-Type: text/event-stream; charset=utf-8\r\n".toByteArray(Charsets.UTF_8))
        output.write("Cache-Control: no-cache\r\n".toByteArray(Charsets.UTF_8))
        output.write("Connection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
        output.flush()

        val msgId = (if (format == WireFormat.ANTHROPIC) "msg_" else "chatcmpl_") + UUID.randomUUID().toString().replace("-", "").take(16)
        var blockIndex = 0
        var activeBlockType: String? = null
        var totalOutputTokens = 0
        var hasToolCall = false
        var upstreamCompleted = false

        if (format == WireFormat.ANTHROPIC) {
            output.write(AntigravityProtocolAdapter.anthropicMessageStart(msgId, model).toByteArray(Charsets.UTF_8))
            output.flush()
        }

        val reader = BufferedReader(InputStreamReader(httpResult.stream ?: httpResult.body.byteInputStream(), Charsets.UTF_8))
        try {
            reader.use {
            while (true) {
                val rawLine = readBoundedStreamLine(reader) ?: break
                val line = rawLine.trim()
                if (!line.startsWith("data:")) continue
                val dataContent = line.removePrefix("data:").trim()
                if (dataContent == "[DONE]") { upstreamCompleted = true; continue }
                if (dataContent.isEmpty()) continue

                val geminiJson = runCatching { JSONObject(dataContent) }.getOrNull() ?: continue
                val effectiveJson = geminiJson.optJSONObject("response") ?: geminiJson
                if (effectiveJson.has("error")) error("Antigravity upstream returned an error")
                val candidates = effectiveJson.optJSONArray("candidates")
                for (index in 0 until (candidates?.length() ?: 0)) {
                    val finish = candidates?.optJSONObject(index)?.optString("finishReason").orEmpty()
                    if (finish == "STOP" || finish == "MAX_TOKENS") upstreamCompleted = true
                    else if (finish.isNotBlank()) error("Antigravity upstream stopped: $finish")
                }
                val parts = AntigravityProtocolAdapter.parseGeminiChunk(effectiveJson)

                for (part in parts) {
                    when (format) {
                        WireFormat.ANTHROPIC -> {
                            if (part.thinking != null) {
                                if (activeBlockType != "thinking") {
                                    if (activeBlockType != null) {
                                        output.write(AntigravityProtocolAdapter.anthropicContentBlockStop(blockIndex).toByteArray(Charsets.UTF_8))
                                        blockIndex++
                                    }
                                    output.write(AntigravityProtocolAdapter.anthropicContentBlockStart(blockIndex, "thinking").toByteArray(Charsets.UTF_8))
                                    activeBlockType = "thinking"
                                }
                                output.write(AntigravityProtocolAdapter.anthropicThinkingDelta(blockIndex, part.thinking).toByteArray(Charsets.UTF_8))
                                output.flush()
                            }
                            if (part.text != null) {
                                if (activeBlockType != "text") {
                                    if (activeBlockType != null) {
                                        output.write(AntigravityProtocolAdapter.anthropicContentBlockStop(blockIndex).toByteArray(Charsets.UTF_8))
                                        blockIndex++
                                    }
                                    output.write(AntigravityProtocolAdapter.anthropicContentBlockStart(blockIndex, "text").toByteArray(Charsets.UTF_8))
                                    activeBlockType = "text"
                                }
                                output.write(AntigravityProtocolAdapter.anthropicTextDelta(blockIndex, part.text).toByteArray(Charsets.UTF_8))
                                output.flush()
                            }
                            if (part.toolCall != null) {
                                hasToolCall = true
                                if (activeBlockType != null) {
                                    output.write(AntigravityProtocolAdapter.anthropicContentBlockStop(blockIndex).toByteArray(Charsets.UTF_8))
                                    blockIndex++
                                }
                                output.write(AntigravityProtocolAdapter.anthropicContentBlockStart(blockIndex, "tool_use", part.toolCall.id, part.toolCall.name).toByteArray(Charsets.UTF_8))
                                output.write(AntigravityProtocolAdapter.anthropicInputJsonDelta(blockIndex, part.toolCall.argsJson).toByteArray(Charsets.UTF_8))
                                output.write(AntigravityProtocolAdapter.anthropicContentBlockStop(blockIndex).toByteArray(Charsets.UTF_8))
                                blockIndex++
                                activeBlockType = null
                                output.flush()
                            }
                        }
                        WireFormat.OPENAI -> {
                            if (part.thinking != null) {
                                output.write(AntigravityProtocolAdapter.openAiChunk(msgId, model, reasoning = part.thinking).toByteArray(Charsets.UTF_8))
                                output.flush()
                            }
                            if (part.text != null) {
                                output.write(AntigravityProtocolAdapter.openAiChunk(msgId, model, content = part.text).toByteArray(Charsets.UTF_8))
                                output.flush()
                            }
                            if (part.toolCall != null) {
                                hasToolCall = true
                                output.write(AntigravityProtocolAdapter.openAiChunk(msgId, model, toolCall = part.toolCall).toByteArray(Charsets.UTF_8))
                                output.flush()
                            }
                        }
                    }
                }

                val usageMeta = effectiveJson.optJSONObject("usageMetadata") ?: geminiJson.optJSONObject("usageMetadata")
                usageMeta?.optInt("candidatesTokenCount")?.let {
                    totalOutputTokens = it
                }
            }
        }
    } catch (t: Throwable) {
        logW("Upstream streaming connection interrupted: ${t.message}")
        writeStreamError(output, format)
        return
    }
        if (!upstreamCompleted) {
            writeStreamError(output, format)
            return
        }

        when (format) {
            WireFormat.ANTHROPIC -> {
                if (activeBlockType != null) {
                    output.write(AntigravityProtocolAdapter.anthropicContentBlockStop(blockIndex).toByteArray(Charsets.UTF_8))
                }
                val stopReason = if (hasToolCall) "tool_use" else "end_turn"
                output.write(AntigravityProtocolAdapter.anthropicMessageDelta(stopReason, totalOutputTokens).toByteArray(Charsets.UTF_8))
                output.write(AntigravityProtocolAdapter.anthropicMessageStop().toByteArray(Charsets.UTF_8))
                output.flush()
            }
            WireFormat.OPENAI -> {
                val finish = if (hasToolCall) "tool_calls" else "stop"
                output.write(AntigravityProtocolAdapter.openAiChunk(msgId, model, finishReason = finish).toByteArray(Charsets.UTF_8))
                output.write(AntigravityProtocolAdapter.openAiDone().toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
    }

    private fun writeStreamError(output: BufferedOutputStream, format: WireFormat) {
        val error = JSONObject().put("type", "error").put("error", JSONObject()
            .put("type", "api_error").put("message", "Antigravity upstream stream interrupted before completion"))
        val prefix = if (format == WireFormat.ANTHROPIC) "event: error\n" else ""
        output.write("${prefix}data: $error\n\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun relayNonStreamingResponse(output: BufferedOutputStream, httpResult: GatewayHttpResult, format: WireFormat, model: String) {
        val rawBody = httpResult.stream?.use { readBoundedBody(it) } ?: httpResult.body
        val geminiJson = runCatching { JSONObject(rawBody) }.getOrElse {
            writeJson(output, 502, AntigravityProtocolAdapter.anthropicErrorJson("api_error", "Failed to parse upstream response"))
            return
        }

        val converted = when (format) {
            WireFormat.ANTHROPIC -> AntigravityProtocolAdapter.toAnthropicResponse(geminiJson, model)
            WireFormat.OPENAI -> AntigravityProtocolAdapter.toOpenAiResponse(geminiJson, model)
        }
        writeJson(output, 200, converted.toString())
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

    private fun writeUnauthorized(output: BufferedOutputStream, requestLine: String) {
        val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        val isChatCompletions = path.trimEnd('/').endsWith("/chat/completions")
        val errorBody = if (isChatCompletions) {
            AntigravityProtocolAdapter.openAiErrorJson("Missing or invalid authorization token", "authentication_error", 401)
        } else {
            AntigravityProtocolAdapter.anthropicErrorJson("authentication_error", "Missing or invalid authorization token")
        }
        writeJson(output, 401, errorBody)
    }

    private fun writeJson(output: BufferedOutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 $status ${httpStatusText(status)}\r\n".toByteArray(Charsets.UTF_8))
        output.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray(Charsets.UTF_8))
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray(Charsets.UTF_8))
        if (status == 401) {
            output.write("WWW-Authenticate: Bearer\r\n".toByteArray(Charsets.UTF_8))
        }
        output.write("Connection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun readBoundedBody(input: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() + count <= 16 * 1024 * 1024) { "Upstream response too large" }
            output.write(buffer, 0, count)
        }
        return output.toString("UTF-8")
    }

    private fun readBoundedStreamLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value < 0) return line.toString().takeIf { it.isNotEmpty() }
            if (value == '\n'.code) return line.toString()
            check(line.length < 1024 * 1024) { "Upstream stream line too large" }
            line.append(value.toChar())
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null // Prevent unbounded memory allocation
        }
    }

    private fun httpStatusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        else -> "Status $status"
    }

    private fun logW(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private fun logE(message: String, tr: Throwable? = null) {
        runCatching { Log.e(TAG, message, tr) }
    }
}
