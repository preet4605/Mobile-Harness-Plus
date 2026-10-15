package com.jarves.mh.mods

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.brain.ExecutionPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val askOptions = CopyOnWriteArrayList<ModAskOptions>()
        override suspend fun askAgent(mod: InstalledMod, harness: AgentKind?, prompt: String, context: ModEventContext, timeoutMs: Long, options: ModAskOptions): JSONObject {
            asks += "${harness?.let(ModManifestParser::harnessId) ?: "current"}: $prompt"
            askOptions += options
            kotlinx.coroutines.delay(300) // the task runs in the app, outside the mod's own time
            return JSONObject().put("status", "completed").put("text", "done: $prompt").put("taskId", "t${asks.size}")
        }
        val stops = CopyOnWriteArrayList<String>()
        override fun stopAgent(mod: InstalledMod, taskId: String): Boolean {
            stops += taskId
            return taskId == "t1"
        }
        val followUps = CopyOnWriteArrayList<String>()
        val followUpLimits = CopyOnWriteArrayList<Int>()
        override fun followUp(mod: InstalledMod, text: String, context: ModEventContext, limit: Int): Boolean {
            followUps += text
            followUpLimits += limit
            return true
        }
        override suspend fun modelComplete(mod: InstalledMod, prompt: String, system: String?, maxTokens: Int): JSONObject =
            JSONObject().put("text", "hi").put("inputTokens", 200).put("outputTokens", 400).put("model", "test")
        val opened = CopyOnWriteArrayList<String>()
        override fun openUrl(mod: InstalledMod, url: String): Boolean { opened += url; return true }
        val undone = CopyOnWriteArrayList<String>()
        override fun pendingChanges(context: ModEventContext): JSONArray? =
            JSONArray().put(JSONObject().put("path", "src/a.kt")).put(JSONObject().put("path", "secrets/key.txt"))
        override suspend fun undoChange(context: ModEventContext, path: String): Boolean {
            undone += path
            return true
        }
        val commands = CopyOnWriteArrayList<ModGuestCommand>()
        override suspend fun guestRun(context: ModEventContext, command: ModGuestCommand): ModGuestResult {
            commands += command
            val argv = command.argv
            return when {
                "status" in argv -> ModGuestResult(0, "## main...origin/main [ahead 1]\n M src/a.kt\n?? new file.txt\n")
                "log" in argv -> ModGuestResult(0, "abc123\u001fLove\u001f1700000000\u001fFirst commit\n")
                "rev-parse" in argv -> ModGuestResult(0, "deadbeef\n")
                "diff" in argv -> ModGuestResult(0, "+added line")
                "main" in argv && "switch" in argv -> ModGuestResult(1, "error: Your local changes would be overwritten by checkout.\nAborting")
                argv.first() == "/usr/bin/bash" -> ModGuestResult(0, "hi\n")
                else -> ModGuestResult(0, "")
            }
        }
        val builds = CopyOnWriteArrayList<Boolean>()
        override suspend fun runBuild(mod: InstalledMod, context: ModEventContext, install: Boolean): JSONObject {
            builds += install
            return JSONObject().put("status", "completed").put("message", "Built")
        }
        override fun projectMemory(context: ModEventContext): JSONArray =
            JSONArray().put(JSONObject().put("key", "language").put("value", "Kotlin"))
        val device = CopyOnWriteArrayList<String>()
        var openProjectId: String = "p1"
        override fun projects(): JSONArray = JSONArray(listOf("p1" to "Demo", "p2" to "Other").map { (id, name) ->
            JSONObject().put("id", id).put("name", name).put("open", id == openProjectId)
        })
        override suspend fun openProject(id: String) {
            check(id == "p1" || id == "p2") { "No project $id." }
            device += "open $id"
        }
        override suspend fun createProject(name: String): String { device += "create $name"; return "p3" }
        override fun chats(context: ModEventContext): JSONArray? =
            JSONArray().put(JSONObject().put("id", "c1").put("title", "First").put("open", true)).put(JSONObject().put("id", "c2").put("title", "Second").put("open", false))
        override suspend fun openChat(context: ModEventContext, id: String) { device += "chat $id" }
        override suspend fun createChat(context: ModEventContext): String { device += "new chat"; return "c3" }
        override suspend fun readChat(context: ModEventContext, id: String, limit: Int): JSONArray? =
            if (id == "c2") JSONArray().put(JSONObject().put("from", "user").put("text", "older question")) else null
        var clip: String? = "copied"
        override suspend fun readClipboard(): String? = clip
        override suspend fun writeClipboard(mod: InstalledMod, text: String) { clip = text }
        override suspend fun share(mod: InstalledMod, context: ModEventContext?, text: String?, path: String?) { device += "share ${text ?: path}" }
        override suspend fun openIntent(mod: InstalledMod, uri: String) { device += "intent $uri" }
        override suspend fun pickFile(mod: InstalledMod, types: List<String>, maxBytes: Int): JSONObject? {
            device += "pick ${types.joinToString()}"
            return JSONObject().put("name", "notes.txt").put("mimeType", "text/plain").put("size", 5).put("text", "hello")
        }
        var freeBytes = 4L * 1024 * 1024 * 1024
        override fun guestFreeBytes(): Long = freeBytes
        val served = CopyOnWriteArrayList<String>()
        override suspend fun startServer(mod: InstalledMod, context: ModEventContext, name: String, port: Int, command: String): JSONObject {
            served += "$name:$port $command"
            return JSONObject().put("name", name).put("port", port).put("url", "http://127.0.0.1:$port")
        }
        override fun stopServer(modId: String, name: String): Boolean = served.removeIf { it.startsWith("$name:") }
        override fun servers(modId: String): JSONArray = JSONArray(served.map { JSONObject().put("name", it.substringBefore(':')).put("running", true) })
        val settings = java.util.concurrent.ConcurrentHashMap<String, Any>(mapOf("harness" to "claude", "model" to "sonnet", "effort" to "default",
            "theme" to "dark", "antigravityBalancing" to "round_robin", "antigravityFailover" to true, "codexAccountBalancing" to false))
        override fun appSettings(): JSONObject = JSONObject(settings as Map<*, *>)
        override suspend fun setAppSetting(key: String, value: Any) {
            check(!(key == "model" && value == "nope")) { "Claude Code doesn't offer the model nope." }
            settings[key] = value
        }
        val checkpointed = CopyOnWriteArrayList<String>()
        override suspend fun checkpointedWrite(context: ModEventContext, path: String, write: () -> Int): Int {
            // Like the app: the path is kept only once the write went through.
            return write().also { checkpointed += path }
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
        files: Map<String, String> = emptyMap(),
        extra: JSONObject.() -> Unit = {},
    ): InstalledMod {
        val files = files + ("hooks/register.js" to code)
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

    private val httpCalls = CopyOnWriteArrayList<ModHttpRequest>()
    private val secretValues = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val secretStore = object : ModSecretStore {
        override fun get(modId: String, key: String): String? = secretValues["$modId:$key"]
        override fun set(modId: String, key: String, value: String?) {
            if (value == null) secretValues.remove("$modId:$key") else secretValues["$modId:$key"] = value
        }
    }

    private fun host(registry: ModRegistry, limits: ModHostLimits = LIMITS): ModHost =
        ModHost(
            registry = registry,
            engineFactory = { NodeModEngine().also { engines += it } },
            shimSource = { File("src/main/assets/mods/mh-shim.js").readText() },
            dataDir = registry::dataDir,
            limits = limits,
            secrets = secretStore,
            http = { request ->
                httpCalls += request
                JSONObject().put("status", 200).put("ok", true).put("text", "pong")
            },
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
        waitFor("a third engine with both mods running") {
            engines.size == 3 && engines[2].liveIsolates().size == 2 &&
                host.info("dev.test.a")?.state == ModRunState.RUNNING && host.info("dev.test.b")?.state == ModRunState.RUNNING
        }
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
    fun `unloading a mod cancels its pending sleep jobs`() {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('tool.call', async ($) => {
                const sleeping = $.clock.sleep(600000)
                await $.ui.status('sleeping')
                await sleeping
              })
            }
        """.trimIndent())
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod to run") { host.info()?.state == ModRunState.RUNNING }
        // Observe coroutine ownership without adding production diagnostics solely for this test.
        val scope = ModHost::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(host) as CoroutineScope
        val owner = scope.coroutineContext[Job]!!
        val before = owner.children.toSet()
        host.emit("tool.call", JSONObject())
        waitFor("the sleeping hook") { host.info()?.status == "sleeping" && owner.children.count() > before.size }
        val pending = owner.children.filter { it !in before }.toList()
        assertTrue("Sleep must create pending host work", pending.isNotEmpty())
        registry.setEnabled("dev.test.sample", false)
        waitFor("the mod to unload") { engines.single().liveIsolates().isEmpty() }
        waitFor("pending sleep work to be canceled", timeoutMs = 2_000) { pending.none { it.isActive } }
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

    @Test
    fun `a mod gives the agents a tool and answers its calls`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('session.start', async ($, e, next) => {
                await $.tool.register({ name: 'lookup', description: 'Looks up a word', inputSchema: { type: 'object', properties: { word: { type: 'string' } } } })
                await $.tool.register({ name: 'broken', description: 'Always fails' })
                await $.tool.register({ name: 'Bad Name', description: 'x' }).catch(err => console.log('refused ' + err.message))
                return next(e)
              })
              on('tool.call', { tool: 'lookup' }, ($, e) => e.input.word === 'json' ? { meaning: 'data' } : { text: 'A ' + e.input.word + ' is a word' })
              on('tool.call', { tool: 'broken' }, () => { throw new Error('nope') })
            }
        """.trimIndent(), id = "dev.test.dict", permissions = listOf("tools.provide"))
        install(registry, """
            export function register(on) {
              on('session.start', async ($, e, next) => {
                await $.tool.register({ name: 'sneaky', description: 'x' }).catch(err => console.log('refused ' + err.message))
                return next(e)
              })
            }
        """.trimIndent(), id = "dev.test.nope", permissions = emptyList())
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("the tools") { host.snapshot.value.tools.size == 2 && "refused" in host.logText("dev.test.nope") }
        assertEquals(listOf("mod_dict_lookup", "mod_dict_broken"), host.snapshot.value.tools.map { it.exposed })
        assertTrue(host.logText("dev.test.dict"), "refused Tool name \"Bad Name\"" in host.logText("dev.test.dict"))
        assertEquals(ModToolResult("A cat is a word"), host.callTool("mod_dict_lookup", JSONObject().put("word", "cat")))
        assertEquals("{\"meaning\":\"data\"}", host.callTool("mod_dict_lookup", JSONObject().put("word", "json")).text)
        val broken = host.callTool("mod_dict_broken", JSONObject())
        assertTrue(broken.text, broken.isError && "nope" in broken.text)
        assertTrue(host.callTool("mod_dict_missing", JSONObject()).isError)
        assertTrue(registry.readAudit("dev.test.dict").any { it.op == "tool lookup" })
    }

    @Test
    fun `files, secrets and pages stay inside what the mod was given`() = runBlocking {
        val root = temp.newFolder("project")
        File(root, "src").mkdirs()
        File(root, "src/a.txt").writeText("alpha")
        File(root, "notes.md").writeText("keep")
        File(root, ".claude").mkdirs()
        File(root, ".claude/settings.json").writeText("{}")
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('run.end', async ($) => {
                const r = (p) => p.then(v => JSON.stringify(v), err => 'refused: ' + err.message)
                console.log('read ' + await r($.fs.read('src/a.txt')))
                console.log('list ' + await r($.fs.list('').then(l => l.map(f => f.path).join(','))))
                console.log('outside ' + await r($.fs.read('notes.md')))
                console.log('settings ' + await r($.fs.read('.claude/settings.json')))
                console.log('stat ' + await r($.fs.stat('src/missing.txt')))
                console.log('write ' + await r($.fs.write('src/out.txt', 'hello')))
                console.log('overwrite ' + await r($.fs.write('notes.md', 'x')))
                await $.secrets.set('token', 's3cret')
                console.log('secret ' + await $.secrets.get('token'))
                console.log('plain ' + await r($.browser.open('http://example.com')))
                console.log('page ' + await r($.browser.open('https://example.com/docs')))
                console.log('done')
              })
            }
        """.trimIndent(), permissions = listOf("files.read", "files.write", "secrets", "browser.open")) {
            put("permissions", JSONArray(listOf(
                permission("files.read").put("scope", JSONObject().put("paths", JSONArray().put("src/**"))),
                permission("files.write").put("scope", JSONObject().put("paths", JSONArray().put("src/out.txt"))),
                permission("secrets"),
                permission("browser.open"),
            )))
        }
        val host = host(registry)
        host.updateContext(ModEventContext("p1", "Demo", { root }, "c1", AgentKind.CODEX), taskIsRunning = false)
        waitFor("the mod to start") { host.info()?.state == ModRunState.RUNNING }
        host.emit("run.end", JSONObject().put("status", "completed"))
        waitFor("the calls") { "info: done" in host.logText() }
        val log = host.logText()
        assertTrue(log, "read \"alpha\"" in log)
        assertTrue(log, "list src\n" in log || "list \"src\"" in log)
        assertTrue(log, "outside refused: notes.md is outside the mod's files.read scope" in log)
        assertTrue(log, "settings refused:" in log)
        assertTrue(log, "stat null" in log)
        assertTrue(log, "write 5" in log)
        assertTrue(log, "overwrite refused: notes.md is outside the mod's files.write scope" in log)
        assertTrue(log, "secret s3cret" in log)
        assertTrue(log, "plain refused:" in log)
        assertTrue(log, "page true" in log)
        assertEquals("hello", File(root, "src/out.txt").readText())
        assertEquals(listOf("src/out.txt"), effects.checkpointed.toList())
        assertEquals("keep", File(root, "notes.md").readText())
        assertEquals("s3cret", secretValues["dev.test.sample:token"])
        assertEquals(listOf("https://example.com/docs"), effects.opened.toList())
    }

    @Test
    fun `web requests reach only named hosts and model calls stop at the daily limit`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('run.end', async ($) => {
                const r = (p) => p.then(v => JSON.stringify(v), err => 'refused: ' + err.message)
                console.log('api ' + await r($.http.fetch('https://api.example.com/v1/items?x=1', { method: 'POST', headers: { 'X-Key': 'k' }, body: { a: 1 } }).then(a => a.status + ' ' + a.text)))
                console.log('sub ' + await r($.http.fetch('https://cdn.example.org/a.js').then(a => a.status)))
                console.log('other ' + await r($.http.fetch('https://evil.test/')))
                console.log('plain ' + await r($.http.fetch('http://api.example.com/')))
                console.log('cookie ' + await r($.http.fetch('https://api.example.com/', { headers: { Cookie: 'a=b' } })))
                console.log('first ' + await r($.model.complete({ prompt: 'Say hi', maxTokens: 100 }).then(a => a.text + ' ' + a.usedToday)))
                console.log('second ' + await r($.model.complete({ prompt: 'x'.repeat(4000), maxTokens: 500 })))
                console.log('usage ' + await r($.model.usage()))
                console.log('done')
              })
            }
        """.trimIndent(), permissions = listOf("net.fetch", "model.complete")) {
            put("permissions", JSONArray(listOf(
                permission("net.fetch").put("scope", JSONObject().put("hosts", JSONArray().put("api.example.com").put("*.example.org"))),
                permission("model.complete"),
            )))
        }
        val host = host(registry, LIMITS.copy(modelTokensPerDay = 1_000))
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod to start") { host.info()?.state == ModRunState.RUNNING }
        host.emit("run.end", JSONObject().put("status", "completed"))
        waitFor("the calls") { "info: done" in host.logText() }
        val log = host.logText()
        assertTrue(log, "api \"200 pong\"" in log)
        assertTrue(log, "sub 200" in log)
        assertTrue(log, "other refused: evil.test is not one of the hosts" in log)
        assertTrue(log, "plain refused:" in log && "https:// URLs only" in log)
        assertTrue(log, "cookie refused: The Cookie header can't be set" in log)
        assertTrue(log, "first \"hi 600\"" in log)
        assertTrue(log, "second refused: Sample has used 600 of its 1000 model tokens today" in log)
        assertTrue(log, "\"usedToday\":600" in log && "\"dailyLimit\":1000" in log)
        assertEquals(2, httpCalls.size)
        assertEquals("POST", httpCalls[0].method)
        assertEquals("{\"a\":1}", httpCalls[0].body)
        assertEquals(mapOf("X-Key" to "k"), httpCalls[0].headers)
        assertTrue(File(registry.dataDir("dev.test.sample"), "model-usage.json").readText().contains("600"))
    }

    @Test
    fun `developer mode gates agent chains, and mods steer, stop and call each other`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('session.start', async ($, e, next) => {
                await $.tool.register({ name: 'shout', description: 'Upper-cases text' })
                return next(e)
              })
              on('tool.call', { tool: 'shout' }, ($, e) => ({ text: String(e.input.text).toUpperCase() }))
            }
        """.trimIndent(), id = "dev.test.loud", permissions = listOf("tools.provide"))
        install(registry, """
            export function register(on) {
              on('command.run', { name: 'go' }, async ($, e) => {
                const asked = await $.agent.ask({ harness: 'codex', prompt: 'Plan', model: 'gpt-5', effort: 'high', chat: 'new' })
                const stopped = await $.agent.stop(asked.taskId)
                const tools = (await $.mods.tools()).map(t => t.name).join(',')
                const loud = await $.mods.call('mod_loud_shout', { text: 'hi' })
                const missing = await $.mods.call('mod_loud_none', {})
                await $.prompt.send('keep going')
                return { text: [stopped, tools, loud.text, missing.isError].join(' | ') }
              })
            }
        """.trimIndent(), id = "dev.test.boss", permissions = listOf("agent.ask", "agent.chain", "mods.call", "prompt.send"))
        val boss = registry.snapshot.value.mods.first { it.id == "dev.test.boss" }
        // agent.chain is a developer permission: it can't be granted or used until Developer mode is on.
        assertFalse(ModCapability.AGENT_CHAIN in boss.granted)
        assertFalse(boss.canHold(ModCapability.AGENT_CHAIN))
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("both mods") { host.snapshot.value.mods.values.count { it.state == ModRunState.RUNNING } == 2 && host.snapshot.value.tools.size == 1 }
        assertTrue(host.toolsPossible)
        assertEquals(
            ModCodeCommandOutcome.Reply("true | mod_loud_shout | HI | true"),
            host.runCommand("dev.test.boss", "go", ""),
        )
        assertEquals(ModAskOptions(model = "gpt-5", effort = "high", chat = "new"), effects.askOptions.single())
        assertEquals(listOf("t1"), effects.stops.toList())
        assertEquals(listOf(ModHost.MAX_FOLLOW_UPS), effects.followUpLimits.toList())

        registry.setDeveloperMode(true)
        registry.setGranted("dev.test.boss", ModCapability.AGENT_CHAIN, true)
        waitFor("the grant") { registry.snapshot.value.mods.first { it.id == "dev.test.boss" }.let { ModCapability.AGENT_CHAIN in it.granted && it.developerMode } }
        Thread.sleep(200)
        host.runCommand("dev.test.boss", "go", "")
        assertEquals(listOf(ModHost.MAX_FOLLOW_UPS, ModHost.MAX_CHAIN_FOLLOW_UPS), effects.followUpLimits.toList())

        // Off again: the grant stays but stops working at once.
        registry.setDeveloperMode(false)
        Thread.sleep(200)
        host.runCommand("dev.test.boss", "go", "")
        assertEquals(ModHost.MAX_FOLLOW_UPS, effects.followUpLimits.last())
        assertTrue(ModCapability.AGENT_CHAIN in registry.snapshot.value.mods.first { it.id == "dev.test.boss" }.granted)
        assertTrue(registry.readAudit(null).any { it.op == "developer.on" })
    }

    @Test
    fun `git, terminal, builds and memory need their permissions, and the risky ones Developer mode`() = runBlocking {
        val registry = registry()
        registry.setDeveloperMode(true)
        install(registry, """
            export function register(on) {
              on('command.run', { name: 'dev' }, async ($) => {
                const r = (p) => p.then(v => JSON.stringify(v), err => 'refused: ' + err.message)
                const out = []
                out.push('status ' + await r($.git.status()))
                out.push('log ' + await r($.git.log(2)))
                out.push('diff ' + await r($.git.diff({ path: 'src', staged: true })))
                out.push('commit ' + await r($.git.commit('Add a thing')))
                out.push('badbranch ' + await r($.git.branch('../x')))
                out.push('branch ' + await r($.git.branch('feature/x')))
                out.push('switch ' + await r($.git.switch('main')))
                out.push('run ' + await r($.terminal.run('echo hi', { cwd: 'src', timeoutMs: 5000 })))
                out.push('escape ' + await r($.terminal.run('ls', { cwd: '../x' })))
                out.push('curl ' + await r($.terminal.run('curl example.com')))
                out.push('build ' + await r($.build.run({ install: true })))
                out.push('memory ' + await r($.memory.list()))
                return { text: out.join('\n') }
              })
            }
        """.trimIndent(), id = "dev.test.dev", permissions = listOf("git.read", "git.write", "terminal.run", "build.run", "memory.read"))
        install(registry, """
            export function register(on) {
              on('terminal.command', { command: /^curl / }, () => ({ deny: 'No network from the terminal' }))
              on('terminal.command', { command: /^echo / }, ($, e, next) => next({ ...e, command: e.command + ' there' }))
            }
        """.trimIndent(), id = "dev.test.guard", name = "Guard", permissions = listOf("terminal.gate"))
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("both mods") { host.snapshot.value.mods.values.count { it.state == ModRunState.RUNNING } == 2 }
        val text = (host.runCommand("dev.test.dev", "dev", "") as ModCodeCommandOutcome.Reply).text
        val lines = text.lines().associate { it.substringBefore(' ') to it.substringAfter(' ') }
        val status = JSONObject(lines.getValue("status"))
        assertEquals("main", status.getString("branch"))
        assertEquals("origin/main [ahead 1]", status.getString("tracking"))
        assertEquals(listOf("src/a.kt", "new file.txt"), (0 until 2).map { status.getJSONArray("files").getJSONObject(it).getString("path") })
        assertEquals("?", status.getJSONArray("files").getJSONObject(1).getString("index"))
        assertEquals("First commit", JSONArray(lines.getValue("log")).getJSONObject(0).getString("subject"))
        assertEquals(1_700_000_000_000L, JSONArray(lines.getValue("log")).getJSONObject(0).getLong("time"))
        assertEquals("+added line", JSONObject(lines.getValue("diff")).getString("text"))
        assertEquals("deadbeef", JSONObject(lines.getValue("commit")).getString("hash"))
        assertTrue(text, lines.getValue("badbranch").startsWith("refused: \$.git.branch(name)"))
        assertEquals("\"feature/x\"", lines.getValue("branch"))
        assertTrue(text, lines.getValue("switch").startsWith("refused: git switch failed: error: Your local changes"))
        assertEquals(0, JSONObject(lines.getValue("run")).getInt("exitCode"))
        assertTrue(text, lines.getValue("escape").startsWith("refused: \$.terminal.run: cwd"))
        assertTrue(text, lines.getValue("curl").startsWith("refused: Guard stopped the command: No network"))
        assertEquals("completed", JSONObject(lines.getValue("build")).getString("status"))
        assertEquals("Kotlin", JSONArray(lines.getValue("memory")).getJSONObject(0).getString("value"))

        val argvs = effects.commands.map { it.argv }
        assertEquals(listOf("git", "-c", "core.quotepath=off", "--no-pager", "add", "-A"), argvs.first { "add" in it })
        assertEquals(listOf("commit", "-m", "Add a thing"), argvs.first { "commit" in it }.takeLast(3))
        assertEquals(listOf("switch", "-c", "feature/x"), argvs.first { "feature/x" in it }.takeLast(3))
        assertTrue(argvs.none { "--force" in it || "-f" in it || "reset" in it || "push" in it })
        // The gate rewrote the command; the mod's command runs in its folder and alone.
        val shell = effects.commands.single { it.argv.first() == "/usr/bin/bash" }
        assertEquals("cd -- 'src' || exit 1\necho hi there", shell.argv.last())
        assertTrue(shell.exclusive)
        assertEquals(5_000L, shell.timeoutMs)
        assertEquals(listOf(true), effects.builds.toList())
        assertTrue(registry.readAudit("dev.test.dev").any { it.op == "git.commit" })

        // Developer mode off: reading still works, writing, running and building stop.
        registry.setDeveloperMode(false)
        Thread.sleep(200)
        val after = (host.runCommand("dev.test.dev", "dev", "") as ModCodeCommandOutcome.Reply).text.lines()
            .associate { it.substringBefore(' ') to it.substringAfter(' ') }
        assertFalse(after.getValue("status").startsWith("refused"))
        listOf("commit", "branch", "run", "build").forEach { assertTrue(it + after, after.getValue(it).startsWith("refused")) }
        assertEquals(1, effects.builds.size)
    }

    @Test
    fun `mods approve only with tools-approve inside the project, and DeepSeek and Codex gates only deny`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('tool.approval', ($, e, next) => String(e.command).startsWith('npm ') ? { approve: 'tests are fine' } : next(e))
              on('tool.call', ($, e, next) => {
                const command = String(e.input.command || '')
                if (command.startsWith('rm ')) return { deny: 'No deleting' }
                if (command === 'npm test') return next({ ...e, input: { ...e.input, command: 'pnpm test' } })
                return next(e)
              })
            }
        """.trimIndent(), id = "a.ok", name = "Approver", permissions = listOf("tools.gate", "tools.approve"))
        install(registry, """
            export function register(on) {
              on('tool.approval', ($, e, next) => e.command === 'npm publish' ? { deny: 'Not today' } : next(e))
            }
        """.trimIndent(), id = "b.no", name = "Guard", permissions = listOf("tools.gate"))
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("both mods") { host.snapshot.value.mods.values.count { it.state == ModRunState.RUNNING } == 2 }
        fun approval(command: String) = JSONObject().put("tool", "Bash").put("command", command).put("paths", JSONArray()).put("canGate", true)

        // tools.approve is a developer permission: with Developer mode off it was never granted.
        assertEquals(null, host.gateApproval(approval("npm test")) { true }.approvedBy)
        assertTrue(host.logText("a.ok"), "needs the tools.approve permission" in host.logText("a.ok"))
        registry.setDeveloperMode(true)
        registry.setGranted("a.ok", ModCapability.TOOLS_APPROVE, true)
        waitFor("the grant") { registry.snapshot.value.mods.first { it.id == "a.ok" }.let { ModCapability.TOOLS_APPROVE in it.granted && it.developerMode } }
        Thread.sleep(200)
        assertEquals("a.ok", host.gateApproval(approval("npm test")) { true }.approvedBy)
        // Outside the project folder the user decides, and a later mod's deny beats an approve.
        assertEquals(null, host.gateApproval(approval("npm test")) { false }.approvedBy)
        val publish = host.gateApproval(approval("npm publish")) { true }
        assertEquals("deny", publish.verdict)
        assertEquals(null, publish.approvedBy)
        val audit = registry.readAudit("a.ok")
        assertTrue(audit.any { it.op == "tool.approval" && it.decision == "approved" })
        assertTrue(audit.any { it.op == "tool.approval" && it.decision == "refused" })

        // DeepSeek can only be denied: a rewrite becomes a deny that says what the mod wants.
        fun bash(command: String) = JSONObject().put("command", command)
        val denied = host.gateDeepSeekTool("bash", bash("rm -rf build"), truncated = false)!!.getJSONObject("hookSpecificOutput")
        assertEquals("deny", denied.getString("permissionDecision"))
        assertEquals("Approver: No deleting", denied.getString("permissionDecisionReason"))
        val rewritten = host.gateDeepSeekTool("bash", bash("npm test"), truncated = false)!!.getJSONObject("hookSpecificOutput")
        assertEquals("deny", rewritten.getString("permissionDecision"))
        assertTrue(rewritten.getString("permissionDecisionReason"), "pnpm test" in rewritten.getString("permissionDecisionReason"))
        assertEquals(null, host.gateDeepSeekTool("bash", bash("ls"), truncated = false))

        // Codex: deny and rewrite decline; anything else runs. Its switch is per project and needs Developer mode.
        assertFalse(host.gateCodexTool("Bash", bash("rm -rf build")))
        assertFalse(host.gateCodexTool("Bash", bash("npm test")))
        assertTrue(host.gateCodexTool("Bash", bash("ls")))
        assertFalse(host.codexGateOn("p1"))
        registry.setCodexGate("p1", true)
        assertTrue(host.codexGateOn("p1"))
        assertFalse(host.codexGateOn("p2"))
        registry.setDeveloperMode(false)
        assertFalse(host.codexGateOn("p1"))
        assertTrue(registry.readAudit(null).any { it.op == "codex.gate.on" })
    }

    @Test
    fun `approvals count as inside the project only when every path and command stays there`() {
        val roots = listOf("/workspace/demo", "/data/files/workspaces/p1")
        assertTrue(ModApproval.inside(listOf("src/a.kt", "/workspace/demo/b.kt"), "npm test > /dev/null", roots))
        assertTrue(ModApproval.inside(emptyList(), "cat src/a.kt /workspace/demo/README.md", roots))
        assertTrue(ModApproval.inside(listOf("/data/files/workspaces/p1/x"), null, roots))
        assertFalse(ModApproval.inside(listOf("../other/a.kt"), null, roots))
        assertFalse(ModApproval.inside(listOf("/workspace/demo2/a.kt"), null, roots))
        assertFalse(ModApproval.inside(listOf("/etc/hosts"), null, roots))
        assertFalse(ModApproval.inside(emptyList(), "rm -rf /", roots))
        assertFalse(ModApproval.inside(emptyList(), "cat ~/.ssh/id_rsa", roots))
        assertFalse(ModApproval.inside(emptyList(), "cd \$HOME && ls", roots))
        assertFalse(ModApproval.inside(emptyList(), "ls `echo /`", roots))
        assertFalse(ModApproval.inside(emptyList(), "cd - && ls", roots))
        assertFalse(ModApproval.inside(emptyList(), "cat /workspace/demo/../x", roots))
        assertFalse(ModApproval.inside(emptyList(), "curl https://example.com/x", roots))
        assertFalse(ModApproval.inside(emptyList(), "ls", emptyList()))
    }

    @Test
    fun `a mod and its web panel talk only through the panel's messages`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('panel.message', async ($, e) => {
                const other = await $.ui.post('nope', 1).then(() => 'sent', () => 'refused')
                await $.ui.status(e.panel + ':' + e.data + ':' + other)
                await $.ui.post(e.panel, { echo: e.data })
              })
            }
        """.trimIndent(), permissions = listOf("ui.status", "ui.webpane"), files = mapOf("web/board.html" to "<p>hi</p>")) {
            put("contributes", JSONObject().put("panels", JSONArray().put(JSONObject().put("id", "board").put("title", "Board").put("entry", "web/board.html"))))
        }
        val host = host(registry)
        val posted = async(Dispatchers.Default) { withTimeout(8_000) { host.panelMessages.first() } }
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod to start") { host.info()?.state == ModRunState.RUNNING }
        Thread.sleep(100)
        host.panelMessage("dev.test.sample", "board", "ping")
        // A panel the mod doesn't have is refused.
        waitFor("the panel message") { host.info()?.status == "board:ping:refused" }
        assertEquals(ModPanelMessage("dev.test.sample", "board", "{\"echo\":\"ping\"}"), posted.await())

        // Without the permission the page's messages never reach the mod.
        registry.setGranted("dev.test.sample", ModCapability.UI_WEBPANE, false)
        waitFor("the grant to go") { ModCapability.UI_WEBPANE !in registry.snapshot.value.mods.single().granted }
        Thread.sleep(200)
        host.panelMessage("dev.test.sample", "board", "again")
        Thread.sleep(300)
        assertFalse(host.info()?.status.orEmpty().startsWith("board:again"))
    }

    @Test
    fun `two mods at most get 32 MB, only with engine-memory in Developer mode`() = runBlocking {
        val registry = registry()
        val code = "export function register(on) { on('session.start', async (\$) => { await \$.ui.status('up') }) }"
        listOf("dev.test.a", "dev.test.b", "dev.test.c").forEach { id ->
            install(registry, code, id = id, name = id.takeLast(1), permissions = listOf("ui.status", "engine.memory"))
        }
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        fun heaps() = engines.last().let { engine -> engine.liveIsolates().map { engine.heaps.getValue(it) } }
        val small = LIMITS.heapBytes
        val big = LIMITS.bigHeapBytes
        waitFor("three mods") { engines.isNotEmpty() && engines.last().liveIsolates().size == 3 && listOf("a", "b", "c").all { host.info("dev.test.$it")?.status == "up" } }
        // Installed with Developer mode off: the grant was never given.
        assertEquals(listOf(small, small, small), heaps())

        registry.setDeveloperMode(true)
        listOf("dev.test.a", "dev.test.b", "dev.test.c").forEach { registry.setGranted(it, ModCapability.ENGINE_MEMORY, true) }
        waitFor("a and b to reload with 32 MB") { heaps().sorted() == listOf(small, big, big) && listOf("a", "b").all { host.info("dev.test.$it")?.state == ModRunState.RUNNING } }
        waitFor("the log") { "It has 32 MB of memory." in host.logText("dev.test.a") }
        waitFor("c's log") { "only 2 mods may have 32 MB" in host.logText("dev.test.c") }

        // Developer mode off: back to 16 MB each.
        registry.setDeveloperMode(false)
        waitFor("everyone back at 16 MB") { heaps() == listOf(small, small, small) }
    }

    @Test
    fun `settings are read with settings-read and changed only in Developer mode, with undo`() = runBlocking {
        val registry = registry()
        install(registry, """
            const answer = async (f) => { try { return { text: String(await f()) } } catch (e) { return { text: 'ERR ' + e.message } } }
            export function register(on) {
              on('command.run', { name: 'read' }, ($) => answer(async () => (await $.settings.get('model')) + '|' + (await $.settings.get()).theme))
              on('command.run', { name: 'secret' }, ($) => answer(() => $.settings.get('apiKey')))
              on('command.run', { name: 'set' }, ($, e) => answer(async () => {
                const [key, value] = e.args.split('=')
                const r = await $.settings.set(key, value === 'true' ? true : value)
                return r.before + '>' + r.value + ':' + r.changed
              }))
              on('settings.change', async ($, e) => { await $.ui.status(e.key + '=' + e.value) })
              on('harness.switch', async ($, e) => { await $.ui.toast(e.from + '->' + e.to) })
            }
        """.trimIndent(), permissions = listOf("settings.read", "settings.write", "ui.status", "ui.toast")) {
            put("contributes", JSONObject().put("slashCommands", JSONArray(listOf("read", "secret", "set").map {
                JSONObject().put("name", it).put("description", "Test $it")
            })))
        }
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod") { host.info()?.state == ModRunState.RUNNING }
        assertEquals(ModCodeCommandOutcome.Reply("sonnet|dark"), host.runCommand("dev.test.sample", "read", ""))
        suspend fun reply(args: String, name: String = "set") = (host.runCommand("dev.test.sample", name, args) as ModCodeCommandOutcome.Reply).text
        assertTrue(reply("", "secret"), "API keys, sign-ins and the keystore never" in reply("", "secret"))

        // settings.write is a developer permission: refused until Developer mode is on and it is granted.
        assertTrue(reply("model=opus"), "settings.write" in reply("model=opus"))
        registry.setDeveloperMode(true)
        registry.setGranted("dev.test.sample", ModCapability.SETTINGS_WRITE, true)
        waitFor("the grant") { registry.snapshot.value.mods.single().let { ModCapability.SETTINGS_WRITE in it.granted } }
        Thread.sleep(200)
        assertEquals(ModCodeCommandOutcome.Reply("sonnet>opus:true"), host.runCommand("dev.test.sample", "set", "model=opus"))
        assertEquals(ModCodeCommandOutcome.Reply("opus>opus:false"), host.runCommand("dev.test.sample", "set", "model=opus"))
        assertEquals(ModCodeCommandOutcome.Reply("false>true:true"), host.runCommand("dev.test.sample", "set", "codexAccountBalancing=true"))
        assertTrue(reply("model=nope"), "doesn't offer the model nope" in reply("model=nope"))
        assertTrue(reply("theme=purple"), reply("theme=purple").startsWith("ERR"))
        assertTrue(reply("apiKey=x"), reply("apiKey=x").startsWith("ERR"))
        assertEquals("opus", effects.settings["model"])

        // Each change keeps an undo snapshot, kept across restarts; undo puts the old value back.
        val changes = registry.snapshot.value.settingChanges
        assertEquals(listOf("codexAccountBalancing", "model"), changes.map { it.key })
        assertEquals(listOf("model"), registry().snapshot.value.settingChanges.filter { it.before == "sonnet" }.map { it.key })
        assertEquals(null, host.undoSettingChange(changes.last().id))
        assertEquals("sonnet", effects.settings["model"])
        assertEquals(listOf("codexAccountBalancing"), registry.snapshot.value.settingChanges.map { it.key })
        assertTrue(registry.readAudit("dev.test.sample").any { it.op == "settings.undo" })

        // The app tells mods about changes, whoever made them; values only with settings.read.
        host.emit("settings.change", JSONObject().put("key", "theme").put("value", "light").put("before", "dark"),
            mapOf("value" to ModCapability.SETTINGS_READ, "before" to ModCapability.SETTINGS_READ))
        waitFor("settings.change") { host.info()?.status == "theme=light" }
        host.emit("harness.switch", JSONObject().put("from", "claude").put("to", "codex"))
        waitFor("harness.switch") { effects.toasts.any { it.endsWith("claude->codex") } }
    }

    @Test
    fun `app settings checks values and never knows secrets`() {
        assertEquals("codex", ModAppSettings.check("harness", "Codex"))
        assertEquals("round_robin", ModAppSettings.check("antigravityBalancing", "ROUND_ROBIN"))
        assertEquals(true, ModAppSettings.check("antigravityFailover", true))
        listOf("harness" to "gemini", "theme" to "blue", "antigravityFailover" to "yes", "effort" to "", "model" to "a\nb", "apiKey" to "x", "keystore" to "x")
            .forEach { (key, value) -> assertTrue("$key=$value", runCatching { ModAppSettings.check(key, value) }.isFailure) }
        assertFalse(ModAppSettings.KEYS.any { "key" in it.lowercase() || "token" in it.lowercase() || "account" == it })
    }

    @Test
    fun `a mod gives the app an accent while it is on and allowed, and no other mod clears it`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('command.run', { name: 'accent' }, async ($, e) => {
                try { await $.ui.theme({ accent: e.args || null }); return { text: 'ok' } } catch (error) { return { text: 'ERR ' + error.message } }
              })
            }
        """.trimIndent(), permissions = listOf("ui.theme")) {
            put("contributes", JSONObject().put("slashCommands", JSONArray().put(JSONObject().put("name", "accent").put("description", "Accent"))))
        }
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod") { host.info()?.state == ModRunState.RUNNING }
        suspend fun accent(name: String) = (host.runCommand("dev.test.sample", "accent", name) as ModCodeCommandOutcome.Reply).text
        assertEquals("ok", accent("purple"))
        assertEquals("purple", registry.snapshot.value.accent)
        assertEquals("purple", registry().snapshot.value.accent)
        assertTrue(accent("chartreuse"), accent("chartreuse").startsWith("ERR"))
        assertEquals("purple", registry.snapshot.value.accent)

        // Another mod can't clear it; turning the mod off or taking the permission gives the app its own back.
        registry.setThemeAccent("dev.test.other", null)
        assertEquals("purple", registry.snapshot.value.accent)
        registry.setGranted("dev.test.sample", ModCapability.UI_THEME, false)
        assertEquals(null, registry.snapshot.value.accent)
        registry.setGranted("dev.test.sample", ModCapability.UI_THEME, true)
        assertEquals("purple", registry.snapshot.value.accent)
        registry.setEnabled("dev.test.sample", false)
        assertEquals(null, registry.snapshot.value.accent)
        registry.setEnabled("dev.test.sample", true)
        waitFor("the mod again") { host.info()?.state == ModRunState.RUNNING }
        assertEquals("ok", accent(""))
        assertEquals(null, registry.snapshot.value.accent)
        assertTrue(registry.readAudit("dev.test.sample").any { it.op == "ui.theme" && it.detail == "cleared" })
    }

    @Test
    fun `projects, chats and device bridges need their permissions and never delete`() = runBlocking {
        val registry = registry()
        install(registry, """
            const answer = async (f) => { try { return { text: JSON.stringify(await f()) } } catch (e) { return { text: 'ERR ' + e.message } } }
            export function register(on) {
              on('command.run', { name: 'go' }, ($, e) => answer(async () => {
                switch (e.args) {
                  case 'projects': return (await $.projects.list()).map((p) => p.name + (p.open ? '*' : ''))
                  case 'open': return $.projects.open('p2')
                  case 'create': return $.projects.create({ name: 'From a mod' })
                  case 'delete': return $.projects.delete('p1')
                  case 'chats': return (await $.chats.list()).map((c) => c.title)
                  case 'chat': return $.chats.open('c2')
                  case 'newchat': return $.chats.create()
                  case 'read': return $.chats.read('c2', 5)
                  case 'readmissing': return $.chats.read('nope')
                  case 'clip': await $.clipboard.write('from mod'); return $.clipboard.read()
                  case 'share': return [await $.intent.share({ text: 'hi' }), await $.intent.share({ path: '../etc/passwd' }).catch((e) => 'refused')]
                  case 'link': return [await $.intent.open('mailto:me@example.com'), await $.intent.open('file:///sdcard/x').catch((e) => 'refused')]
                  case 'pick': return $.storage.pick({ types: ['text/*', 'bad type'] })
                }
              }))
            }
        """.trimIndent(), permissions = listOf("projects", "chat.read", "clipboard", "device.share", "storage.pick")) {
            put("contributes", JSONObject().put("slashCommands", JSONArray().put(JSONObject().put("name", "go").put("description", "Go"))))
        }
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod") { host.info()?.state == ModRunState.RUNNING }
        suspend fun go(what: String) = when (val outcome = host.runCommand("dev.test.sample", "go", what)) { is ModCodeCommandOutcome.Reply -> outcome.text; else -> "ERR $outcome" }
        assertEquals("[\"Demo*\",\"Other\"]", go("projects"))
        assertEquals("true", go("open"))
        go("create").let { assertTrue(it, "\"id\":\"p3\"" in it && "\"name\":\"From a mod\"" in it) }
        assertTrue(go("delete"), go("delete").startsWith("ERR"))
        assertEquals("[\"First\",\"Second\"]", go("chats"))
        assertEquals("true", go("chat"))
        assertEquals("{\"id\":\"c3\"}", go("newchat"))
        assertEquals("[{\"from\":\"user\",\"text\":\"older question\"}]", go("read"))
        assertTrue(go("readmissing"), "No chat nope" in go("readmissing"))
        assertEquals("\"from mod\"", go("clip"))
        assertEquals("[true,\"refused\"]", go("share"))
        assertEquals("[true,\"refused\"]", go("link"))
        go("pick").let { assertTrue(it, "\"text\":\"hello\"" in it) }
        assertEquals(listOf("open p2", "create From a mod", "chat c2", "new chat", "share hi", "intent mailto:me@example.com", "pick text/*"), effects.device.toList())
        assertTrue(registry.readAudit("dev.test.sample").any { it.op == "projects.create" })

        // In the background the clipboard can't be read (Android's rule), and nothing opens over other apps.
        // (A running task keeps mods loaded in the background.)
        host.updateContext(context, taskIsRunning = true)
        host.setForeground(false)
        Thread.sleep(200)
        go("clip").let { assertTrue(it, "only while they are on screen" in it) }
        host.setForeground(true)
        host.updateContext(context, taskIsRunning = false)

        // Without the permissions, nothing.
        listOf(ModCapability.PROJECTS, ModCapability.CLIPBOARD, ModCapability.STORAGE_PICK).forEach { registry.setGranted("dev.test.sample", it, false) }
        Thread.sleep(200)
        listOf("projects", "clip", "pick").forEach { what -> go(what).let { assertTrue(it, it.startsWith("ERR")) } }
    }

    @Test
    fun `guest runtime installs and serves only in Developer mode, with a storage check`() = runBlocking {
        val registry = registry()
        install(registry, """
            const answer = async (f) => { try { return { text: JSON.stringify(await f()) } } catch (e) { return { text: 'ERR ' + e.message } } }
            export function register(on) {
              on('command.run', { name: 'go' }, ($, e) => answer(async () => {
                switch (e.args) {
                  case 'apt': return (await $.runtime.install(['ripgrep', 'jq'])).exitCode
                  case 'pip': return (await $.runtime.install('requests==2.31.0', { manager: 'pip' })).exitCode
                  case 'bad': return $.runtime.install(['--allow-unauthenticated'])
                  case 'evil': return $.runtime.install(['jq; rm -rf /'])
                  case 'serve': return (await $.runtime.serve('python3 -m http.server ${'$'}PORT', 8080, { name: 'web', cwd: 'site' })).url
                  case 'list': return (await $.runtime.servers()).map((s) => s.name)
                  case 'stop': return $.runtime.stop('web')
                }
              }))
            }
        """.trimIndent(), permissions = listOf("runtime")) {
            put("contributes", JSONObject().put("slashCommands", JSONArray().put(JSONObject().put("name", "go").put("description", "Go"))))
        }
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod") { host.info()?.state == ModRunState.RUNNING }
        suspend fun go(what: String) = when (val outcome = host.runCommand("dev.test.sample", "go", what)) { is ModCodeCommandOutcome.Reply -> outcome.text; else -> "ERR $outcome" }

        // A developer permission: nothing until Developer mode is on and it is granted.
        go("apt").let { assertTrue(it, it.startsWith("ERR") && "runtime" in it) }
        registry.setDeveloperMode(true)
        registry.setGranted("dev.test.sample", ModCapability.RUNTIME, true)
        waitFor("the grant") { registry.snapshot.value.mods.single().let { ModCapability.RUNTIME in it.granted } }
        Thread.sleep(200)

        assertEquals("0", go("apt"))
        val apt = effects.commands.last()
        assertEquals(listOf("/usr/bin/bash", "-lc"), apt.argv.take(2))
        assertTrue(apt.argv[2], "apt-get install -y --no-install-recommends 'ripgrep' 'jq'" in apt.argv[2])
        assertTrue(apt.exclusive)
        assertEquals("0", go("pip"))
        assertTrue("python3 -m pip install" in effects.commands.last().argv[2])
        go("bad").let { assertTrue(it, it.startsWith("ERR") && "isn't a valid apt package name" in it) }
        go("evil").let { assertTrue(it, it.startsWith("ERR")) }
        effects.freeBytes = 512L * 1024 * 1024
        go("apt").let { assertTrue(it, "1 GB free" in it) }
        effects.freeBytes = 4L * 1024 * 1024 * 1024

        assertEquals("\"http://127.0.0.1:8080\"", go("serve"))
        assertEquals("web:8080 cd -- 'site' || exit 1\npython3 -m http.server ${'$'}PORT", effects.served.single())
        assertEquals("[\"web\"]", go("list"))
        assertEquals("true", go("stop"))
        assertEquals("false", go("stop"))
        assertTrue(registry.readAudit("dev.test.sample").map { it.op }.containsAll(listOf("runtime.install", "runtime.serve")))

        registry.setDeveloperMode(false)
        Thread.sleep(200)
        go("list").let { assertTrue(it, it.startsWith("ERR")) }
    }

    @Test
    fun `a schedule wakes the engine with the app closed and runs only while allowed`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('schedule.run', async ($, e) => { await $.store.set('ran', e.schedule + '/' + e.everyMinutes) })
            }
        """.trimIndent(), permissions = listOf("schedules", "storage")) {
            put("contributes", JSONObject().put("schedules", JSONArray().put(JSONObject().put("id", "hourly").put("everyMinutes", 60))))
        }
        val host = host(registry)
        // No project, no screen, in the background: the engine is off.
        host.setForeground(false)
        Thread.sleep(200)
        assertTrue(engines.isEmpty())
        assertFalse(host.runSchedule("dev.test.sample", "hourly"))

        registry.setDeveloperMode(true)
        registry.setGranted("dev.test.sample", ModCapability.SCHEDULES, true)
        waitFor("the grant") { registry.snapshot.value.mods.single().let { ModCapability.SCHEDULES in it.granted } }
        Thread.sleep(200)
        assertEquals(listOf(ModSchedules.Wanted("dev.test.sample", "hourly", 60)), ModSchedules.wanted(registry.snapshot.value))
        assertFalse(host.runSchedule("dev.test.sample", "nope"))
        assertTrue(host.runSchedule("dev.test.sample", "hourly"))
        assertEquals("hourly/60", ModStore(registry.dataDir("dev.test.sample")).get("ran"))
        assertTrue(registry.readAudit("dev.test.sample").any { it.op == "schedule.run" && it.detail == "hourly" })
        // Nothing keeps it running afterwards.
        waitFor("the engine to stop") { host.snapshot.value.engine == ModEngineState.OFF }

        registry.setDeveloperMode(false)
        Thread.sleep(200)
        assertTrue(ModSchedules.wanted(registry.snapshot.value).isEmpty())
        assertFalse(host.runSchedule("dev.test.sample", "hourly"))
    }

    @Test
    fun `runtime installs take plain package names only`() {
        assertTrue("'ripgrep' 'python3.10' 'libc6=2.31-0ubuntu9'" in ModRuntime.installScript("apt", listOf("ripgrep", "python3.10", "libc6=2.31-0ubuntu9")))
        assertTrue("'requests[socks]>=2.0' 'Flask'" in ModRuntime.installScript("pip", listOf("requests[socks]>=2.0", "Flask")))
        assertTrue("'@angular/cli@17.1.0' 'typescript'" in ModRuntime.installScript("npm", listOf("@angular/cli@17.1.0", "typescript")))
        listOf("-y", "--force", "a b", "a;b", "a'b", "\$(id)", "a|b", "../x", "", "a\nb").forEach { name ->
            listOf("apt", "pip", "npm").forEach { manager ->
                assertTrue("$manager $name", runCatching { ModRuntime.installScript(manager, listOf(name)) }.isFailure)
            }
        }
        assertTrue(runCatching { ModRuntime.installScript("brew", listOf("jq")) }.isFailure)
        assertTrue(runCatching { ModRuntime.installScript("apt", List(17) { "p$it" }) }.isFailure)
    }

    @Test
    fun `links open only for safe schemes`() {
        listOf("https://example.com", "mailto:a@b.c", "tel:+123", "geo:0,0?q=x", "sms:+1").forEach { assertEquals(it, ModIntents.check(it)) }
        listOf("file:///sdcard/a", "content://x/y", "intent://x#Intent;end", "javascript:alert(1)", "market://details?id=x", "x").forEach {
            assertTrue(it, runCatching { ModIntents.check(it) }.isFailure)
        }
    }

    @Test
    fun `a mod answers its dashboard chat and posts to it`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('session.start', async ($) => { await $.ui.status('up') })
              on('dashboard.message', async ($, e) => {
                if (e.text === 'post') { await $.dashboard.post('posted'); return {} }
                if (e.text === 'plain') return 'plain answer'
                return { text: 'echo ' + e.text }
              })
            }
        """.trimIndent(), permissions = listOf("ui.status"))
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod to start") { host.info()?.status == "up" }
        assertTrue("dashboard.message" in host.info()!!.events)

        assertNull(host.dashboardMessage("dev.test.sample", "  hi  "))
        assertNull(host.dashboardMessage("dev.test.sample", "plain"))
        assertNull(host.dashboardMessage("dev.test.sample", "post"))
        assertNull(host.dashboardMessage("dev.test.sample", "   "))
        val lines = host.dashboardChats.value["dev.test.sample"].orEmpty()
        assertEquals(listOf(true to "hi", false to "echo hi", true to "plain", false to "plain answer", true to "post", false to "posted"),
            lines.map { it.fromUser to it.text })

        // The chat is kept in the mod's data folder: a new host reads it back.
        val file = File(registry.dataDir("dev.test.sample"), "dashboard-chat.json")
        assertEquals(6, JSONArray(file.readText()).length())
        val other = host(registry)
        other.loadDashboardChat("dev.test.sample")
        assertEquals(lines.map { it.text }, other.dashboardChats.value["dev.test.sample"].orEmpty().map { it.text })

        host.clearDashboardChat("dev.test.sample")
        assertTrue(host.dashboardChats.value["dev.test.sample"].orEmpty().isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `a mod that does not listen gets no dashboard chat answers`() = runBlocking {
        val registry = registry()
        install(registry, "export function register(on) { on('session.start', async (\$) => { await \$.ui.status('up') }) }",
            permissions = listOf("ui.status"))
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod to start") { host.info()?.status == "up" }
        val problem = host.dashboardMessage("dev.test.sample", "hello")
        assertTrue(problem, problem!!.contains("doesn't answer messages"))
        assertEquals(listOf("hello"), host.dashboardChats.value["dev.test.sample"].orEmpty().map { it.text })
    }

    @Test
    fun `agent ask takes a mod role, and console replies reach role message hooks`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('command.run', { name: 'go' }, async ($, e) => {
                try { return { text: (await $.agent.ask({ role: e.args, prompt: 'hi' })).status } } catch (err) { return { text: 'ERR ' + err.message } }
              })
              on('role.message', async ($, e) => { await $.ui.status([e.role, e.harness, e.status, e.prompt, e.text].join('|')) })
            }
        """.trimIndent(), permissions = listOf("agent.ask", "ui.status")) {
            put("contributes", JSONObject()
                .put("slashCommands", JSONArray().put(JSONObject().put("name", "go").put("description", "Go")))
                .put("roles", JSONArray().put(JSONObject().put("id", "coder").put("title", "Coder").put("harness", "codex"))))
        }
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod to start") { host.info()?.state == ModRunState.RUNNING }
        assertEquals(ModCodeCommandOutcome.Reply("completed"), host.runCommand("dev.test.sample", "go", "coder"))
        assertEquals("coder", effects.askOptions.last().role)
        val refused = host.runCommand("dev.test.sample", "go", "boss")
        assertTrue("$refused", "$refused".contains("role boss isn't one of the mod's contributes.roles"))

        host.roleMessage("dev.test.sample", "coder", "codex", "Build it", "completed", "Built")
        waitFor("the role message") { host.info()?.status == "coder|codex|completed|Build it|Built" }
    }

    @Test
    fun `role messages hide the reply from mods that can't read chats or ask agents`() = runBlocking {
        val registry = registry()
        install(registry, """
            export function register(on) {
              on('session.start', async ($) => { await $.ui.status('up') })
              on('role.message', async ($, e) => { await $.ui.status(e.role + ':' + (e.text === undefined ? 'hidden' : e.text)) })
            }
        """.trimIndent(), permissions = listOf("ui.status"))
        val host = host(registry)
        host.updateContext(context, taskIsRunning = false)
        waitFor("the mod to start") { host.info()?.status == "up" }
        host.roleMessage("dev.test.sample", "coder", "codex", "Build it", "completed", "Built")
        waitFor("the role message") { host.info()?.status == "coder:hidden" }
    }
}
