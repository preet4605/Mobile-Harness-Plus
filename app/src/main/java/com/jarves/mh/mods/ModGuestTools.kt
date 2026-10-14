package com.jarves.mh.mods

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Mod-shipped guest tools and MCP servers (mod plan row 11). Only mods allowed `guest.tools`
 * (Developer mode) get them. Before each task the app copies those mods into the guest at
 * [GUEST_ROOT]/pkg/<id>, writes one launcher per guest tool, and hands Claude and Codex the MCP
 * servers. Every program runs with [MOD_DIR_ENV] set to its mod's folder (how [stop] finds it),
 * a CPU-time limit and a 1 GB data limit, so a runaway program stops itself instead of loading
 * the guest. Turning a mod off, or Developer mode off, kills its programs ([stop]).
 */
object ModGuestTools {
    const val GUEST_ROOT = "/opt/pocket/mods"
    const val MOD_DIR_ENV = "MOD_DIR"
    /** CPU seconds for one guest tool run, and for an MCP server's whole life. */
    const val TOOL_CPU_SECONDS = 600
    const val SERVER_CPU_SECONDS = 3_600
    const val DATA_LIMIT_KB = 1024 * 1024
    private const val MARKER = ".mh-installed"

    /** An MCP server to start with `/bin/sh -c [script]`, under the name [key]. */
    data class McpServer(val key: String, val modId: String, val description: String, val script: String)

    /** A guest tool's launcher, as the agents see it. */
    data class Tool(val command: String, val modName: String, val description: String)

    /** Mods whose guest tools and MCP servers may run now. */
    fun allowed(snapshot: ModsSnapshot): List<InstalledMod> = snapshot.active.filter { mod ->
        (mod.manifest.guestTools.isNotEmpty() || mod.manifest.mcpServers.isNotEmpty()) &&
            ModCapability.GUEST_TOOLS in mod.granted && mod.canHold(ModCapability.GUEST_TOOLS)
    }

    /** The command an agent types for [program] of [modId]: unique per mod, like mod tool names. */
    fun commandName(modId: String, program: String): String = ModTool.exposedName(modId, program)

    fun tools(mods: List<InstalledMod>): List<Tool> = mods.flatMap { mod ->
        mod.manifest.guestTools.map { Tool(commandName(mod.id, it.name), mod.manifest.name, it.description) }
    }

    fun mcpServers(mods: List<InstalledMod>): List<McpServer> = mods.flatMap { mod ->
        mod.manifest.mcpServers.map { server ->
            McpServer(commandName(mod.id, server.name), mod.id, "${server.description} (from the ${mod.manifest.name} mod)",
                script(mod.id, server, SERVER_CPU_SECONDS, passArgs = false))
        }
    }

    /**
     * Puts [mods] into the guest under [rootfs] and removes every other mod's files and launchers.
     * A mod is copied again only when its version or install time changed.
     */
    fun sync(rootfs: File, mods: List<InstalledMod>) {
        val root = File(rootfs, GUEST_ROOT.removePrefix("/"))
        val pkg = File(root, "pkg").apply { mkdirs() }
        val bin = File(root, "bin").apply { mkdirs() }
        val keep = mods.map { it.id }.toSet()
        pkg.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
        mods.forEach { mod ->
            val target = File(pkg, mod.id)
            val stamp = "${mod.manifest.version}:${mod.installedAt}"
            if (File(target, MARKER).takeIf { it.isFile }?.readText() != stamp) {
                target.deleteRecursively()
                copyPackage(mod.dir, target)
                File(target, MARKER).writeText(stamp)
            }
            (mod.manifest.guestTools + mod.manifest.mcpServers).flatMap { it.command }.filter { it.startsWith("./") }
                .forEach { File(target, it.removePrefix("./")).takeIf(File::isFile)?.setExecutable(true, false) }
        }
        val launchers = tools(mods).map { it.command }.toSet()
        bin.listFiles()?.filter { it.name !in launchers }?.forEach { it.delete() }
        mods.forEach { mod ->
            mod.manifest.guestTools.forEach { tool ->
                File(bin, commandName(mod.id, tool.name)).apply {
                    writeText("#!/bin/sh\n# ${mod.manifest.name}: ${tool.name} (written by the app before each task)\n" +
                        script(mod.id, tool, TOOL_CPU_SECONDS, passArgs = true) + "\n")
                    setExecutable(true, false)
                }
            }
        }
    }

    /**
     * Before a task: syncs the allowed mods into the guest and answers their MCP servers. Any
     * failure leaves the task without mod programs rather than stopping it.
     */
    fun prepare(rootfs: File, snapshot: ModsSnapshot): List<McpServer> = runCatching {
        val mods = allowed(snapshot)
        sync(rootfs, mods)
        mcpServers(mods)
    }.getOrDefault(emptyList())

    /** Claude's `--mcp-config` content: the app's own mods server plus [servers]. */
    fun claudeConfig(base: JSONObject, servers: List<McpServer>): JSONObject {
        val all = JSONObject(base.toString())
        val map = all.optJSONObject("mcpServers") ?: JSONObject().also { all.put("mcpServers", it) }
        servers.forEach { server -> map.put(server.key, JSONObject().put("command", "/bin/sh").put("args", JSONArray().put("-c").put(server.script))) }
        return all
    }

    /** Codex `-c` overrides that add [servers] as optional MCP servers. */
    fun codexOverrides(servers: List<McpServer>, toml: (String) -> String): List<String> = servers.map { server ->
        "mcp_servers.${server.key}={command=\"/bin/sh\",args=[\"-c\",${toml(server.script)}],env_vars=[\"POCKET_WORKSPACE\",\"PATH\"]," +
            "startup_timeout_sec=20,tool_timeout_sec=120,required=false}"
    }

    /**
     * Kills the guest programs of every mod not in [keep] (all of them for an empty set): any
     * process whose environment names a mod folder outside [keep]. [processes] lists `/proc`.
     */
    fun stop(keep: Set<String>, processes: File = File("/proc"), kill: (Int) -> Unit): Int {
        val prefix = "$MOD_DIR_ENV=$GUEST_ROOT/pkg/"
        var killed = 0
        processes.listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val environ = runCatching { File(dir, "environ").readBytes() }.getOrNull() ?: return@forEach
            val owner = String(environ, Charsets.UTF_8).split('\u0000').firstOrNull { it.startsWith(prefix) }
                ?.removePrefix(prefix)?.substringBefore('/') ?: return@forEach
            if (owner !in keep) {
                runCatching { kill(pid) }
                killed++
            }
        }
        return killed
    }

    /** `/bin/sh` text that limits the program and runs it, with `./` files taken from the mod. */
    internal fun script(modId: String, program: ModGuestProgram, cpuSeconds: Int, passArgs: Boolean): String {
        val dir = "$GUEST_ROOT/pkg/$modId"
        val argv = program.command.map { if (it.startsWith("./")) "$dir/${it.removePrefix("./")}" else it }
        return buildString {
            append("$MOD_DIR_ENV=").append(quote(dir)).append("; export $MOD_DIR_ENV; ")
            program.env.forEach { (key, value) -> append(key).append('=').append(quote(value)).append("; export ").append(key).append("; ") }
            append("ulimit -t $cpuSeconds 2>/dev/null; ulimit -d $DATA_LIMIT_KB 2>/dev/null; ")
            append("exec ").append(argv.joinToString(" ", transform = ::quote))
            if (passArgs) append(" \"\$@\"")
        }
    }

    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

    /** Copies the installed package (never its saved data, which lives beside it), skipping links. */
    private fun copyPackage(from: File, to: File) {
        val base = from.canonicalFile
        base.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach { file ->
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) return@forEach
            val relative = file.relativeTo(base).path
            if (relative.isEmpty()) return@forEach
            val out = File(to, relative)
            if (file.isDirectory) out.mkdirs() else file.copyTo(out, overwrite = true)
        }
    }
}
