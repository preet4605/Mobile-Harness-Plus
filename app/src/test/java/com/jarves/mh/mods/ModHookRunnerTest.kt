package com.jarves.mh.mods

import com.jarves.mh.model.AgentKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class ModHookRunnerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var now = 1_000_000L

    private class Recorder : ModHookEffects {
        val toasts = mutableListOf<String>()
        val posts = mutableListOf<String>()
        val drafts = mutableListOf<String>()
        var composerBusy = false

        override fun toast(mod: InstalledMod, text: String) { toasts += text }
        override fun notify(mod: InstalledMod, title: String, body: String) = false
        override fun chatPost(mod: InstalledMod, text: String, context: ModEventContext) { posts += text }
        override fun promptDraft(mod: InstalledMod, text: String, context: ModEventContext): Boolean {
            if (composerBusy) return false
            drafts += text
            return true
        }
    }

    private fun permission(capability: String, paths: List<String> = emptyList()) = JSONObject()
        .put("capability", capability).put("reason", "test")
        .apply { if (paths.isNotEmpty()) put("scope", JSONObject().put("paths", JSONArray(paths))) }

    private fun hook(id: String, on: String, action: JSONObject, status: String? = null) = JSONObject()
        .put("id", id).put("on", on).put("action", action)
        .apply { if (status != null) put("filter", JSONObject().put("status", status)) }

    private fun manifest() = ModTestKit.manifest {
        put("permissions", JSONArray()
            .put(permission("ui.toast"))
            .put(permission("chat.post"))
            .put(permission("chat.read"))
            .put(permission("prompt.draft"))
            .put(permission("files.write", listOf("notes/*.md"))))
        put("contributes", JSONObject().put("hooks", JSONArray()
            .put(hook("done", "task.finished", JSONObject().put("type", "ui.toast").put("text", "{{task.status}} in {{project.name}} after {{task.duration}}"), "completed"))
            .put(hook("journal", "files.changed", JSONObject().put("type", "files.append").put("path", "notes/log.md").put("line", "{{files.count}}: {{files.paths}}")))
            .put(hook("echo", "assistant.message.completed", JSONObject().put("type", "chat.post").put("text", "Agent said: {{message.text}}")))
            .put(hook("next", "session.completed", JSONObject().put("type", "prompt.draft").put("text", "Now add tests for {{project.name}}")))))
    }

    private val project by lazy { File(temp.root, "project").apply { mkdirs() } }
    private val context get() = ModEventContext("p1", "Demo", { project }, "c1", AgentKind.CODEX)

    private fun setup(granted: Set<ModCapability>): Triple<ModRegistry, Recorder, ModHookRunner> {
        val registry = ModRegistry(File(temp.root, "mods"), appVersionCode = 5)
        val staged = registry.stageArchive(ByteArrayInputStream(ModTestKit.modZip(manifest())))
        registry.install(staged, granted, ModTrust.IMPORTED, "sample.mhmod")
        val recorder = Recorder()
        val runner = ModHookRunner(registry, recorder, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) { now }
        return Triple(registry, recorder, runner)
    }

    private val all = setOf(ModCapability.UI_TOAST, ModCapability.CHAT_POST, ModCapability.CHAT_READ, ModCapability.PROMPT_DRAFT, ModCapability.FILES_WRITE)

    @Test
    fun `status filters, templates and rate limits`() {
        val (_, recorder, runner) = setup(all)
        assertTrue(runner.listens(ModEvent.TASK_FINISHED))
        assertFalse(runner.listens(ModEvent.APP_RESUMED))

        runner.emit(ModEvent.TASK_FINISHED, context, mapOf("task.status" to "failed"), status = "failed")
        assertTrue(recorder.toasts.isEmpty())

        val vars = mapOf("task.status" to "completed", "task.duration" to "1m 5s", "secret.thing" to "dropped")
        runner.emit(ModEvent.TASK_FINISHED, context, vars, status = "completed")
        assertEquals(listOf("Sample: completed in Demo after 1m 5s"), recorder.toasts)

        runner.emit(ModEvent.TASK_FINISHED, context, vars, status = "completed")
        assertEquals("a second toast within a second is dropped", 1, recorder.toasts.size)
        now += 1_001
        runner.emit(ModEvent.TASK_FINISHED, context, vars, status = "completed")
        assertEquals(2, recorder.toasts.size)
    }

    @Test
    fun `file appends stay in the project and refuse links`() {
        val (registry, _, runner) = setup(all)
        runner.emit(ModEvent.FILES_CHANGED, context, mapOf("files.count" to "2", "files.paths" to "a.kt, b.kt"))
        assertEquals("2: a.kt, b.kt\n", File(project, "notes/log.md").readText())

        val outside = File(temp.root, "outside.md").apply { writeText("keep") }
        File(project, "notes/log.md").delete()
        Files.createSymbolicLink(File(project, "notes/log.md").toPath(), outside.toPath())
        repeat(ModRegistry.MAX_HOOK_FAILURES) {
            now += 1_001
            runner.emit(ModEvent.FILES_CHANGED, context, mapOf("files.count" to "1", "files.paths" to "x"))
        }
        assertEquals("keep", outside.readText())
        val mod = registry.snapshot.value.mods.single()
        assertTrue("five failures turn the hook off", "journal" in mod.disabledHooks)
        assertTrue(registry.readAudit(mod.id).any { it.decision == "failed" })
    }

    @Test
    fun `revoked permissions and chat text need their grants`() {
        val (registry, recorder, runner) = setup(all - ModCapability.CHAT_READ - ModCapability.UI_TOAST)
        runner.emit(ModEvent.TASK_FINISHED, context, mapOf("task.status" to "completed"), status = "completed")
        runner.emit(ModEvent.ASSISTANT_MESSAGE_COMPLETED, context, mapOf("message.text" to "All done"))
        assertTrue(recorder.toasts.isEmpty())
        assertTrue("message.text needs chat.read", recorder.posts.isEmpty())
        val id = registry.snapshot.value.mods.single().id
        assertEquals(2, registry.readAudit(id).count { it.decision == "denied" })

        registry.setGranted(id, ModCapability.CHAT_READ, true)
        now += 10_000
        runner.emit(ModEvent.ASSISTANT_MESSAGE_COMPLETED, context, mapOf("message.text" to "All done"))
        assertEquals(listOf("Agent said: All done"), recorder.posts)
    }

    @Test
    fun `drafts never replace typing and paused mods do nothing`() {
        val (registry, recorder, runner) = setup(all)
        recorder.composerBusy = true
        runner.emit(ModEvent.SESSION_COMPLETED, context)
        assertTrue(recorder.drafts.isEmpty())
        recorder.composerBusy = false
        now += 5_001
        runner.emit(ModEvent.SESSION_COMPLETED, context)
        assertEquals(listOf("Now add tests for Demo"), recorder.drafts)

        registry.setModsEnabled(false)
        assertFalse(runner.listens(ModEvent.SESSION_COMPLETED))
        now += 5_001
        runner.emit(ModEvent.SESSION_COMPLETED, context)
        assertEquals(1, recorder.drafts.size)
    }
}
