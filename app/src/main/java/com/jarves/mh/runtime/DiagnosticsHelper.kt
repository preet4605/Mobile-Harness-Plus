package com.jarves.mh.runtime

import android.app.Application
import android.os.Build
import android.system.Os
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.SessionTokenMetrics
import com.jarves.mh.model.SlashCommand
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

object DiagnosticsHelper {

    fun buildHelpMessage(agent: AgentKind): String = buildString {
        appendLine("### Available Slash (`/`) Commands for ${agent.title}")
        appendLine()
        val commands = SlashCommandEngine.ALL_SLASH_COMMANDS.filter { agent in it.supportedAgents }
        val grouped = commands.groupBy { it.category }

        grouped.forEach { (category, list) ->
            appendLine("#### ${category.title}")
            list.forEach { cmd ->
                val hint = if (cmd.parameterHint != null) " `${cmd.parameterHint}`" else ""
                val localBadge = if (cmd.isLocalOnly) " *(Instant)*" else ""
                appendLine("- **`/${cmd.name}`**$hint$localBadge: ${cmd.description}")
            }
            appendLine()
        }
        appendLine("---")
        appendLine("💡 **Tips:**")
        appendLine("- Type `/` in the chat input anytime to open the command palette.")
        appendLine("- Use `/plan` before complex features to align on architecture and files.")
        appendLine("- Use `/doctor` to diagnose runtime, PRoot, and network health.")
    }

    fun buildCostMessage(metrics: SessionTokenMetrics, agent: AgentKind): String = buildString {
        appendLine("### Session Token Metrics & Cost (${agent.title})")
        appendLine()
        appendLine("| Metric | Count / Value |")
        appendLine("| :--- | :--- |")
        appendLine("| **Prompt Tokens** | ${String.format("%,d", metrics.promptTokens)} |")
        appendLine("| **Completion Tokens** | ${String.format("%,d", metrics.completionTokens)} |")
        appendLine("| **Cached Tokens** | ${String.format("%,d", metrics.cachedTokens)} |")
        appendLine("| **Total Session Tokens** | ${String.format("%,d", metrics.promptTokens + metrics.completionTokens)} |")
        appendLine("| **Context Window Limit** | ${String.format("%,d", metrics.contextWindowLimit)} |")
        
        val pct = if (metrics.contextWindowLimit > 0) {
            ((metrics.promptTokens.toDouble() / metrics.contextWindowLimit) * 100).coerceIn(0.0, 100.0)
        } else 0.0
        appendLine("| **Context Capacity Used** | ${String.format("%.1f%%", pct)} |")
        
        if (metrics.estimatedCostUsd > 0.0001) {
            appendLine("| **Estimated Cost** | $${String.format("%.4f", metrics.estimatedCostUsd)} USD |")
        } else {
            appendLine("| **Estimated Cost** | Included in quota / subscription |")
        }
        appendLine()
        appendLine("*Note: Context window usage resets or compacts on `/compact` or `/clear`.*")
    }

    suspend fun runDoctor(app: Application, agent: AgentKind): String {
        val installer = RuntimeInstaller(app)
        val installed = installer.installedRuntime()

        val results = mutableListOf<Pair<String, Boolean>>()
        val details = mutableListOf<String>()

        // 1. ARM64 ABI check
        val isArm64 = supportsArm64Runtime(Build.SUPPORTED_ABIS, System.getProperty("os.arch"))
        results.add("ARM64 Platform Support" to isArm64)
        details.add("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")

        // 2. PRoot binary
        val prootOk = installed.proot.isFile && installed.proot.canExecute()
        results.add("PRoot Virtualization Engine" to prootOk)
        details.add("PRoot binary: ${installed.proot.name} (${if (prootOk) "Executable" else "Missing/No-exec"})")

        // 3. Linux rootfs
        val rootfsOk = installed.rootfs.isDirectory && File(installed.rootfs, "bin/sh").isFile
        results.add("Linux Guest RootFS" to rootfsOk)

        // 4. Active agent binary
        val agentInstalled = installer.isAgentInstalled(agent)
        results.add("${agent.title} Runtime" to agentInstalled)

        // 5. Storage free space
        var freeMb = 0L
        runCatching {
            val stat = Os.statvfs(app.filesDir.absolutePath)
            freeMb = (stat.f_bavail * stat.f_frsize) / (1024 * 1024)
        }
        val storageOk = freeMb > 500
        results.add("App Storage (${freeMb} MB free)" to storageOk)

        // 6. Network connectivity
        var internetOk = false
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("1.1.1.1", 53), 2500)
                internetOk = true
            }
        }
        results.add("Network & DNS Reachability" to internetOk)

        // Build report
        return buildString {
            appendLine("### Mobile Harness Doctor Diagnostics")
            appendLine()
            results.forEach { (item, ok) ->
                val badge = if (ok) "✓" else "✗"
                appendLine("- [$badge] **$item**")
            }
            appendLine()
            appendLine("#### System Context:")
            appendLine("- **Device Model:** ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine("- **Active Agent:** ${agent.title}")
            details.forEach { appendLine("- $it") }
            appendLine()
            if (results.all { it.second }) {
                appendLine("🎉 **All systems operational.** Ready to code!")
            } else {
                appendLine("⚠️ **Issues detected.** Review failed items above.")
            }
        }
    }
}
