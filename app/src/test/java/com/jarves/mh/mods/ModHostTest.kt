package com.jarves.mh.mods

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.brain.ExecutionPlan
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The mod host with the real shim, running mods in Node isolates (spec v2 §12: dispatch, budgets, `.catch`, faults). */
class ModHostTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val engines = CopyOnWriteArrayList<NodeModEngine>()
    private val hosts = mutableListOf<ModHost>()
    private val effects = Recorder()
    private val context = ModEventContext("p1", "Demo", null, "c1", AgentKind.CODEX)

    private class Recorder : ModHostEffects {
        val toasts = CopyOnWriteArrayList<String>()
        val posts = CopyOnWriteArrayList<String>()
        override fun toast(mod: InstalledMod, text: String) { toasts += text }
        override fun notify(mod: InstalledMod, title: String, body: String) = true
        override fun chatPost(mod: InstalledMod, text: String, context: ModEventContext) { posts += text }
        override fun promptDraft(mod: InstalledMod, text: String, context: ModEventContext) = true
        override fun chatMessages(context: ModEventContext, limit: Int) = listOf("user" to "hi", "agent" to "hello")
        override fun currentPlan(context: ModEventContext): ExecutionPlan? = null
    }

    @Before
    fun needsNode() {
        val node = runCatching { ProcessBuilder("node", "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
        assumeTrue("node is needed to run mod code in tests", node)
    }

    @After
    fun stop() {
        engines.forEach { it.close() }
    }

    private fun permission(capability: String) = JSONObject().put("capability", capability).put("reason", "test")

    private fun install(
        registry: ModRegistry,
        code: String,
        id: String = "dev.test.sample",
        name: String = "Sample",
        permissions: List<String> = listOf("storage", "ui.status", "ui.toast", "chat.read"),
        granted: Set<ModCapability> = permissions.mapNotNull(ModCapability::fromId).toSet(),
        extra: JSONObject.() -> Unit = {},
    ): InstalledMod {
        val files = mapOf("hooks/register.js" to code)
        val manifest = ModTestKit.manifest(files) {
            put("id", id)
            put("name", name)
            put("hooks", JSONObject().put("module", "hooks/register.js"))
            put("permissions", JSONArray(permissions.map(::permission)))
            extra()
        }
        val staged = registry.stageArchive(ByteArrayInputStream(ModTestKit.modZip(manifest, files)))
        return registry.install(staged, granted, ModTrust.IMPORTED, "test.mhmod")
    }

    private fun host(registry: ModRegistry, limits: ModHostLimits = LIMITS): ModHost =
        ModHost(
            registry = registry,
            engineFactory = { NodeModEngine().also { engines += it } },
            shimSource = { File("src/main/assets/mods/mh-shim.js").readText() },
            dataDir = registry::dataDir,
            limits = limits,
        ).also {
            it.effects = effects
            it.start()
            hosts += it
        }

    private fun registry() = ModRegistry(File(temp.root, "mods"), appVersionCode = 5)

    private fun waitFor(what: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun ModHost.info(id: String = "dev.test.sample") = snapshot.value.mods[id]
    private fun ModHost.logText(id: String = "dev.test.sample") = log(id).joinToString("\n") { "${it.level}: ${it.text}" }

    @Test
    fun `no mod code means no engine`() {
        val registry = registry()
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        Thread.sleep(300)
        assertTrue(engines.isEmpty())
        assertEquals(ModEngineState.OFF, host.snapshot.value.engine)
    }

    @Test
    fun `a mod starts, keeps saved data, registers and answers a command`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on, options) {
              on('session.start', async ($, e, next) => {
                const runs = ((await $.store.get('runs')) || 0) + 1
                await $.store.set('runs', runs)
                await $.ui.status('Run ' + runs + ' in ' + (e.project && e.project.name))
                await $.command.register({ name: 'hello', description: 'Say hello', argumentHint: '<name>' })
                console.log('started', options.greeting)
                return next(e)
              })
              on('command.run', { name: 'hello' }, ($, e) => ({ text: options.greeting + ', ' + e.args }))
              on('command.run', { name: 'ask' }, ($, e) => ({ prompt: 'Explain ' + e.args }))
            }
        """.trimIndent()) {
            put("contributes", JSONObject()
                .put("settings", JSONArray().put(JSONObject().put("key", "greeting").put("title", "Greeting").put("type", "string").put("default", "Hi")))
                .put("slashCommands", JSONArray().put(JSONObject().put("name", "ask").put("description", "Ask about something"))))
        }
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        // The status, the command and the log line arrive in that order, as separate messages.
        waitFor("session.start to finish") { host.info()?.status == "Run 1 in Demo" && "info: started Hi" in host.logText() }
        assertEquals(ModRunState.RUNNING, host.info()?.state)
        assertEquals(setOf("ask", "hello"), host.snapshot.value.commands.map { it.name }.toSet())
        assertEquals("<name>", host.snapshot.value.commands.first { it.name == "hello" }.argumentHint)
        assertTrue(host.logText(), "info: started Hi" in host.logText())
        assertEquals("1", File(registry.dataDir("dev.test.sample"), "store.json").readText().let { JSONObject(it).get("runs").toString() })

        assertEquals(ModCodeCommandOutcome.Reply("Hi, Love"), host.runCommand("dev.test.sample", "hello", "Love"))
        assertEquals(ModCodeCommandOutcome.Prompt("Explain this"), host.runCommand("dev.test.sample", "ask", "this"))
        assertTrue(host.runCommand("other.mod", "x", "") is ModCodeCommandOutcome.Failed)

        // Saved data outlives the engine: a new host (as after process death) sees run 1 and counts run 2.
        registry.setModsEnabled(false)
        waitFor("the engine to stop") { engines.all { it.liveIsolates().isEmpty() } }
        registry.setModsEnabled(true)
        waitFor("the second run") { host.info()?.status == "Run 2 in Demo" }
    }

    @Test
    fun `a failing hook is skipped, catch answers, and permissions are checked`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('command.run', ($, e, next) => { throw new Error('boom') })
              on('command.run', async ($, e, next) => {
                const r = await next(e)
                return { text: 'outer saw ' + JSON.stringify(r) }
              })
              on('command.run', ($, e, next) => { throw new Error('inner') })
                .catch(($, e, next, err) => ({ text: 'caught ' + err.message + ' called=' + next.called }))
              on('message.end', async ($, e) => {
                try { await $.ui.notify({ title: 't', body: 'b' }) } catch (err) { console.warn(err.message) }
                await $.ui.status('text: ' + (e.text === undefined ? 'hidden' : e.text))
              })
            }
        """.trimIndent(), permissions = listOf("ui.status", "chat.read"), granted = setOf(ModCapability.UI_STATUS))
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod to run") { host.info()?.state == ModRunState.RUNNING }

        val outcome = host.runCommand("dev.test.sample", "x", "")
        assertEquals(ModCodeCommandOutcome.Reply("outer saw {\"text\":\"caught inner called=false\"}"), outcome)
        waitFor("two strikes") { host.info()?.strikes == 2 }
        assertTrue(host.logText(), "boom" in host.logText())

        host.emit("message.end", JSONObject().put("text", "secret"), mapOf("text" to ModCapability.CHAT_READ))
        waitFor("the status") { host.info()?.status == "text: hidden" }
        assertTrue(host.logText(), "needs the notify permission, which this mod didn't ask for" in host.logText())
        assertEquals(ModRunState.RUNNING, host.info()?.state)
    }

    @Test
    fun `hooks past their budget are skipped and five strikes stop the mod until restart`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('tool.result', ($, e, next) => new Promise(() => {}))
              on('tool.call', async ($, e) => { await $.clock.sleep(1200); await $.ui.status('slept') })
            }
        """.trimIndent())
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod to run") { host.info()?.state == ModRunState.RUNNING }

        // Time spent in the app's own calls (a 1.2 s sleep) does not count against the 0.8 s budget.
        host.emit("tool.call", JSONObject().put("tool", "Bash"))
        waitFor("the status") { host.info()?.status == "slept" }
        assertEquals(0, host.info()?.strikes)

        repeat(ModHost.MAX_STRIKES) { host.emit("tool.result", JSONObject()) }
        waitFor("the crash") { host.info()?.state == ModRunState.CRASHED }
        assertTrue(host.info()?.message.orEmpty(), "Stopped after 5 problems" in host.info()?.message.orEmpty())
        assertTrue(engines.single().liveIsolates().isEmpty())

        host.restart("dev.test.sample")
        waitFor("the restart") { host.info()?.state == ModRunState.RUNNING }
        assertEquals(0, host.info()?.strikes)
    }

    @Test
    fun `a busy loop is stopped and the mod restarts`() = runBlocking {
        val registry = registry()
        install(registry, """
            let loops = 0
            export function register(on) {
              on('session.start', ($) => $.ui.status('up'))
              on('command.run', () => { while (true) {} })
            }
        """.trimIndent())
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod to run") { host.info()?.status == "up" }

        val outcome = host.runCommand("dev.test.sample", "spin", "")
        assertTrue(outcome is ModCodeCommandOutcome.Failed)
        assertTrue(host.logText(), "ran for more than" in host.logText())
        waitFor("the restart") { host.info()?.state == ModRunState.RUNNING && engines.single().liveIsolates().size == 1 }
        assertEquals(1, host.info()?.strikes)
    }

    @Test
    fun `a mod over its memory cap gets the strike and the others reload`() = runBlocking {
        val registry = registry()
        val code = """
            export function register(on) {
              on('session.start', async ($) => { update($, atom('n', 0), (n) => n + 1); await $.ui.status('n=' + read($, atom('n', 0))) })
            }
        """.trimIndent()
        install(registry, code, id = "dev.test.a", name = "A")
        install(registry, code, id = "dev.test.b", name = "B")
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("both mods") { host.info("dev.test.a")?.status == "n=1" && host.info("dev.test.b")?.status == "n=1" }
        val first = engines.single()
        val aIsolate = first.liveIsolates().first()

        first.kill(culprit = aIsolate)
        waitFor("a new engine with both mods") {
            engines.size == 2 && engines[1].liveIsolates().size == 2 &&
                host.info("dev.test.a")?.state == ModRunState.RUNNING && host.info("dev.test.b")?.state == ModRunState.RUNNING
        }
        assertEquals(1, host.info("dev.test.a")?.strikes)
        assertEquals(0, host.info("dev.test.b")?.strikes)
        assertTrue(host.logText("dev.test.b"), "loses its in-memory state" in host.logText("dev.test.b"))

        engines[1].kill(culprit = engines[1].liveIsolates().first())
        waitFor("a third engine") { engines.size == 3 && engines[2].liveIsolates().size == 2 }
        engines[2].kill(culprit = engines[2].liveIsolates().first())
        waitFor("mod code to pause") { host.snapshot.value.engine == ModEngineState.PAUSED }
        assertEquals(ModRunState.BLOCKED, host.info("dev.test.b")?.state)

        host.restart(null)
        waitFor("mod code to resume") { host.snapshot.value.engine == ModEngineState.ON && engines.size == 4 }
    }

    @Test
    fun `module errors and imports are reported, not retried`() {
        val registry = registry()
        install(registry, "import x from './other.js'\nexport function register(on) {}", id = "dev.test.imports")
        install(registry, "export function register(on) { on('session.start', () => {}) \n oops( }", id = "dev.test.syntax")
        install(registry, "export const notRegister = 1", id = "dev.test.empty")
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("all three to fail") { listOf("imports", "syntax", "empty").all { host.info("dev.test.$it")?.state == ModRunState.CRASHED } }
        assertTrue("can't import" in host.info("dev.test.imports")?.message.orEmpty())
        assertTrue(host.info("dev.test.syntax")?.message.orEmpty(), "SyntaxError" in host.info("dev.test.syntax")?.message.orEmpty())
        assertTrue("export a function named register" in host.info("dev.test.empty")?.message.orEmpty())
    }

    @Test
    fun `timers tick, stop with the engine, and the engine stops when nothing needs it`() {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('session.start', ($, e) => {
                if (!e.project) return
                let ticks = 0
                $.clock.every(1000, async ($) => { ticks++; await $.ui.status('tick ' + ticks) })
              })
            }
        """.trimIndent())
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("two ticks") { host.info()?.status == "tick 2" }

        host.updateContext(null, taskIsRunning = false)
        waitFor("the engine to stop") { host.snapshot.value.engine == ModEngineState.OFF }
        assertTrue(engines.single().liveIsolates().isEmpty())
        assertEquals(ModRunState.WAITING, host.info()?.state)
        assertFalse(host.listens("session.start"))
    }

    companion object {
        private val LIMITS = ModHostLimits(
            evalTimeoutMs = 500,
            settleCheckMs = 200,
            loadTimeoutMs = 5_000,
            stopGraceMs = 200,
            endBudgetMs = 300,
            eventBudgetMs = 800,
            quickBudgetMs = 300,
            backoffMs = listOf(100, 200, 300),
        )
    }
}
