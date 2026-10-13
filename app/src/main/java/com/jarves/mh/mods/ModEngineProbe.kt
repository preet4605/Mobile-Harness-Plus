package com.jarves.mh.mods

import android.content.Context
import android.os.Build
import android.webkit.WebView
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptConsoleCallback
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.MemoryLimitExceededException
import androidx.javascriptengine.Message
import androidx.javascriptengine.MessagePort
import androidx.javascriptengine.MessagePortClient
import androidx.javascriptengine.TerminationInfo
import com.google.common.util.concurrent.ListenableFuture
import com.jarves.mh.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One line of the engine test. [passed] is null for plain facts. */
data class EngineCheck(val group: String, val label: String, val value: String, val passed: Boolean? = null)

data class EngineReport(val checks: List<EngineCheck>, val ready: Boolean, val verdict: String, val finishedAt: Instant) {
    /** Plain text the user copies or shares back to the agent that builds the engine. */
    fun text(): String = buildString {
        appendLine("Mobile Harness engine test")
        appendLine("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · $finishedAt")
        appendLine("Verdict: $verdict")
        checks.groupBy { it.group }.forEach { (group, items) ->
            appendLine()
            appendLine(group)
            items.forEach { check ->
                val mark = when (check.passed) { true -> "ok"; false -> "FAIL"; null -> "-" }
                appendLine("  [$mark] ${check.label}: ${check.value}")
            }
        }
    }
}

/**
 * The engine test from spec v2 P3: can this phone run mod code in Jetpack JavaScriptSandbox, the
 * locked-down JavaScript process the system WebView provides, and how fast is it? It runs only
 * when the user taps Run, every step has a time limit, and the sandbox is closed at the end. A
 * test that can't be done safely on this phone (a stuck script it couldn't stop, memory it
 * couldn't cap) is skipped and reported, never attempted. No mod code runs here.
 *
 * The sandbox is one process for the whole app, and a mod that passes its memory cap takes that
 * process down with every other mod in it (documented for [IsolateStartupParameters.setMaxHeapSizeBytes]).
 * So the memory check runs last, passes when the greedy mod is named as the cause, and is followed
 * by a recovery check that restarts the sandbox and loads a mod again.
 */
class ModEngineProbe(context: Context) {
    private val appContext = context.applicationContext

    suspend fun run(onCheck: (EngineCheck) -> Unit = {}): EngineReport = withContext(Dispatchers.Default) {
        val checks = mutableListOf<EngineCheck>()
        fun add(group: String, label: String, value: String, passed: Boolean? = null) {
            EngineCheck(group, label, value, passed).also { checks += it; onCheck(it) }
        }
        add(PHONE, "Model", "${Build.MANUFACTURER} ${Build.MODEL}")
        add(PHONE, "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val webView = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        add(PHONE, "WebView", webView?.let { "${it.packageName} ${it.versionName}" } ?: "Unknown")

        val supported = runCatching { JavaScriptSandbox.isSupported() }.getOrDefault(false)
        add(SUPPORT, "Sandbox available", yesNo(supported), supported)
        if (!supported) return@withContext report(checks, emptySet(), passedTests = emptySet())

        val features = mutableSetOf<String>()
        val passedTests = mutableSetOf<String>()
        var sandbox: JavaScriptSandbox? = null
        val isolates = mutableListOf<JavaScriptIsolate>()
        fun isolate(params: IsolateStartupParameters? = null): JavaScriptIsolate =
            (params?.let { sandbox!!.createIsolate(it) } ?: sandbox!!.createIsolate()).also { isolates += it }
        fun closeIsolates() {
            isolates.forEach { runCatching { it.close() } }
            isolates.clear()
        }
        suspend fun step(group: String, label: String, timeoutMs: Long = STEP_TIMEOUT_MS, block: suspend () -> Pair<String, Boolean?>) {
            try {
                val (value, passed) = withTimeout(timeoutMs) { block() }
                add(group, label, value, passed)
                if (passed == true) passedTests += label
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                add(group, label, "No answer within ${timeoutMs / 1000} s", false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                add(group, label, describe(t), false)
            } finally {
                closeIsolates()
            }
        }

        try {
            step(SPEED, "Start the sandbox") {
                val ms = timed { sandbox = JavaScriptSandbox.createConnectedInstanceAsync(appContext).await() }
                "$ms ms" to true
            }
            val connected = sandbox ?: return@withContext report(checks, features, passedTests)
            FEATURES.forEach { (id, label) ->
                val has = runCatching { connected.isFeatureSupported(id) }.getOrDefault(false)
                if (has) features += id
                add(SUPPORT, label, yesNo(has), if (id in REQUIRED) has else null)
            }
            val promises = JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN in features

            step(SPEED, "First script in a new isolate") {
                val ms = timed { check(isolate().evaluateJavaScriptAsync("'ok'").await() == "ok") }
                "$ms ms" to true
            }
            step(SPEED, "Script round trip") {
                val engine = isolate()
                engine.evaluateJavaScriptAsync("'warm'").await()
                val samples = List(ROUND_TRIPS) { i -> timedNanos { engine.evaluateJavaScriptAsync("'$i'").await() } }
                "${stats(samples)} over $ROUND_TRIPS" to true
            }
            if (promises) {
                step(SPEED, "Mod call to the app and back") {
                    val engine = isolate()
                    check(engine.evaluateJavaScriptAsync(TRAMPOLINE_SHIM).await() == "ready")
                    var ops = 0
                    val ms = timed {
                        var message = engine.evaluateJavaScriptAsync("__probe.dispatch($TRAMPOLINE_OPS)").await()
                        while (message.startsWith("{\"op\"")) {
                            ops++
                            message = engine.evaluateJavaScriptAsync("__probe.resume(\"1\")").await()
                        }
                        check(message == "{\"done\":$TRAMPOLINE_OPS}") { "Unexpected result $message" }
                    }
                    String.format(Locale.US, "%.2f ms each (%d calls, %d ms)", ms.toDouble() / ops, ops, ms) to true
                }
            } else {
                add(SPEED, "Mod call to the app and back", "Skipped: scripts can't return promises here", false)
            }
            if (promises && JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS in features) {
                step(SPEED, "Message port round trip") {
                    val engine = isolate()
                    val port = AtomicReference<MessagePort>()
                    val replies = MessagePortClient { runCatching { port.get()?.postMessage(Message.createStringMessage("1")) } }
                    port.set(engine.createMessageChannel("probe", Runnable::run, replies))
                    var reported = ""
                    val ms = timed { reported = engine.evaluateJavaScriptAsync(PORT_SCRIPT).await() }
                    check(reported.toIntOrNull() != null) { "Unexpected result $reported" }
                    runCatching { port.get()?.close() }
                    String.format(Locale.US, "%.2f ms each (%d messages, %d ms)", ms.toDouble() / PORT_MESSAGES, PORT_MESSAGES, ms) to true
                }
            }
            for (kb in MODULE_SIZES_KB) {
                step(SPEED, "Load a $kb KB mod") {
                    val engine = isolate()
                    val script = moduleOfSize(kb * 1024)
                    var result = ""
                    val ms = timed { result = engine.evaluateJavaScriptAsync(script).await() }
                    check(result.toIntOrNull() != null) { "Unexpected result $result" }
                    "$ms ms" to true
                }
            }
            if (JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING in features) {
                step(SAFETY, "Mod log reaches the app") {
                    val engine = isolate()
                    val received = CompletableDeferred<String>()
                    engine.setConsoleCallback(Runnable::run, JavaScriptConsoleCallback { received.complete(it.message) })
                    engine.evaluateJavaScriptAsync("console.log('mh-probe'); 'ok'").await()
                    val text = withTimeoutOrNull(CONSOLE_WAIT_MS) { received.await() }
                    (if (text == null) "No log arrived" else yesNo(text == "mh-probe")) to (text == "mh-probe")
                }
            }
            // A stuck mod must stop without taking the others down; other isolates are checked while it spins.
            if (JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION in features) {
                step(SAFETY, "Stuck mod is stopped") {
                    val bystander = isolate()
                    val engine = isolate()
                    val pending = engine.evaluateJavaScriptAsync("while (true) {}")
                    delay(STUCK_RUN_MS)
                    val othersDuring = withTimeoutOrNull(CONSOLE_WAIT_MS) { bystander.evaluateJavaScriptAsync("'ok'").await() } == "ok"
                    val start = System.nanoTime()
                    engine.close()
                    val rejected = outcome { pending.await() }.isFailure
                    val ms = (System.nanoTime() - start) / 1_000_000
                    val othersAfter = withTimeoutOrNull(CONSOLE_WAIT_MS) { bystander.evaluateJavaScriptAsync("'ok'").await() } == "ok"
                    val detail = "Stopped in $ms ms; other mods ${if (othersDuring) "kept running" else "waited"} while it was stuck " +
                        "and ${if (othersAfter) "still work" else "stopped too"}"
                    detail to (rejected && othersAfter)
                }
            } else {
                add(SAFETY, "Stuck mod is stopped", "Skipped: a stuck script can't be stopped here", false)
            }
            step(SPEED, "Start $MAX_LOGIC_MODS mods at once") {
                val ms = timed {
                    List(MAX_LOGIC_MODS) { isolate() }
                        .map { it.evaluateJavaScriptAsync("'ok'") }
                        .forEach { check(it.await() == "ok") }
                }
                "$ms ms" to true
            }
            // Runs last: by design, a mod that passes its memory cap takes the whole sandbox down. The
            // greedy isolate is named as the cause and every other isolate reports the sandbox as dead.
            if (JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE in features) {
                step(SAFETY, "Memory cap stops a greedy mod", timeoutMs = MEMORY_TIMEOUT_MS) {
                    val bystander = isolate()
                    val bystanderEnded = CompletableDeferred<TerminationInfo>()
                    bystander.addOnTerminatedCallback(Runnable::run) { bystanderEnded.complete(it) }
                    check(bystander.evaluateJavaScriptAsync("'ok'").await() == "ok")
                    val engine = isolate(IsolateStartupParameters().apply { setMaxHeapSizeBytes(HEAP_LIMIT_BYTES) })
                    val terminated = CompletableDeferred<TerminationInfo>()
                    engine.addOnTerminatedCallback(Runnable::run) { terminated.complete(it) }
                    val start = System.nanoTime()
                    val failure = outcome { engine.evaluateJavaScriptAsync(GREEDY_SCRIPT).await() }.exceptionOrNull()
                        ?: error("The script finished instead of being stopped")
                    val ms = (System.nanoTime() - start) / 1_000_000
                    val info = withTimeoutOrNull(CONSOLE_WAIT_MS) { terminated.await() }
                    val blamed = failure is MemoryLimitExceededException || info?.status == TerminationInfo.STATUS_MEMORY_LIMIT_EXCEEDED
                    val others = withTimeoutOrNull(CONSOLE_WAIT_MS) { bystanderEnded.await() }
                    val othersText = when (others?.status) {
                        null -> "other mods kept running"
                        TerminationInfo.STATUS_SANDBOX_DEAD -> "the sandbox went down with it"
                        else -> "other mods ended: ${others.statusString}"
                    }
                    val detail = "${if (blamed) "Stopped and named as the cause" else "Ended with ${failure.javaClass.simpleName}"} " +
                        "after $ms ms; $othersText"
                    detail to blamed
                }
            } else {
                add(SAFETY, "Memory cap stops a greedy mod", "Skipped: memory can't be capped here", false)
            }
            step(SAFETY, RECOVER) {
                runCatching { sandbox?.close() }
                sandbox = null
                val ms = timed {
                    sandbox = JavaScriptSandbox.createConnectedInstanceAsync(appContext).await()
                    check(isolate().evaluateJavaScriptAsync(TRAMPOLINE_SHIM).await() == "ready")
                }
                "Sandbox back with a mod loaded in $ms ms" to true
            }
        } finally {
            closeIsolates()
            runCatching { sandbox?.close() }
        }
        report(checks, features, passedTests)
    }

    private fun report(checks: List<EngineCheck>, features: Set<String>, passedTests: Set<String>): EngineReport {
        val missing = buildList {
            if (checks.none { it.label == "Sandbox available" && it.passed == true }) add("the sandbox")
            REQUIRED.filter { it !in features }.forEach { add(FEATURES.getValue(it)) }
            REQUIRED_TESTS.filter { it !in passedTests }.forEach { add(it) }
        }.distinct()
        val ready = missing.isEmpty()
        val verdict = if (ready) "Ready for mod code" else "Not ready: ${missing.joinToString(", ") { it.lowercase() }}"
        return EngineReport(checks.toList(), ready, verdict, Instant.now())
    }

    companion object {
        private const val PHONE = "Phone"
        private const val SUPPORT = "Support"
        private const val SPEED = "Speed"
        private const val SAFETY = "Safety"
        private const val STEP_TIMEOUT_MS = 15_000L
        private const val MEMORY_TIMEOUT_MS = 30_000L
        private const val CONSOLE_WAIT_MS = 3_000L
        private const val STUCK_RUN_MS = 500L
        private const val ROUND_TRIPS = 100
        private const val TRAMPOLINE_OPS = 200
        private const val PORT_MESSAGES = 200
        private const val MAX_LOGIC_MODS = 8
        private const val HEAP_LIMIT_BYTES = 16L * 1024 * 1024
        private val MODULE_SIZES_KB = listOf(64, 512)

        /** Feature ids in the order shown, with the user-facing name. */
        private val FEATURES = linkedMapOf(
            JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN to "Scripts return promises",
            JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION to "Stop a running script",
            JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE to "Memory cap per mod",
            JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING to "Mod log",
            JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS to "Message ports",
            JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT to "Large scripts",
            JavaScriptSandbox.JS_FEATURE_EVALUATE_FROM_FD to "Scripts from files",
            JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER to "Binary data",
            JavaScriptSandbox.JS_FEATURE_WASM_COMPILATION to "WebAssembly",
        )

        /** What spec v2 §3.2 needs from the engine before mod code can run. */
        private val REQUIRED = setOf(
            JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN,
            JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION,
            JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE,
            JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING,
        )
        private const val RECOVER = "Recover after the sandbox goes down"
        private val REQUIRED_TESTS = listOf("Mod call to the app and back", "Stuck mod is stopped", "Memory cap stops a greedy mod", RECOVER)

        /**
         * The §3.2 trampoline in miniature: a hook that awaits [TRAMPOLINE_OPS] host calls. Each
         * `dispatch` or `resume` returns a promise for the next `{op}` request or the `{done}` result.
         */
        internal const val TRAMPOLINE_SHIM = """
globalThis.__probe = (function () {
  var waiter = null, opResolve = null;
  function settle(msg) { var w = waiter; waiter = null; if (w) w(JSON.stringify(msg)); }
  function next() { return new Promise(function (r) { waiter = r; }); }
  function hostOp(name, args) { return new Promise(function (r) { opResolve = r; settle({ op: name, args: args }); }); }
  async function hook(n) { var sum = 0; for (var i = 0; i < n; i++) sum += Number(await hostOp("fs.read", { i: i })); return sum; }
  return {
    dispatch: function (n) { var p = next(); hook(n).then(function (v) { settle({ done: v }); }, function (e) { settle({ error: String(e) }); }); return p; },
    resume: function (v) { var p = next(); var r = opResolve; opResolve = null; if (r) r(v); return p; }
  };
})();
"ready";
"""

        private const val PORT_SCRIPT = """
(async function () {
  const port = await android.getNamedPort("probe");
  const start = Date.now();
  let n = 0;
  return await new Promise(function (resolve) {
    port.onmessage = function () { n++; if (n >= $PORT_MESSAGES) resolve(String(Date.now() - start)); else port.postMessage("op" + n); };
    port.postMessage("op0");
  });
})()
"""

        private const val GREEDY_SCRIPT = "(function () { var keep = []; while (true) keep.push(new Array(100000).fill(keep.length)); })()"

        /** A script of about [bytes] made of small functions, like a bundled mod. */
        internal fun moduleOfSize(bytes: Int): String = buildString {
            append("var __mod = [];\n")
            var i = 0
            while (length < bytes) {
                append("__mod.push(function f").append(i).append("(e) { return { id: ").append(i)
                    .append(", text: 'handler ").append(i).append("', value: e && e.value } });\n")
                i++
            }
            append("String(__mod.length)")
        }

        private fun yesNo(value: Boolean) = if (value) "Yes" else "No"

        private fun describe(t: Throwable): String {
            val cause = if (t is ExecutionException) t.cause ?: t else t
            return "${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(160)}".trimEnd(':', ' ')
        }

        private fun stats(nanos: List<Long>): String {
            val sorted = nanos.sorted()
            fun at(q: Double) = sorted[((sorted.size - 1) * q).toInt()] / 1_000_000.0
            return String.format(Locale.US, "median %.2f ms, p95 %.2f ms", at(0.5), at(0.95))
        }

        /** Like runCatching, but a cancelled run still stops. */
        private inline fun <T> outcome(block: () -> T): Result<T> = try {
            Result.success(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }

        private inline fun timed(block: () -> Unit): Long {
            val start = System.nanoTime()
            block()
            return (System.nanoTime() - start) / 1_000_000
        }

        private inline fun timedNanos(block: () -> Unit): Long {
            val start = System.nanoTime()
            block()
            return System.nanoTime() - start
        }
    }
}

/** Waits for a future without blocking a thread; cancelling the wait cancels the future. */
private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
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
