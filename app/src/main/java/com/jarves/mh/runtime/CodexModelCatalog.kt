package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.model.AgentUsage
import com.jarves.mh.model.UsageLimit
import com.jarves.mh.model.usageResetText
import com.jarves.mh.model.usageWindowLabel
import com.jarves.mh.network.DiscoveredModel
import com.jarves.mh.network.ModelDiscoveryResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Reads Codex's model catalog from `codex app-server` (JSON-RPC over stdio). One short-lived process per refresh:
 * it is always destroyed and its capture file removed. Blocking, so call it from Dispatchers.IO.
 */
internal class CodexModelCatalog(private val context: Context) {
    private val installer = RuntimeInstaller(context)

    fun fetch(): ModelDiscoveryResult = withAppServer(
        failure = { ModelDiscoveryResult.Failure(it) },
    ) { process, output -> readCatalog(process, output) }

    /** Plan limits of the signed-in ChatGPT account. Blocking, so call from IO. Never throws. */
    fun fetchUsage(): AgentUsage = withAppServer(
        failure = { AgentUsage(note = it) },
    ) { process, output -> readUsage(process, output) }

    /** Starts one app-server process, runs [body], and always kills the process and removes its capture file. */
    private fun <T> withAppServer(failure: (String) -> T, body: (NativeSpawnProcess, File) -> T): T {
        if (!installer.isCodexInstalled()) {
            return failure("Codex is not installed. Open Settings → Coding agent and tap Install.")
        }
        val runtime = runCatching { installer.installedRuntime() }
            .getOrElse { return failure(it.message ?: "The core runtime is not ready.") }
        val workspace = File(context.filesDir, "workspaces/$WORKSPACE").apply { mkdirs() }
        val output = File(context.cacheDir, "codex-app-server-${System.nanoTime()}.log")
        val process = runCatching {
            installer.process(
                proot = runtime.proot,
                rootfs = runtime.rootfs,
                workspace = workspace,
                environment = CodexLaunchBuilder.environment(CodexRoute.ChatGptLogin(""), null),
                guestCommand = listOf(CodexLaunchBuilder.CODEX_GUEST_PATH, "app-server"),
                guestWorkspacePath = "/workspace/$WORKSPACE",
                emulateHardLinks = false,
                outputFile = output,
            ) as? NativeSpawnProcess
        }.getOrNull() ?: return failure("Codex could not start to read its data.")
        return try {
            body(process, output)
        } finally {
            runCatching { process.outputStream.close() }
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { output.delete() }
        }
    }

    private fun readUsage(process: NativeSpawnProcess, output: File): AgentUsage {
        val reader = CaptureLineReader(output)
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        val started = send(process, CodexAppServerProtocol.initializeRequest(INIT_ID)) &&
            send(process, CodexAppServerProtocol.initializedNotification()) &&
            send(process, CodexAppServerProtocol.rateLimitsRequest(LIST_ID_FIRST))
        if (!started) return AgentUsage(note = "Codex stopped before it reported usage.")
        while (true) {
            val alive = process.isAlive
            for (line in reader.readLines()) {
                val reply = CodexAppServerProtocol.parseReply(line) ?: continue
                if (reply.id != LIST_ID_FIRST) continue
                reply.errorMessage?.let { return CodexAppServerProtocol.usageError(it) }
                return CodexAppServerProtocol.parseRateLimits(reply.result ?: JSONObject())
            }
            if (!alive) return AgentUsage(note = "Codex stopped before it reported usage.")
            if (System.currentTimeMillis() >= deadline) {
                return AgentUsage(note = "Codex did not report usage within ${TIMEOUT_MS / 1000} seconds.")
            }
            Thread.sleep(POLL_MS)
        }
    }

    private fun readCatalog(process: NativeSpawnProcess, output: File): ModelDiscoveryResult {
        val reader = CaptureLineReader(output)
        val models = mutableListOf<DiscoveredModel>()
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var expectedId = LIST_ID_FIRST
        var pages = 0
        if (!send(process, CodexAppServerProtocol.initializeRequest(INIT_ID)) ||
            !send(process, CodexAppServerProtocol.initializedNotification()) ||
            !send(process, CodexAppServerProtocol.modelListRequest(expectedId, cursor = null))
        ) {
            return ModelDiscoveryResult.Failure("Codex stopped before it reported its models.")
        }
        while (true) {
            val alive = process.isAlive
            for (line in reader.readLines()) {
                val reply = CodexAppServerProtocol.parseReply(line) ?: continue
                if (reply.id != expectedId) continue
                reply.errorMessage?.let { return ModelDiscoveryResult.Failure(it) }
                val page = CodexAppServerProtocol.parseModelPage(reply.result ?: JSONObject())
                models += page.models
                pages += 1
                val cursor = page.nextCursor
                if (cursor == null || pages >= MAX_PAGES) {
                    return if (models.isEmpty()) {
                        ModelDiscoveryResult.Failure("Codex reported no models for this account.")
                    } else {
                        ModelDiscoveryResult.Success(models.distinctBy { it.id }, ENDPOINT_LABEL)
                    }
                }
                expectedId += 1
                if (!send(process, CodexAppServerProtocol.modelListRequest(expectedId, cursor))) {
                    return ModelDiscoveryResult.Failure("Codex stopped before it reported all its models.")
                }
            }
            if (!alive) {
                val lastLine = reader.text().lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
                val exitCode = runCatching { process.exitValue() }.getOrNull()
                return ModelDiscoveryResult.Failure(CodexFailureMessages.friendly(lastLine, exitCode))
            }
            if (System.currentTimeMillis() >= deadline) {
                return ModelDiscoveryResult.Failure(
                    "Codex did not return its model list within ${TIMEOUT_MS / 1000} seconds. The last saved list is still shown.",
                )
            }
            Thread.sleep(POLL_MS)
        }
    }

    private fun send(process: NativeSpawnProcess, line: String): Boolean = try {
        process.outputStream.apply {
            write(line.toByteArray(Charsets.UTF_8))
            flush()
        }
        true
    } catch (_: IOException) {
        false
    }

    /** Reads only the bytes appended since the last call and splits them on newlines. */
    private class CaptureLineReader(private val file: File) {
        private var offset = 0L
        private val pending = ByteArrayOutputStream()
        private val everything = ByteArrayOutputStream()

        fun readLines(): List<String> {
            val chunk = readNewBytes()
            if (chunk.isEmpty()) return emptyList()
            if (everything.size() < MAX_KEPT_BYTES) everything.write(chunk, 0, chunk.size.coerceAtMost(MAX_KEPT_BYTES - everything.size()))
            val lines = mutableListOf<String>()
            for (byte in chunk) {
                if (byte == NEWLINE) {
                    lines += String(pending.toByteArray(), Charsets.UTF_8)
                    pending.reset()
                } else {
                    pending.write(byte.toInt())
                }
            }
            return lines
        }

        fun text(): String = String(everything.toByteArray(), Charsets.UTF_8)

        private fun readNewBytes(): ByteArray {
            val available = file.length() - offset
            if (available <= 0) return ByteArray(0)
            val buffer = ByteArray(minOf(available, READ_CHUNK).toInt())
            val count = runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    raf.read(buffer)
                }
            }.getOrDefault(-1)
            if (count <= 0) return ByteArray(0)
            offset += count
            return buffer.copyOf(count)
        }
    }

    private companion object {
        const val WORKSPACE = "codex-models"
        const val ENDPOINT_LABEL = "Codex app-server"
        const val TIMEOUT_MS = 15_000L
        const val POLL_MS = 40L
        const val MAX_PAGES = 10
        const val INIT_ID = 1
        const val LIST_ID_FIRST = 2
        const val READ_CHUNK = 64L * 1024
        const val MAX_KEPT_BYTES = 64 * 1024
        const val NEWLINE: Byte = 10
    }
}

/**
 * JSON-RPC lines for `codex app-server`, and the parsing of its replies. Pure; no Android types.
 * Verified against Codex 0.161.0: `model/list` answers without a sign-in, and each model carries its
 * `supportedReasoningEfforts`.
 */
internal object CodexAppServerProtocol {

    /** A response to one of our requests. [errorMessage] is set when the server refused it. */
    data class Reply(val id: Int, val result: JSONObject?, val errorMessage: String?)

    data class ModelPage(val models: List<DiscoveredModel>, val nextCursor: String?)

    fun initializeRequest(id: Int): String = line(
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", "initialize")
            .put(
                "params",
                JSONObject().put("clientInfo", JSONObject().put("name", "mobile_harness").put("version", "1")),
            ),
    )

    fun initializedNotification(): String = line(JSONObject().put("jsonrpc", "2.0").put("method", "initialized"))

    fun rateLimitsRequest(id: Int): String = line(
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "account/rateLimits/read"),
    )

    /** Codex refuses the read without a ChatGPT sign-in; say what to do rather than echoing the server text. */
    fun usageError(message: String): AgentUsage = AgentUsage(
        note = if (message.contains("authentication", ignoreCase = true)) {
            "Usage needs ChatGPT sign-in. Sign in from Settings → Coding agent."
        } else {
            message.take(200)
        },
    )

    /** Limits from `account/rateLimits/read`: the primary and secondary windows, plus plan and credits when reported. */
    fun parseRateLimits(result: JSONObject): AgentUsage {
        val snapshot = result.optJSONObject("rateLimits")
        val limits = buildList {
            for (key in listOf("primary", "secondary")) {
                val window = snapshot?.optJSONObject(key) ?: continue
                if (window.isNull("usedPercent")) continue
                val minutes = if (window.isNull("windowDurationMins")) null else window.optLong("windowDurationMins")
                val resets = if (window.isNull("resetsAt")) null else window.optLong("resetsAt")
                add(UsageLimit(usageWindowLabel(minutes), "${window.optInt("usedPercent")}% used${usageResetText(resets)}"))
            }
        }
        val notes = mutableListOf<String>()
        val plan = snapshot?.takeIf { !it.isNull("planType") }?.optString("planType").orEmpty()
        if (plan.isNotBlank()) notes += "Plan: $plan"
        if (!result.isNull("ordinaryUsageAllowed") && !result.optBoolean("ordinaryUsageAllowed")) {
            notes += "Usage is paused for this account."
        }
        val credits = snapshot?.optJSONObject("credits")
        val balance = when {
            credits == null -> null
            credits.optBoolean("unlimited") -> "Credits: unlimited"
            credits.optBoolean("hasCredits") && !credits.isNull("balance") -> "Credits: ${credits.optString("balance")}"
            else -> null
        }
        return AgentUsage(
            limits = limits,
            balance = balance,
            note = notes.takeIf { it.isNotEmpty() }?.joinToString(" "),
        )
    }

    fun modelListRequest(id: Int, cursor: String?): String {
        val params = JSONObject()
        if (cursor != null) params.put("cursor", cursor)
        return line(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", "model/list")
                .put("params", params),
        )
    }

    /** Responses only. Startup banners, notifications and anything that is not a JSON object return null. */
    fun parseReply(raw: String): Reply? {
        val text = raw.trim()
        if (!text.startsWith("{")) return null
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (obj.has("method")) return null
        val id = obj.optInt("id", -1)
        if (id < 0) return null
        val error = obj.optJSONObject("error")
        val errorMessage = when {
            error != null -> error.optString("message").trim().ifBlank { "Codex returned an error." }.take(300)
            else -> null
        }
        return Reply(id = id, result = obj.optJSONObject("result"), errorMessage = errorMessage)
    }

    /** Hidden models are skipped. Effort levels keep the order Codex reports them in. */
    fun parseModelPage(result: JSONObject): ModelPage {
        val data = result.optJSONArray("data")
        val models = buildList {
            for (index in 0 until (data?.length() ?: 0)) {
                val obj = data?.optJSONObject(index) ?: continue
                if (obj.optBoolean("hidden", false)) continue
                val id = obj.optString("model").ifBlank { obj.optString("id") }.trim()
                if (id.isEmpty()) continue
                val efforts = obj.optJSONArray("supportedReasoningEfforts")
                val levels = buildList {
                    for (level in 0 until (efforts?.length() ?: 0)) {
                        efforts?.optJSONObject(level)?.optString("reasoningEffort")?.trim()
                            ?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                }
                add(
                    DiscoveredModel(
                        id = id,
                        displayName = obj.optString("displayName").ifBlank { id },
                        reasoningEfforts = levels,
                    ),
                )
            }
        }
        val nextCursor = if (result.isNull("nextCursor")) null else result.optString("nextCursor").ifBlank { null }
        return ModelPage(models, nextCursor)
    }

    private fun line(json: JSONObject): String = json.toString() + "\n"
}
