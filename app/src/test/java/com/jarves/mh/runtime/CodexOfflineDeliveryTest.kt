package com.jarves.mh.runtime

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CodexOfflineDeliveryTest {
    @Test fun offlineCodexUsesThePinnedArchiveAndNeverDownloads() {
        val root = if (File("src/main/java").isDirectory) File("..") else File(".")
        val source = File(root, "app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt").readText()
        val install = source.substringAfter("private suspend fun ensureCodexInstalled").substringBefore("private suspend fun ensureDshInstalled")
        assertFalse(install.contains("Codex is too large to bundle"))
        val offline = install.substringAfter("if (BuildConfig.OFFLINE_RUNTIME_BUNDLES)").substringBefore("} else {")
        assertTrue(offline.contains("context.assets.open"))
        assertTrue(offline.contains("CodexInstallSpec.ARCHIVE_SHA512"))
        assertTrue(offline.contains("CodexInstallSpec.ARCHIVE_BYTES"))
        assertFalse(offline.contains("downloadVerified"))
    }
}
