package com.jarves.mh.mods

import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * [ModEngine] for JVM tests: isolates are vm contexts in a Node process (`node-engine.cjs`), so
 * the real shim and the real host run together. [kill] plays the engine dying the way
 * JavaScriptSandbox does when a mod passes its memory cap.
 */
class NodeModEngine : ModEngine {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val listeners = ConcurrentHashMap<Int, ModIsolateListener>()
    private val dead = ConcurrentHashMap.newKeySet<Int>()
    private val nextReq = AtomicLong(1)
    private val nextIso = AtomicInteger(1)
    val connects = AtomicInteger(0)
    @Volatile var failConnect: Exception? = null

    override suspend fun connect() {
        failConnect?.let { throw it }
        connects.incrementAndGet()
        val script = File(javaClass.getResource("/mods/node-engine.cjs")!!.toURI())
        val started = ProcessBuilder("node", script.path).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        process = started
        writer = started.outputStream.bufferedWriter()
        Thread {
            started.inputStream.bufferedReader().forEachLine { line ->
                val message = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                when (message.optString("t")) {
                    "result" -> pending.remove(message.optLong("req"))?.complete(message)
                    "msg" -> {
                        val iso = message.optInt("iso")
                        if (iso !in dead) listeners[iso]?.onMessage(message.optString("data"))
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    override suspend fun createIsolate(heapBytes: Long, listener: ModIsolateListener): ModIsolate {
        val iso = nextIso.getAndIncrement()
        listeners[iso] = listener
        request(JSONObject().put("t", "new").put("iso", iso))
        return NodeIsolate(iso)
    }

    /** The isolate [culprit] (1-based, in creation order) passes its memory cap: the whole engine dies. */
    fun kill(culprit: Int) {
        listeners.keys.filter { it !in dead }.sorted().forEach { iso ->
            dead += iso
            listeners[iso]?.onTerminated(if (iso == culprit) ModTermination.MEMORY_LIMIT else ModTermination.SANDBOX_DEAD, "test")
        }
    }

    /** Ids of isolates that are open now. */
    fun liveIsolates(): List<Int> = listeners.keys.filter { it !in dead }.sorted()

    override fun close() {
        listeners.keys.forEach { dead += it }
        runCatching { process?.destroy() }
        pending.values.forEach { it.completeExceptionally(IllegalStateException("engine closed")) }
        pending.clear()
    }

    private suspend fun request(body: JSONObject): JSONObject {
        val req = nextReq.getAndIncrement()
        val answer = CompletableDeferred<JSONObject>()
        pending[req] = answer
        synchronized(this) {
            val out = writer ?: throw IllegalStateException("not connected")
            out.write(body.put("req", req).toString())
            out.newLine()
            out.flush()
        }
        return answer.await()
    }

    private inner class NodeIsolate(private val iso: Int) : ModIsolate {
        override suspend fun evaluate(script: String): String {
            if (iso in dead) throw IllegalStateException("isolate terminated")
            val answer = request(JSONObject().put("t", "eval").put("iso", iso).put("code", script))
            if (iso in dead) throw IllegalStateException("isolate terminated")
            if (!answer.optBoolean("ok")) throw ModScriptException(answer.optString("value"))
            return answer.optString("value")
        }

        override fun close() {
            if (!dead.add(iso)) return
            listeners.remove(iso)
            runCatching {
                synchronized(this@NodeModEngine) {
                    writer?.apply { write(JSONObject().put("t", "close").put("iso", iso).put("req", 0).toString()); newLine(); flush() }
                }
            }
        }
    }
}
