package com.jarves.mh.mods

import android.content.Context
import androidx.javascriptengine.EvaluationFailedException
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.Message
import androidx.javascriptengine.MessagePortClient
import androidx.javascriptengine.TerminationInfo
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Why an isolate ended without the host closing it. */
enum class ModTermination {
    /** It went over its memory cap. The engine takes the whole sandbox down with it. */
    MEMORY_LIMIT,
    /** The sandbox process died, for this isolate's sake or another's. */
    SANDBOX_DEAD,
    OTHER,
}

/** The phone can't run mod code; [message] says why, for the Mods screen. */
class ModEngineUnavailableException(message: String) : Exception(message)

/** The script threw; [message] is the engine's text. */
class ModScriptException(message: String) : Exception(message)

/** What an isolate tells the host. Called on any thread. */
interface ModIsolateListener {
    fun onMessage(text: String)
    fun onTerminated(reason: ModTermination, detail: String)
}

/** One mod's own JavaScript world: its own heap and globals. */
interface ModIsolate {
    /** Runs [script] and returns its result as text; a returned promise is awaited. Throws [ModScriptException]. */
    suspend fun evaluate(script: String): String

    /** Stops the isolate, including a script that is still running. */
    fun close()
}

/** The process mod code runs in (spec v2 §3). One per app; each mod gets its own isolate. */
interface ModEngine {
    /** Starts the engine. Throws [ModEngineUnavailableException] when this phone can't run mod code. */
    suspend fun connect()

    /** A new isolate with at most [heapBytes] of memory, with the message port "mh" ready for the shim. */
    suspend fun createIsolate(heapBytes: Long, listener: ModIsolateListener): ModIsolate

    /** Stops the engine and every isolate in it. */
    fun close()
}

/**
 * [ModEngine] on Jetpack JavaScriptSandbox, the locked-down JavaScript process the system WebView
 * provides (D1, tested on Love's phone in `audits/mod-engine-test-2026-10-13.md`).
 */
class JsSandboxEngine(context: Context) : ModEngine {
    private val appContext = context.applicationContext
    @Volatile private var sandbox: JavaScriptSandbox? = null

    override suspend fun connect() {
        if (sandbox != null) return
        if (!runCatching { JavaScriptSandbox.isSupported() }.getOrDefault(false)) {
            throw ModEngineUnavailableException("This phone's WebView can't run mod code. Update Android System WebView and try again.")
        }
        val connected = JavaScriptSandbox.createConnectedInstanceAsync(appContext).await()
        val missing = REQUIRED.filterNot { runCatching { connected.isFeatureSupported(it.first) }.getOrDefault(false) }
        if (missing.isNotEmpty()) {
            runCatching { connected.close() }
            throw ModEngineUnavailableException(
                "This phone's WebView is missing ${missing.joinToString { it.second }}, which mod code needs. Update Android System WebView.",
            )
        }
        sandbox = connected
    }

    override suspend fun createIsolate(heapBytes: Long, listener: ModIsolateListener): ModIsolate {
        val connected = sandbox ?: throw IllegalStateException("The engine is not running")
        val isolate = connected.createIsolate(IsolateStartupParameters().apply { setMaxHeapSizeBytes(heapBytes) })
        isolate.addOnTerminatedCallback(Runnable::run) { info ->
            val reason = when (info.status) {
                TerminationInfo.STATUS_MEMORY_LIMIT_EXCEEDED -> ModTermination.MEMORY_LIMIT
                TerminationInfo.STATUS_SANDBOX_DEAD -> ModTermination.SANDBOX_DEAD
                else -> ModTermination.OTHER
            }
            listener.onTerminated(reason, info.message.orEmpty())
        }
        isolate.createMessageChannel(PORT, Runnable::run, MessagePortClient { message ->
            if (message.type == Message.TYPE_STRING) message.string?.let(listener::onMessage)
        })
        return SandboxIsolate(isolate)
    }

    override fun close() {
        val current = sandbox ?: return
        sandbox = null
        runCatching { current.close() }
    }

    private class SandboxIsolate(private val isolate: JavaScriptIsolate) : ModIsolate {
        override suspend fun evaluate(script: String): String = try {
            isolate.evaluateJavaScriptAsync(script).await()
        } catch (e: EvaluationFailedException) {
            throw ModScriptException(e.message ?: "The script failed")
        }

        override fun close() {
            runCatching { isolate.close() }
        }
    }

    companion object {
        const val PORT = "mh"

        /** What the host needs (spec v2 §3.2), with the name the user sees. */
        private val REQUIRED = listOf(
            JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN to "promise results",
            JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION to "stopping a stuck script",
            JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE to "memory caps",
            JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS to "message ports",
            JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT to "large scripts",
        )
    }
}

/** Waits for a future without blocking a thread; cancelling the wait cancels the future. */
internal suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addListener({
        try {
            continuation.resume(get())
        } catch (e: ExecutionException) {
            continuation.resumeWithException(e.cause ?: e)
        } catch (t: Throwable) {
            continuation.resumeWithException(t)
        }
    }, Runnable::run)
    continuation.invokeOnCancellation { cancel(false) }
}
