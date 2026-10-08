package com.jarves.mh.runtime

import com.jarves.mh.data.AppPreferences
import com.jarves.mh.data.ApiKeyVault
import com.jarves.mh.data.ContextMemory
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.providerProtocolForAgent
import com.jarves.mh.model.providersForAgent
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private class StubBridge : RuntimeBridge {
    override val events: Flow<RuntimeEvent> = emptyFlow()
    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
        memory: ContextMemory,
        taskId: String?,
        brainSnapshot: com.jarves.mh.data.BrainContextSnapshot?,
        attemptId: String?,
    ): String = "stub"
    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = Unit
    override suspend fun stopSession(sessionId: String, force: Boolean) = Unit
    override suspend fun stopActiveSession(force: Boolean) = Unit
    override suspend fun undoLastChanges(projectId: String) = false
    override suspend fun acceptLastChanges(projectId: String) = Unit
    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = emptyList()
    override suspend fun undoFileChange(projectId: String, path: String) = false
    override suspend fun acceptFileChange(projectId: String, path: String) = false
}

class CodexAgentModelTest {
    @Test
    fun codexIsTheFourthAgentWithAUniqueStableId() {
        assertEquals(4, AgentKind.entries.size)
        assertEquals(AgentKind.entries.size, AgentKind.entries.map { it.stableId }.toSet().size)
        assertEquals(AgentKind.CODEX, AgentKind.fromStored("codex"))
        assertEquals(AgentKind.CODEX, AgentKind.fromStored("CODEX"))
    }

    @Test
    fun unknownStoredAgentStillFallsBackToClaude() {
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.fromStored("not-an-agent"))
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.fromStored(null))
    }

    @Test
    fun codexOffersOnlyResponsesCapableProviders() {
        assertEquals(listOf(ProviderKind.CHATGPT, ProviderKind.CUSTOM), providersForAgent(AgentKind.CODEX))
    }

    @Test
    fun codexAlwaysResolvesToTheResponsesProtocol() {
        listOf("openai-completions", "anthropic-messages", "openai-responses", "").forEach { api ->
            val profile = ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example.com/v1", dshApi = api)
            assertEquals(ProviderProtocol.OPENAI_RESPONSES, providerProtocolForAgent(profile, AgentKind.CODEX))
        }
    }

    @Test
    fun otherAgentsKeepTheirProviderLists() {
        assertFalse(providersForAgent(AgentKind.CLAUDE_CODE).contains(ProviderKind.OPENCODE_ZEN))
        assertTrue(providersForAgent(AgentKind.DEEPSEEK_HARNESS).contains(ProviderKind.DEEPSEEK))
        assertTrue(providersForAgent(AgentKind.ANTIGRAVITY).isEmpty())
    }

    @Test
    fun theChatGptAccountIsOfferedOnlyToCodex() {
        AgentKind.entries.filterNot { it == AgentKind.CODEX }.forEach {
            assertFalse("$it must not offer ChatGPT sign-in", providersForAgent(it).contains(ProviderKind.CHATGPT))
        }
        assertTrue(ProviderKind.CHATGPT.fixedBaseUrl)
        assertEquals("", ProviderProfile(ProviderKind.CHATGPT).resolvedBaseUrl)
        assertEquals("", ProviderKind.CHATGPT.defaultModel)
    }

    @Test
    fun registryRegistersEveryAgentSoLaunchCannotCrash() {
        val bridges = List(4) { StubBridge() }
        val registry = AgentRegistry.builtIns(bridges[0], bridges[1], bridges[2], bridges[3])
        AgentKind.entries.forEach { assertEquals(it, registry.require(it).kind) }
        val codex = registry.require(AgentKind.CODEX)
        assertTrue(codex.runtime === bridges[3])
        assertFalse(codex.capabilities.contains(AgentCapability.INTERACTIVE_APPROVALS))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexPreferencesTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun codexDefaultsToTheChatGptAccount() {
        val profile = AppPreferences(app).loadProvider(ApiKeyVault(app), AgentKind.CODEX)
        assertEquals(ProviderKind.CHATGPT, profile.kind)
        assertEquals("", profile.model)
        assertFalse(profile.hasSecret)
    }

    @Test
    fun chatGptProfileReflectsTheSignedInMirror() {
        val prefs = AppPreferences(app)
        val vault = ApiKeyVault(app)
        prefs.saveProviderForAgentOnly(ProviderProfile(ProviderKind.CHATGPT), AgentKind.CODEX)
        assertFalse(prefs.loadProvider(vault, AgentKind.CODEX).hasSecret)
        prefs.codexSignedIn = true
        val profile = prefs.loadProvider(vault, AgentKind.CODEX)
        assertEquals(ProviderKind.CHATGPT, profile.kind)
        assertTrue(profile.hasSecret)
        prefs.codexSignedIn = false
        assertFalse(prefs.loadProvider(vault, AgentKind.CODEX).hasSecret)
    }

    @Test
    fun aSavedChatGptModelSurvivesReload() {
        val prefs = AppPreferences(app)
        prefs.saveProviderForAgentOnly(ProviderProfile(ProviderKind.CHATGPT, "", "gpt-x"), AgentKind.CODEX)
        assertEquals("gpt-x", prefs.loadProvider(ApiKeyVault(app), AgentKind.CODEX).model)
    }

    @Test
    fun codexIgnoresAProviderOnlyOtherAgentsCanUse() {
        val prefs = AppPreferences(app)
        prefs.saveProviderForAgentOnly(ProviderProfile(ProviderKind.ANTHROPIC), AgentKind.CODEX)
        assertEquals(ProviderKind.CHATGPT, prefs.loadProvider(ApiKeyVault(app), AgentKind.CODEX).kind)
    }

    @Test
    fun codexKeepsItsOwnSavedCustomProfileAndLeavesOtherAgentsAlone() {
        val prefs = AppPreferences(app)
        val vault = ApiKeyVault(app)
        prefs.saveProviderForAgentOnly(
            ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example.com/v1", model = "gpt-x"),
            AgentKind.CODEX,
        )
        val codex = prefs.loadProvider(vault, AgentKind.CODEX)
        assertEquals(ProviderKind.CUSTOM, codex.kind)
        assertEquals("https://gw.example.com/v1", codex.baseUrl)
        assertEquals("gpt-x", codex.model)
        assertEquals(ProviderKind.ANTHROPIC, prefs.loadProvider(vault, AgentKind.CLAUDE_CODE).kind)
        assertEquals(ProviderKind.DEEPSEEK, prefs.loadProvider(vault, AgentKind.DEEPSEEK_HARNESS).kind)
    }
}

class CodexArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    private val payload = ByteArray(5_000) { (it % 251).toByte() }

    private fun archive(entries: List<Pair<String, ByteArray>>): File {
        val file = folder.newFile()
        TarArchiveOutputStream(GzipCompressorOutputStream(file.outputStream())).use { tar ->
            entries.forEach { (name, bytes) ->
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return file
    }

    @Test
    fun extractsOnlyTheRequestedEntry() {
        val tgz = archive(listOf("package/other.txt" to ByteArray(10), "package/bin/codex" to payload, "package/z" to ByteArray(3)))
        val out = File(folder.root, "out/codex")
        CodexArchive.extractEntry(tgz, "package/bin/codex", out, payload.size.toLong())
        assertTrue(out.readBytes().contentEquals(payload))
        assertEquals(listOf("codex"), out.parentFile!!.list()!!.toList())
    }

    @Test
    fun wrongSizeIsRejectedAndLeavesNothingBehind() {
        val tgz = archive(listOf("package/bin/codex" to payload))
        val out = File(folder.root, "out/codex")
        assertThrows(IllegalArgumentException::class.java) {
            CodexArchive.extractEntry(tgz, "package/bin/codex", out, payload.size + 1L)
        }
        assertFalse(out.exists())
    }

    @Test
    fun missingEntryIsRejectedAndLeavesNothingBehind() {
        val tgz = archive(listOf("package/other" to payload))
        val out = File(folder.root, "out/codex")
        assertThrows(IllegalStateException::class.java) {
            CodexArchive.extractEntry(tgz, "package/bin/codex", out, payload.size.toLong())
        }
        assertFalse(out.exists())
    }

    @Test
    fun corruptOrTruncatedArchiveFailsCleanly() {
        val good = archive(listOf("package/bin/codex" to payload))
        val bytes = good.readBytes()
        val truncated = folder.newFile().apply { writeBytes(bytes.copyOf(bytes.size / 2)) }
        val garbage = folder.newFile().apply { writeBytes(ByteArray(2_000) { 7 }) }
        listOf(truncated, garbage).forEach { broken ->
            val out = File(folder.root, "out-${broken.name}/codex")
            assertThrows(Exception::class.java) {
                CodexArchive.extractEntry(broken, "package/bin/codex", out, payload.size.toLong())
            }
            assertFalse(out.exists())
        }
    }

    @Test
    fun freeSpaceCheckComparesAgainstTheRequirement() {
        assertTrue(CodexArchive.hasFreeSpace(folder.root, 0L))
        assertFalse(CodexArchive.hasFreeSpace(folder.root, Long.MAX_VALUE))
    }

    @Test
    fun pinnedReleaseConstantsAreConsistent() {
        assertEquals(128, CodexInstallSpec.ARCHIVE_SHA512.length)
        assertTrue(CodexInstallSpec.ARCHIVE_SHA512.all { it in "0123456789abcdef" })
        assertTrue(CodexInstallSpec.ARCHIVE_URL.contains(CodexInstallSpec.VERSION))
        assertTrue(CodexInstallSpec.ARCHIVE_URL.startsWith("https://registry.npmjs.org/"))
        assertTrue(CodexInstallSpec.BINARY_ENTRY.contains("aarch64"))
        assertTrue(CodexInstallSpec.REQUIRED_FREE_BYTES > CodexInstallSpec.ARCHIVE_BYTES + CodexInstallSpec.BINARY_BYTES)
    }
}
