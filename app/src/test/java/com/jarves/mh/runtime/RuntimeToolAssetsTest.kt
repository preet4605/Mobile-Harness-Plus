package com.jarves.mh.runtime

import java.io.File
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeToolAssetsTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun repeatedManagedToolsInstallationPreservesLoginAndConfiguration() {
        val root = temp.newFolder()
        val home = File(root, "root/.codex").apply { mkdirs() }
        val auth = File(home, "auth.json").apply { writeText("fixture login") }
        val config = File(home, "config.toml").apply { writeText("fixture custom MCP configuration") }
        val context = RuntimeEnvironment.getApplication()
        val installer = RuntimeInstaller(context)
        assertTrue(installer.ensureInspectionTools(root))
        val script = File(root, "opt/pocket/harness/harness-tools.cjs")
        val modified = script.lastModified()
        assertTrue(installer.ensureInspectionTools(root))
        assertEquals(modified, script.lastModified())
        assertEquals("fixture login", auth.readText()); assertEquals("fixture custom MCP configuration", config.readText())
        assertTrue(File(root, "opt/pocket/tools/rg").length() > 0)
        assertTrue(File(root, "usr/local/bin/harness-tools").readText().contains("\"${'$'}@\""))
    }
    @Test fun corruptSearchAssetIsRejectedWithoutPublishingAnExecutable() {
        val root = temp.newFolder()
        assertFalse(RuntimeSearchTool.ensure(root) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })
        assertFalse(File(root, "opt/pocket/tools/rg").exists())
        assertTrue(File(root, "opt/pocket/tools").list().orEmpty().isEmpty())
    }
}
