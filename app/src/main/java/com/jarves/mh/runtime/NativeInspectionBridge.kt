package com.jarves.mh.runtime

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import android.os.FileObserver
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Tokens describe this launch's workspace and lifetime, never a caller-supplied project ID. */
internal class InspectionSessions {
    data class Scope(val workspace: File, val guestRoot: String, val isAlive: () -> Boolean)
    private val scopes = mutableMapOf<String, Scope>()
    @Volatile var onChange: (() -> Unit)? = null
    private fun alive(scope: Scope) = runCatching(scope.isAlive).getOrDefault(false)
    @Synchronized fun register(token: String, workspace: File, guestRoot: String, isAlive: () -> Boolean): Boolean {
        scopes.entries.removeAll { !alive(it.value) }
        if (scopes.size >= 64) return false
        scopes[token] = Scope(workspace.canonicalFile, guestRoot, isAlive)
        onChange?.invoke()
        return true
    }
    @Synchronized fun resolve(token: String): Scope? {
        val scope = scopes[token] ?: return null
        if (!alive(scope)) { scopes.remove(token); onChange?.invoke(); return null }
        return scope
    }
    @Synchronized fun snapshot(): Map<String, Scope> {
        scopes.entries.removeAll { !alive(it.value) }
        return scopes.toMap()
    }
    companion object { val shared = InspectionSessions() }
}

/** Fixed read-only operations over the existing private runtime bridge. */
internal class NativeInspectionBridge(
    private val context: Context,
    private val sessions: InspectionSessions = InspectionSessions.shared,
    private val preview: suspend (InspectionSessions.Scope, String) -> JSONObject = PreviewInspectionHost::inspect,
) {
    private val directory = File(context.filesDir.canonicalFile, "runtime-inspection")
    suspend fun watch() {
        watcher.lock()
        val events = Channel<Unit>(Channel.CONFLATED)
        val observers = mutableMapOf<String, FileObserver>()
        sessions.onChange = { events.trySend(Unit) }
        try {
            directory.mkdirs()
            events.trySend(Unit)
            for (event in events) {
                currentCoroutineContext().ensureActive()
                val active = sessions.snapshot()
                for (token in observers.keys.toList()) {
                    if (token !in active) {
                        observers.remove(token)?.stopWatching()
                        removeDirectory(File(directory, token))
                    }
                }
                // Old process tokens cannot survive Android process death. Only managed, expired
                // directories are removed; recent launches may still be registering their process.
                directory.listFiles().orEmpty().filter { it.name !in active &&
                    Regex("^[0-9a-f-]{36}$").matches(it.name) && System.currentTimeMillis() - it.lastModified() > 30_000
                }.forEach(::removeDirectory)
                for (token in active.keys) {
                    val scoped = File(directory, token)
                    if (!scoped.isDirectory || scoped.canonicalFile != scoped.absoluteFile) continue
                    if (token !in observers) {
                        val observer = object : FileObserver(scoped.absolutePath, CLOSE_WRITE or MOVED_TO) {
                            override fun onEvent(event: Int, path: String?) { events.trySend(Unit) }
                        }
                        observer.startWatching(); observers[token] = observer
                    }
                    Files.newDirectoryStream(scoped.toPath(), "*.request").use { files ->
                        for (path in files) handleFile(path.toFile())
                    }
                }
            }
        } finally {
            sessions.onChange = null
            observers.values.forEach { it.stopWatching() }
            events.close()
            watcher.unlock()
        }
    }
    private fun removeDirectory(file: File) {
        // NIO does not follow symlinks, including a replaced directory root.
        runCatching { Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(path); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(path: Path, error: java.io.IOException?): FileVisitResult {
                Files.deleteIfExists(path); return FileVisitResult.CONTINUE
            }
        }) }
    }

    internal suspend fun handleFile(file: File) {
        if (!Regex("^[0-9a-f-]{36}\\.request$").matches(file.name)) return
        val parent = file.parentFile ?: return
        if (parent.parentFile?.canonicalFile != directory.canonicalFile || parent.canonicalFile != parent.absoluteFile || Files.isSymbolicLink(file.toPath())) return
        val response = File(parent, file.name.removeSuffix(".request") + ".response")
        if (response.exists()) return
        var token = ""
        try {
            require(file.isFile && file.length() in 1..65_536 && System.currentTimeMillis() - file.lastModified() < 30_000)
            val request = JSONObject(file.inputStream().use { input ->
                val bytes = ByteArray(65_537)
                var count = 0
                while (count < bytes.size) { val read = input.read(bytes, count, bytes.size - count); if (read < 0) break; count += read }
                require(count <= 65_536)
                String(bytes, 0, count, Charsets.UTF_8)
            })
            token = request.getString("token")
            val scope = sessions.resolve(token) ?: error("Inspection launch has ended or is unavailable")
            require(parent.name == token) { "Inspection request belongs to another launch" }
            val operation = request.getString("operation")
            // A mod tool may wait on its own `$` calls; ModHost gives up on it first (toolWaitMs).
            val result = withTimeout(if (operation == "mod_tool") 30_000 else 8_000) {
                when (operation) {
                    "preview_dom", "preview_console", "preview_screenshot" -> preview(scope, operation)
                    "brain_search" -> {
                        val query = request.getJSONObject("args").getString("query")
                        require(query.length <= 256)
                        val base = File(context.filesDir, "workspaces").canonicalFile.toPath()
                        val workspace = scope.workspace.toPath()
                        require(workspace.startsWith(base) && workspace != base)
                        val projectId = base.relativize(workspace).getName(0).toString()
                        val entries = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context)
                            .brainKnowledgeRepository.search(projectId, query, 10)
                        val data = JSONArray(entries.map { entry -> JSONObject()
                            .put("key", entry.key.take(256)).put("content", entry.content.take(1_024))
                            .put("type", entry.knowledgeType.name).put("confidence", entry.confidence) })
                        JSONObject().put("ok", true).put("text", "Untrusted project Brain context: $data".take(12_000))
                    }
                    "mod_gate" -> {
                        // Claude's and DeepSeek's PreToolUse hook (mod spec v2 §7). Mods only decide: nothing
                        // runs here, and any failure answers with no decision, so the tool call goes on.
                        val args = request.getJSONObject("args")
                        val tool = args.getString("tool").take(128)
                        val input = args.optJSONObject("input") ?: JSONObject()
                        val truncated = args.optBoolean("truncated")
                        val deepSeek = args.optString("harness") == "deepseek"
                        val interactive = com.jarves.mh.data.AppPreferences(context).claudeInteractiveApprovals
                        val hook = com.jarves.mh.mods.ModHost.current.value?.let { host ->
                            kotlinx.coroutines.withTimeoutOrNull(7_000) {
                                runCatching {
                                    if (deepSeek) host.gateDeepSeekTool(tool, input, truncated) else host.gateClaudeTool(tool, input, truncated, interactive)
                                }.getOrNull()
                            }
                        }
                        JSONObject().put("ok", true).apply { if (hook != null) put("hook", hook) }
                    }
                    "mod_tools" -> {
                        // Tools running mods give the agents (mod spec v2 P8); none when no mod runs.
                        val tools = com.jarves.mh.mods.ModHost.current.value?.snapshot?.value?.tools.orEmpty()
                        JSONObject().put("ok", true).put("tools", JSONArray(tools.take(64).map { tool ->
                            JSONObject().put("name", tool.exposed).put("description", "${tool.description} (from the ${tool.modName} mod)")
                                .put("inputSchema", runCatching { JSONObject(tool.inputSchema) }.getOrElse { JSONObject().put("type", "object") })
                        }))
                    }
                    "mod_tool" -> {
                        val args = request.getJSONObject("args")
                        val name = args.getString("name").take(64)
                        val input = args.optJSONObject("input") ?: JSONObject()
                        val answer = com.jarves.mh.mods.ModHost.current.value?.callTool(name, input)
                            ?: com.jarves.mh.mods.ModToolResult("Mods aren't running right now.", isError = true)
                        JSONObject().put("ok", true).put("text", answer.text).put("isError", answer.isError)
                    }
                    else -> error("Unsupported inspection operation")
                }
            }
            check(sessions.resolve(token) === scope) { "Inspection launch has ended" }
            if (file.exists()) publish(response, result)
        } catch (cancelled: CancellationException) {
            // A per-request deadline is a tool error; lifecycle cancellation must still stop the watcher.
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) {
                if (file.exists()) publish(response, JSONObject().put("ok", false).put("error", "Inspection timed out"))
            } else throw cancelled
        } catch (_: Exception) {
            if (file.exists()) publish(response, JSONObject().put("ok", false).put("error", "Inspection unavailable, stale, or invalid; open this project's Preview and retry"))
        } finally {
            // Keep requests until the guest consumes the reply; deletion cancels an in-flight request.
            if (!file.exists()) response.delete()
        }
    }

    private fun publish(file: File, result: JSONObject) {
        val bytes = result.toString().toByteArray()
        if (bytes.size > 65_536) return publish(file, JSONObject().put("ok", false).put("error", "Inspection output exceeds its limit"))
        val staging = File(file.parentFile, "${file.name}.tmp-${UUID.randomUUID()}")
        runCatching {
            try { staging.writeBytes(bytes); check(staging.renameTo(file)) } finally { staging.delete() }
        }

    }
    companion object { private val watcher = Mutex() }
}
