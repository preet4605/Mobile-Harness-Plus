package com.jarves.mh.mods

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/** A program a mod keeps running in the guest with `$.runtime.serve`, as its page shows it. */
data class ModServer(
    val modId: String,
    val name: String,
    val port: Int,
    val command: String,
    val startedAt: Long,
    val running: Boolean,
    val exitCode: Int? = null,
    /** Why it stopped, when the app stopped it. */
    val note: String? = null,
)

/**
 * The guest servers mods started (mod plan row 20, `runtime`, Developer mode). At most
 * [MAX_PER_MOD] run per mod, each on its own port. Every server runs with [MARKER_ENV] naming it,
 * a CPU-time and data limit, and an output cap, so [stop] can find and kill everything it started
 * and a runaway server stops itself instead of loading the guest. Turning the mod off or Developer
 * mode off stops its servers ([stopMods]); so does the app's screen closing.
 */
class ModGuestServers(
    private val processes: File = File("/proc"),
    private val kill: (Int) -> Unit = { android.os.Process.sendSignal(it, 9) },
    private val portFree: (Int) -> Boolean = ::localPortFree,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(var info: ModServer, val process: Process, val log: File, var watcher: Job? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val _servers = MutableStateFlow<List<ModServer>>(emptyList())
    val servers: StateFlow<List<ModServer>> = _servers.asStateFlow()

    /** Throws with the reason a mod may not start server [name] on [port] now. */
    fun check(modId: String, name: String, port: Int) = synchronized(lock) {
        require(NAME.matches(name)) { "\$.runtime.serve: name is up to 32 lowercase letters, digits or dashes" }
        require(port in 1024..65535) { "\$.runtime.serve: port is 1024 to 65535" }
        val running = entries.values.filter { it.info.running }
        check(running.none { it.info.modId == modId && it.info.name == name }) { "Server $name is already running; stop it first" }
        check(running.count { it.info.modId == modId } < MAX_PER_MOD) { "A mod runs at most $MAX_PER_MOD servers" }
        check(running.none { it.info.port == port } && portFree(port)) { "Port $port is in use" }
    }

    /** The guest `/bin/sh` text that limits and runs [command] as server [name] of [modId], with `PORT` set. */
    fun script(modId: String, name: String, port: Int, command: String): String =
        "$MARKER_ENV=${quote("$modId/$name")}; export $MARKER_ENV; PORT=$port; export PORT; " +
            "ulimit -t $CPU_SECONDS 2>/dev/null; ulimit -d ${ModGuestTools.DATA_LIMIT_KB} 2>/dev/null; " +
            "exec /usr/bin/bash -lc ${quote(command)}"

    /** Tracks a server the app just launched; its output goes to [log]. */
    fun started(modId: String, name: String, port: Int, command: String, process: Process, log: File): ModServer = synchronized(lock) {
        val key = key(modId, name)
        entries.remove(key)?.log?.delete()
        val entry = Entry(ModServer(modId, name, port, command.take(500), clock(), running = true), process, log)
        entries[key] = entry
        dropOldStopped(modId)
        entry.watcher = scope.launch { watch(key, entry) }
        publish()
        entry.info
    }

    fun list(modId: String): List<ModServer> = synchronized(lock) { entries.values.map { it.info }.filter { it.modId == modId } }

    /** The end of server [name]'s output, at most [maxBytes]. */
    fun output(modId: String, name: String, maxBytes: Int = 4096): String {
        val log = synchronized(lock) { entries[key(modId, name)]?.log } ?: return ""
        return runCatching {
            java.io.RandomAccessFile(log, "r").use { file ->
                val start = (file.length() - maxBytes).coerceAtLeast(0)
                file.seek(start)
                val bytes = ByteArray((file.length() - start).toInt())
                file.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    /** Stops server [name] of [modId]; true when it was running. */
    fun stop(modId: String, name: String, note: String? = null): Boolean {
        val entry = synchronized(lock) { entries[key(modId, name)]?.takeIf { it.info.running } } ?: return false
        end(entry, note ?: "Stopped")
        return true
    }

    /** Stops every server of a mod not in [keep]. */
    fun stopMods(keep: Set<String>, note: String = "The mod was turned off or lost the runtime permission") {
        synchronized(lock) { entries.values.filter { it.info.running && it.info.modId !in keep } }.forEach { end(it, note) }
    }

    fun stopAll(note: String = "The app closed") = stopMods(emptySet(), note)

    private fun end(entry: Entry, note: String) {
        entry.watcher?.cancel()
        runCatching { entry.process.destroyForcibly() }
        killMarked("${entry.info.modId}/${entry.info.name}")
        synchronized(lock) {
            if (entry.info.running) entry.info = entry.info.copy(running = false, exitCode = -1, note = note)
            publish()
        }
    }

    private suspend fun watch(key: String, entry: Entry) {
        while (scope.isActive) {
            delay(WATCH_MS)
            if (!entry.process.isAlive) {
                // Whatever it left behind in the guest goes with it.
                killMarked("${entry.info.modId}/${entry.info.name}")
                synchronized(lock) {
                    if (entries[key] === entry && entry.info.running) {
                        entry.info = entry.info.copy(running = false, exitCode = runCatching { entry.process.exitValue() }.getOrNull())
                        publish()
                    }
                }
                return
            }
            if (entry.log.length() > MAX_LOG_BYTES) {
                end(entry, "It printed more than ${MAX_LOG_BYTES / (1024 * 1024)} MB")
                return
            }
        }
    }

    /** Kills every process whose environment names server [owner] (`<mod id>/<name>`). */
    private fun killMarked(owner: String) {
        val marker = "$MARKER_ENV=$owner"
        processes.listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val environ = runCatching { File(dir, "environ").readBytes() }.getOrNull() ?: return@forEach
            if (String(environ, Charsets.UTF_8).split('\u0000').any { it == marker }) runCatching { kill(pid) }
        }
    }

    private fun dropOldStopped(modId: String) {
        entries.entries.filter { it.value.info.modId == modId && !it.value.info.running }.dropLast(MAX_PER_MOD).forEach {
            it.value.log.delete()
            entries.remove(it.key)
        }
    }

    private fun publish() {
        _servers.value = entries.values.map { it.info }
    }

    private fun key(modId: String, name: String) = "$modId/$name"

    companion object {
        const val MAX_PER_MOD = 2
        const val MARKER_ENV = "MOD_SERVE"
        /** CPU seconds a server may use in all, like a mod's MCP server. */
        const val CPU_SECONDS = ModGuestTools.SERVER_CPU_SECONDS
        const val MAX_LOG_BYTES = 8L * 1024 * 1024
        private const val WATCH_MS = 1_000L
        val NAME = Regex("^[a-z0-9][a-z0-9-]{0,31}$")

        /** The app's servers. */
        val shared: ModGuestServers by lazy { ModGuestServers() }

        private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

        private fun localPortFree(port: Int): Boolean =
            runCatching { ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close() }.isSuccess
    }
}
