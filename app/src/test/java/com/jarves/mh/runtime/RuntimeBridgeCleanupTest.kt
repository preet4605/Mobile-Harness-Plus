package com.jarves.mh.runtime

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeBridgeCleanupTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun cleanupKeepsScriptsAndWatchedDirectories() {
        val bridge = temp.newFolder()
        val script = File(bridge, "open-url.sh").apply { writeText("browser script") }
        val inspection = File(bridge, "inspection").apply { mkdir() }
        val queued = File(bridge, "open-url-stale.url").apply { writeText("https://example.test/") }
        val other = File(bridge, "custom-file").apply { writeText("unrelated") }
        AndroidBrowserBridge.clearPendingRequests(bridge)
        AndroidBrowserBridge.clearPendingRequests(bridge)
        assertFalse(queued.exists()); assertEquals("browser script", script.readText())
        assertTrue(inspection.isDirectory); assertEquals("unrelated", other.readText())
    }
}
