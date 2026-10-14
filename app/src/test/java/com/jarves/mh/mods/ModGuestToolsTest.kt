package com.jarves.mh.mods

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
import java.util.concurrent.TimeUnit

class ModGuestToolsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun install(registry: ModRegistry, id: String = "dev.test.guest"): InstalledMod {
        val files = mapOf("tools/hello.sh" to "echo hello")
        fun program(name: String, command: List<String>) = JSONObject().put("name", name).put("description", "Says hello")
            .put("command", JSONArray(command)).put("env", JSONObject().put("GREETING", "it's me"))
        val manifest = ModTestKit.manifest(files) {
            put("id", id)
            put("permissions", JSONArray().put(JSONObject().put("capability", "guest.tools").put("reason", "test")))
            put("contributes", JSONObject()
                .put("guestTools", JSONArray().put(program("hello", listOf("sh", "-c", "echo \"\$MOD_DIR|\$GREETING|\$1\"", "x"))))
                .put("mcpServers", JSONArray().put(program("docs", listOf("sh", "./tools/hello.sh")))))
        }
        val staged = registry.stageArchive(ByteArrayInputStream(ModTestKit.modZip(manifest, files)))
        return registry.install(staged, setOf(ModCapability.GUEST_TOOLS), ModTrust.IMPORTED, "guest.mhmod")
    }

    @Test
    fun `only mods allowed guest-tools in Developer mode get guest programs`() {
        val registry = ModRegistry(File(temp.root, "mods"), appVersionCode = 5)
        install(registry)
        // Installed with Developer mode off: the developer grant was never given.
        assertTrue(ModGuestTools.allowed(registry.snapshot.value).isEmpty())
        registry.setDeveloperMode(true)
        registry.setGranted("dev.test.guest", ModCapability.GUEST_TOOLS, true)
        assertEquals(listOf("dev.test.guest"), ModGuestTools.allowed(registry.snapshot.value).map { it.id })
        registry.setDeveloperMode(false)
        assertTrue(ModGuestTools.allowed(registry.snapshot.value).isEmpty())
    }

    @Test
    fun `sync writes limited launchers and the MCP servers, and removes what is no longer allowed`() {
        val registry = ModRegistry(File(temp.root, "mods"), appVersionCode = 5)
        registry.setDeveloperMode(true)
        val mod = install(registry)
        val rootfs = temp.newFolder("rootfs")
        File(rootfs, "opt/pocket/mods/pkg/old.mod").mkdirs()
        File(rootfs, "opt/pocket/mods/bin").mkdirs()
        File(rootfs, "opt/pocket/mods/bin/mod_old_tool").writeText("old")

        val servers = ModGuestTools.prepare(rootfs, registry.snapshot.value)
        val command = ModGuestTools.commandName(mod.id, "hello")
        assertEquals("mod_guest_hello", command)
        val launcher = File(rootfs, "opt/pocket/mods/bin/$command")
        assertTrue(launcher.canExecute())
        val text = launcher.readText()
        assertTrue(text, "ulimit -t ${ModGuestTools.TOOL_CPU_SECONDS}" in text && "ulimit -d ${ModGuestTools.DATA_LIMIT_KB}" in text)
        assertTrue(File(rootfs, "opt/pocket/mods/pkg/dev.test.guest/tools/hello.sh").canExecute())
        assertFalse(File(rootfs, "opt/pocket/mods/pkg/old.mod").exists())
        assertFalse(File(rootfs, "opt/pocket/mods/bin/mod_old_tool").exists())

        // The launcher runs: its mod folder and env are set and the agent's arguments pass through.
        val run = ProcessBuilder("sh", launcher.absolutePath, "world").redirectErrorStream(true).start()
        assertTrue(run.waitFor(10, TimeUnit.SECONDS))
        assertEquals("/opt/pocket/mods/pkg/dev.test.guest|it's me|world", run.inputStream.bufferedReader().readText().trim())

        val server = servers.single()
        assertEquals("mod_guest_docs", server.key)
        assertTrue(server.script, "ulimit -t ${ModGuestTools.SERVER_CPU_SECONDS}" in server.script)
        assertTrue(server.script, server.script.endsWith("exec 'sh' '/opt/pocket/mods/pkg/dev.test.guest/tools/hello.sh'"))
        val claude = ModGuestTools.claudeConfig(JSONObject().put("mcpServers", JSONObject().put("mods", JSONObject().put("command", "node"))), servers)
        assertEquals("node", claude.getJSONObject("mcpServers").getJSONObject("mods").getString("command"))
        assertEquals("/bin/sh", claude.getJSONObject("mcpServers").getJSONObject("mod_guest_docs").getString("command"))
        val codex = ModGuestTools.codexOverrides(servers) { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }.single()
        assertTrue(codex, codex.startsWith("mcp_servers.mod_guest_docs={command=\"/bin/sh\",args=[\"-c\",") && "required=false" in codex)

        // Developer mode off: the next task start removes the mod's files and launcher.
        registry.setDeveloperMode(false)
        assertTrue(ModGuestTools.prepare(rootfs, registry.snapshot.value).isEmpty())
        assertFalse(launcher.exists())
        assertFalse(File(rootfs, "opt/pocket/mods/pkg/dev.test.guest").exists())
    }

    @Test
    fun `stop kills only programs of mods that are no longer allowed`() {
        val proc = temp.newFolder("proc")
        fun process(pid: Int, vararg env: String) = File(proc, pid.toString()).apply { mkdirs() }
            .resolve("environ").writeBytes(env.joinToString("\u0000").toByteArray())
        process(10, "PATH=/usr/bin", "MOD_DIR=/opt/pocket/mods/pkg/a.keep")
        process(11, "MOD_DIR=/opt/pocket/mods/pkg/b.gone", "HOME=/root")
        process(12, "PATH=/usr/bin")
        File(proc, "self").mkdirs()
        val killed = mutableListOf<Int>()
        assertEquals(1, ModGuestTools.stop(setOf("a.keep"), proc) { killed += it })
        assertEquals(listOf(11), killed)
        killed.clear()
        ModGuestTools.stop(emptySet(), proc) { killed += it }
        assertEquals(setOf(10, 11), killed.toSet())
    }
}
