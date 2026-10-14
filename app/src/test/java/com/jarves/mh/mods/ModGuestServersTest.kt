package com.jarves.mh.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class ModGuestServersTest {
    @get:Rule val temp = TemporaryFolder()

    private val killed = CopyOnWriteArrayList<Int>()
    private val busyPorts = mutableSetOf<Int>()

    private fun servers(proc: File = temp.newFolder("proc")) = ModGuestServers(processes = proc, kill = { killed += it }, portFree = { it !in busyPorts })

    private fun sleeper(): Process = ProcessBuilder("sleep", "30").start()

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun `two servers per mod, one per port, plain names`() {
        val servers = servers()
        servers.check("a.mod", "web", 8080)
        servers.started("a.mod", "web", 8080, "serve", sleeper(), temp.newFile())
        assertTrue(runCatching { servers.check("a.mod", "web", 8081) }.exceptionOrNull()!!.message!!.contains("already running"))
        assertTrue(runCatching { servers.check("b.mod", "api", 8080) }.exceptionOrNull()!!.message!!.contains("in use"))
        busyPorts += 9000
        assertTrue(runCatching { servers.check("b.mod", "api", 9000) }.exceptionOrNull()!!.message!!.contains("in use"))
        assertTrue(runCatching { servers.check("b.mod", "api", 80) }.isFailure)
        assertTrue(runCatching { servers.check("b.mod", "Bad Name", 8090) }.isFailure)
        servers.started("a.mod", "api", 8081, "serve", sleeper(), temp.newFile())
        assertTrue(runCatching { servers.check("a.mod", "third", 8082) }.exceptionOrNull()!!.message!!.contains("at most 2"))
        servers.check("b.mod", "api", 8082)
        servers.stopAll()
    }

    @Test
    fun `stopping kills the process and everything marked with its name`() {
        val proc = temp.newFolder("proc")
        fun fakeProcess(pid: Int, vararg env: String) = File(proc, "$pid").apply { mkdirs(); File(this, "environ").writeText(env.joinToString("\u0000")) }
        fakeProcess(101, "PATH=/bin", "MOD_SERVE=a.mod/web")
        fakeProcess(102, "MOD_SERVE=a.mod/web-2")
        fakeProcess(103, "MOD_SERVE=b.mod/web")
        val servers = servers(proc)
        val process = sleeper()
        servers.started("a.mod", "web", 8080, "serve", process, temp.newFile())
        assertTrue(servers.stop("a.mod", "web"))
        assertFalse(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS).not())
        assertEquals(listOf(101), killed.toList())
        assertFalse(servers.list("a.mod").single().running)
        assertFalse(servers.stop("a.mod", "web"))
    }

    @Test
    fun `turning a mod off stops only its servers, and an ended server is noticed`() {
        val servers = servers()
        servers.started("a.mod", "web", 8080, "serve", sleeper(), temp.newFile())
        servers.started("b.mod", "web", 8081, "serve", sleeper(), temp.newFile())
        servers.stopMods(setOf("b.mod"))
        assertEquals(listOf(false, true), servers.servers.value.map { it.running })
        servers.started("c.mod", "once", 8082, "true", ProcessBuilder("true").start(), temp.newFile())
        waitFor("the ended server") { servers.list("c.mod").single().let { !it.running && it.exitCode == 0 } }
        servers.stopAll()
        assertTrue(servers.servers.value.none { it.running })
    }

    @Test
    fun `a flood of output stops the server, and output is readable`() {
        val servers = servers()
        val log = temp.newFile().apply { writeText("x".repeat(100) + "listening on 8080\n") }
        servers.started("a.mod", "web", 8080, "serve", sleeper(), log)
        assertTrue(servers.output("a.mod", "web", 20).endsWith("listening on 8080\n"))
        log.appendBytes(ByteArray((ModGuestServers.MAX_LOG_BYTES + 1).toInt()))
        waitFor("the flood stop") { servers.list("a.mod").single().let { !it.running && it.note?.contains("more than 8 MB") == true } }
    }

    @Test
    fun `the script marks, limits and runs the command with its port`() {
        val script = servers().script("a.mod", "web", 8080, "echo 'hi'")
        assertTrue(script, script.startsWith("MOD_SERVE='a.mod/web'; export MOD_SERVE; PORT=8080; export PORT; ulimit -t 3600"))
        assertTrue(script, script.endsWith("exec /usr/bin/bash -lc 'echo '\\''hi'\\'''"))
    }
}
