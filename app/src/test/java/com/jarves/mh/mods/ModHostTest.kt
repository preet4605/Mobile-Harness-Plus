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
        val asks = CopyOnWriteArrayList<String>()
        override fun agents(): JSONArray = JSONArray().put(JSONObject().put("id", "codex").put("installed", true).put("current", true))
        override suspend fun askAgent(mod: InstalledMod, harness: AgentKind?, prompt: String, context: ModEventContext, timeoutMs: Long): JSONObject {
            asks += "${harness?.let(ModManifestParser::harnessId) ?: "current"}: $prompt"
            kotlinx.coroutines.delay(300) // the task runs in the app, outside the mod's own time
            return JSONObject().put("status", "completed").put("text", "done: $prompt").put("taskId", "t${asks.size}")
        }
        val followUps = CopyOnWriteArrayList<String>()
        override fun followUp(mod: InstalledMod, text: String, context: ModEventContext): Boolean {
            followUps += text
            return true
        }
        val undone = CopyOnWriteArrayList<String>()
        override fun pendingChanges(context: ModEventContext): JSONArray? =
            JSONArray().put(JSONObject().put("path", "src/a.kt")).put(JSONObject().put("path", "secrets/key.txt"))
        override suspend fun undoChange(context: ModEventContext, path: String): Boolean {
            undone += path
            return true
        }
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
        assertEquals(host.logText("dev.test.b"), ModRunState.BLOCKED, host.info("dev.test.b")?.state)

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

    private fun ModHost.drawing(surface: String, id: String = "dev.test.sample") = surfaces.value[id]?.drawings?.get(surface)

    private fun ModNode.all(): List<ModNode> = listOf(this) + children.flatMap { it.all() }

    private fun ModNode.texts(): String = all().filter { it.type == ModNode.TEXT }.joinToString("|") { it.text.orEmpty() }

    @Test
    fun `a dashboard draws, a tap runs its function, and atoms redraw it`() {
        val registry = registry()
        install(registry, """
            const count = atom('count', 0)
            export function register(on) {
              on('session.start', async (${'$'}, e, next) => { await ${'$'}.ui.open({ id: 'extra', title: 'More' }); return next(e) })
              on('ui.render', { component: 'Pane', requestId: 'main' }, (${'$'}, e) => {
                const { Column, Text, Button } = ${'$'}.ui.resolve(e)
                return h(Column, { gap: 12 },
                  h(Text, { style: 'title2' }, 'Pressed ' + read(${'$'}, count)),
                  [1, 2].map((n) => h(Text, { key: 'n' + n }, 'row ' + n)),
                  h(Button, { onPress: () => update(${'$'}, count, (n) => n + 1) }, 'Press'))
              })
              on('command.run', { name: 'bump' }, (${'$'}) => { update(${'$'}, count, (n) => n + 10); return { text: 'ok' } })
            }
        """.trimIndent(), permissions = listOf("ui.pane")) {
            put("dashboard", JSONObject().put("pane", "main"))
        }
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod to run") { host.info()?.state == ModRunState.RUNNING }
        waitFor("the opened pane") { host.surfaces.value["dev.test.sample"]?.panes?.map { it.id } == listOf("main", "extra") }
        assertEquals("Dashboard", host.surfaces.value["dev.test.sample"]?.panes?.first()?.title)

        // Nothing is drawn until something shows the pane.
        assertEquals(null, host.drawing("pane:main"))
        host.show("dev.test.sample", "pane:main", "test", true)
        waitFor("the first drawing") { host.drawing("pane:main")?.tree != null }
        val tree = host.drawing("pane:main")!!.tree!!
        assertEquals("Column", tree.type)
        assertEquals("Pressed 0|row 1|row 2|Press", tree.texts())
        val button = tree.all().first { it.type == "Button" }.handler("onPress")!!

        host.press("dev.test.sample", "pane:main", button, null)
        waitFor("the tap to redraw") { host.drawing("pane:main")?.tree?.texts()?.startsWith("Pressed 1") == true }

        // An atom the drawing read changes in another hook: the pane redraws by itself.
        kotlinx.coroutines.runBlocking { host.runCommand("dev.test.sample", "bump", "") }
        waitFor("the atom to redraw") { host.drawing("pane:main")?.tree?.texts()?.startsWith("Pressed 11") == true }

        // A pane with no hook for it draws nothing; hiding stops drawing.
        host.show("dev.test.sample", "pane:extra", "test", true)
        waitFor("the empty pane") { host.drawing("pane:extra")?.drawn == true }
        assertEquals(null, host.drawing("pane:extra")?.tree)
        host.show("dev.test.sample", "pane:main", "test", false)
    }

    @Test
    fun `the band needs its permission and switch, and bad trees are refused with a reason`() {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('ui.render', { component: 'AbovePrompt' }, () => h('Text', { style: 'footnote' }, 'band'))
              on('ui.render', { component: 'Pane' }, () => h('Blink', {}, 'no'))
            }
        """.trimIndent(), permissions = listOf("ui.band", "ui.pane"), granted = setOf(ModCapability.UI_PANE))
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod to run") { host.info()?.state == ModRunState.RUNNING }

        host.show("dev.test.sample", ModHost.BAND, "test", true)
        waitFor("the band to be off") { host.drawing(ModHost.BAND)?.message == "Off." }
        registry.setGranted("dev.test.sample", ModCapability.UI_BAND, true)
        registry.setShownIn("dev.test.sample", ModPlace.BAND, true)
        waitFor("the band") { host.drawing(ModHost.BAND)?.tree?.texts() == "band" }
        assertEquals(setOf(ModPlace.BAND), host.surfaces.value["dev.test.sample"]?.shownIn)

        host.show("dev.test.sample", "pane:x", "test", true)
        waitFor("the refusal") { host.drawing("pane:x")?.message != null }
        assertTrue(host.drawing("pane:x")?.message.orEmpty(), "\"Blink\" is not an element" in host.drawing("pane:x")?.message.orEmpty())
        assertTrue(host.logText(), "ui.render (Pane) refused" in host.logText())
    }

    @Test
    fun `run log reaches a mod only while its surface shows and it may watch`() {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('run.log', async (${'$'}, e) => { await ${'$'}.ui.status(e.lines.map((l) => l.kind + ':' + l.text).join('/')) })
              on('ui.render', () => h('Text', {}, 'x'))
            }
        """.trimIndent(), permissions = listOf("events.subscribe", "ui.pane", "ui.status"))
        val host = host(registry)
        host.updateContext(context, taskIsRunning = true)
        waitFor("the mod to run") { host.info()?.state == ModRunState.RUNNING && host.listens("run.log") }

        host.emitRunLog("reply", "hidden")
        Thread.sleep(1_500)
        assertEquals(null, host.info()?.status)

        host.show("dev.test.sample", "pane:main", "test", true)
        waitFor("the pane") { host.drawing("pane:main")?.tree != null }
        host.emitRunLog("reply", "Hel")
        host.emitRunLog("reply", "lo")
        host.emitRunLog("tool", "Bash: ls")
        waitFor("the batch") { host.info()?.status == "reply:Hello/tool:Bash: ls" }
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
            renderBudgetMs = 800,
            redrawGapMs = 50,
        )
    }

    @Test
    fun `a mod runs agent tasks one after another on chosen harnesses`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('command.run', { name: 'pipeline' }, async ($, e) => {
                const harnesses = (await $.agent.list()).map(a => a.id)
                const plan = await $.agent.ask({ harness: 'codex', prompt: 'Plan ' + e.args })
                const code = await $.agent.ask({ harness: 'antigravity', prompt: 'Build: ' + plan.text })
                let refused = ''
                try { await $.agent.ask({ harness: 'gpt', prompt: 'x' }) } catch (err) { refused = err.message }
                return { text: [harnesses.join(), plan.status, code.text, refused].join(' | ') }
              })
            }
        """.trimIndent(), permissions = listOf("agent.ask")) {
            put("contributes", JSONObject().put("slashCommands", JSONArray().put(JSONObject().put("name", "pipeline").put("description", "Run the pipeline"))))
        }
        val host = host(registry, LIMITS.copy(eventBudgetMs = 400))
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod to start") { host.info()?.state == ModRunState.RUNNING }
        // Two 300 ms tasks pass a 400 ms budget: only the mod's own time counts.
        val outcome = host.runCommand("dev.test.sample", "pipeline", "a todo app")
        assertEquals(
            ModCodeCommandOutcome.Reply("codex | completed | done: Build: done: Plan a todo app | \$.agent.ask: harness must be claude, deepseek, antigravity or codex"),
            outcome,
        )
        assertEquals(listOf("codex: Plan a todo app", "antigravity: Build: done: Plan a todo app"), effects.asks.toList())
        assertEquals(0, host.info()?.strikes)
        assertTrue(registry.snapshot.value.mods.first().let { mod -> registry.readAudit(mod.id).any { "agent.ask" in it.op } })
    }

    @Test
    fun `gates pass, rewrite, stop or ask, in mod order, and fail open`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('prompt.submit', ($, e, next) => e.text.includes('secret') ? { drop: 'No secrets, please' } : next({ ...e, text: e.text + '!', extra: 1 }))
              on('terminal.command', { command: /^rm / }, () => ({ confirm: 'This deletes files' }))
              on('terminal.command', { command: /^curl / }, () => ({ deny: 'No network from the terminal' }))
              on('terminal.command', { command: /^boom/ }, () => { throw new Error('bug') })
              on('files.op', ($, e, next) => next(e))
            }
        """.trimIndent(), id = "a.first", permissions = listOf("prompt.transform", "terminal.gate", "files.gate"))
        install(registry, """
            export function register(on) {
              on('prompt.submit', async ($, e, next) => { await $.clock.sleep(50); return next({ ...e, text: e.text.toUpperCase() }) })
              on('prompt.submit', async ($, e, next) => ({ ...(await next(e)), context: 'Project uses tabs' }))
            }
        """.trimIndent(), id = "b.second", permissions = listOf("prompt.transform"))
        install(registry, """
            export function register(on) {
              on('prompt.submit', () => ({ drop: 'never asked for the permission' }))
              on('prompt.compose', ($, e, next) => next({ ...e, sections: [...e.sections, 'Use British spelling.', 'x'.repeat(20000)] }))
            }
        """.trimIndent(), id = "c.third", permissions = listOf("prompt.compose"))
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("three mods to start") { host.snapshot.value.mods.values.count { it.state == ModRunState.RUNNING } == 3 }

        val pass = host.gate("prompt.submit", JSONObject().put("text", "hi"), ModCapability.PROMPT_TRANSFORM, setOf("drop"))
        assertEquals(null, pass.verdict)
        // a.first then b.second: the later mod sees the earlier one's rewrite; unknown fields are not kept.
        assertEquals("HI!", pass.input.getString("text"))
        assertFalse(pass.input.has("extra"))
        assertEquals(listOf(Triple("b.second", "Sample", "Project uses tabs")), pass.notes)
        // c.third lacks prompt.transform, so its drop never runs.
        val dropped = host.gate("prompt.submit", JSONObject().put("text", "my secret"), ModCapability.PROMPT_TRANSFORM, setOf("drop"))
        assertEquals("drop", dropped.verdict)
        assertEquals("No secrets, please", dropped.reason)
        assertEquals("a.first", dropped.modId)

        val verdicts = setOf("deny", "confirm")
        assertEquals("confirm", host.gate("terminal.command", JSONObject().put("command", "rm -rf build"), ModCapability.TERMINAL_GATE, verdicts).verdict)
        assertEquals("No network from the terminal", host.gate("terminal.command", JSONObject().put("command", "curl x"), ModCapability.TERMINAL_GATE, verdicts).reason)
        assertEquals(null, host.gate("terminal.command", JSONObject().put("command", "boom"), ModCapability.TERMINAL_GATE, verdicts).verdict)
        assertEquals(null, host.gate("terminal.command", JSONObject().put("command", "ls"), ModCapability.TERMINAL_GATE, verdicts).verdict)
        assertTrue(host.logText("a.first"), "terminal.command hook failed" in host.logText("a.first"))

        val composed = host.compose("Fix the colour", "codex")
        assertEquals(1, composed.notes.size)
        assertTrue(composed.notes[0].third.startsWith("Use British spelling."))
        assertTrue(composed.notes[0].third.toByteArray().size <= ModHost.MAX_SECTION_BYTES)
        // No hooks for an event: answered at once, unchanged.
        assertEquals(null, host.gate("build.start", JSONObject(), ModCapability.TERMINAL_GATE, verdicts).verdict)
    }

    @Test
    fun `run end follow-ups need prompt send`() {
        val registry = registry()
        val code = """
            export function register(on) {
              on('run.end', ($, e) => e.status === 'failed' ? { followUp: 'Try again: ' + e.error } : undefined)
            }
        """.trimIndent()
        install(registry, code, id = "a.retry", permissions = listOf("prompt.send"))
        install(registry, code, id = "b.nope", permissions = emptyList())
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("both mods to start") { host.snapshot.value.mods.values.count { it.state == ModRunState.RUNNING } == 2 }
        host.emit("run.end", JSONObject().put("status", "failed").put("error", "tests red"))
        waitFor("the follow-up") { effects.followUps.isNotEmpty() && "prompt.send permission" in host.logText("b.nope") }
        assertEquals(listOf("Try again: tests red"), effects.followUps.toList())
    }

    @Test
    fun `claude tool calls can be denied or rewritten but never approved, and undo stays in its scope`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('tool.call', { tool: 'Bash' }, ($, e, next) => {
                if (e.input.command.startsWith('rm ')) return { deny: 'No deleting' }
                return next({ ...e, input: { ...e.input, command: e.input.command.replace('npm ', 'pnpm ') } })
              })
              on('tool.approval', ($, e, next) => e.tool === 'Write' ? { deny: 'Read-only today' } : next(e))
              on('run.end', async ($) => {
                const changes = await $.changes.list()
                console.log('changes ' + changes.map(c => c.path).join(','))
                console.log('undid ' + await $.changes.undo('src/a.kt'))
                await $.changes.undo('secrets/key.txt').catch(err => console.log('refused ' + err.message))
              })
            }
        """.trimIndent(), id = "a.guard", permissions = listOf("tools.gate", "changes.read", "changes.undo")) {
            put("permissions", JSONArray(listOf(
                permission("tools.gate"),
                permission("changes.read"),
                permission("changes.undo").put("scope", JSONObject().put("paths", JSONArray().put("src/**"))),
            )))
        }
        install(registry, """
            export function register(on) {
              on('tool.approval', ($, e) => { console.log('heard ' + e.tool + ' ' + e.canGate); return { deny: 'not mine to say' } })
            }
        """.trimIndent(), id = "b.watch", permissions = emptyList())
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("both mods to start") { host.snapshot.value.mods.values.count { it.state == ModRunState.RUNNING } == 2 }
        // Claude's PreToolUse hook is installed only for the tools a mod matches.
        assertEquals("Bash", host.toolGateMatcher)

        fun bash(command: String) = JSONObject().put("command", command).put("timeout", 5)
        val denied = host.gateClaudeTool("Bash", bash("rm -rf build"), truncated = false, interactive = true)!!.getJSONObject("hookSpecificOutput")
        assertEquals("deny", denied.getString("permissionDecision"))
        assertEquals("Sample: No deleting", denied.getString("permissionDecisionReason"))
        // A rewrite asks the user when approvals are interactive; it never approves on its own.
        val rewritten = host.gateClaudeTool("Bash", bash("npm test"), truncated = false, interactive = true)!!.getJSONObject("hookSpecificOutput")
        assertEquals("ask", rewritten.getString("permissionDecision"))
        assertEquals("pnpm test", rewritten.getJSONObject("updatedInput").getString("command"))
        assertEquals(5, rewritten.getJSONObject("updatedInput").getInt("timeout"))
        assertEquals("allow", host.gateClaudeTool("Bash", bash("npm test"), truncated = false, interactive = false)!!
            .getJSONObject("hookSpecificOutput").getString("permissionDecision"))
        assertEquals(null, host.gateClaudeTool("Bash", bash("ls"), truncated = false, interactive = true))
        // An input cut short for the bridge can be denied but not rewritten.
        assertEquals(null, host.gateClaudeTool("Bash", bash("npm test"), truncated = true, interactive = true))
        assertEquals(null, host.gateClaudeTool("Read", JSONObject().put("file_path", "a.kt"), truncated = false, interactive = true))

        val write = host.gateApproval(JSONObject().put("tool", "Write").put("canGate", true))
        assertEquals("deny", write.verdict)
        assertEquals("Read-only today", write.reason)
        // b.watch lacks tools.gate: it hears of the request, and its answer counts for nothing.
        assertEquals(null, host.gateApproval(JSONObject().put("tool", "Edit").put("canGate", true)).verdict)
        waitFor("b.watch to hear") { "heard Edit false" in host.logText("b.watch") }

        host.emit("run.end", JSONObject().put("status", "completed"))
        waitFor("the undo calls") { "refused" in host.logText("a.guard") }
        val log = host.logText("a.guard")
        assertTrue(log, "changes src/a.kt,secrets/key.txt" in log)
        assertTrue(log, "undid true" in log)
        assertTrue(log, "secrets/key.txt is outside the mod's changes.undo scope" in log)
        assertEquals(listOf("src/a.kt"), effects.undone.toList())
    }
}
