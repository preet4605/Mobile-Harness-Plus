package com.jarves.mh.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidBrowserBridgeLogTest {
    @Test fun openBrowserLogsSchemeHostAndPathNotTheRawUrl() {
        val root = if (File("src/main/java").isDirectory) File("..") else File(".")
        val source = File(root, "app/src/main/java/com/jarves/mh/runtime/AndroidBrowserBridge.kt").readText()
        val openLog = source.lines().single { it.contains("Opening browser URL") }
        assertFalse("the browser log must not carry the raw URL: $openLog", openLog.contains("\$url"))
    }

    @Test fun redactedForLogKeepsOnlySchemeHostAndPath() {
        assertEquals(
            "https://claude.ai/oauth/authorize",
            AndroidBrowserBridge.redactedForLog("https://claude.ai/oauth/authorize?code_challenge=abc&state=def#frag"),
        )
        assertEquals(
            "https://example.test/cb",
            AndroidBrowserBridge.redactedForLog("https://user:pw@example.test:8443/cb?token=x"),
        )
        assertEquals("<unparseable url>", AndroidBrowserBridge.redactedForLog("https://bad host/?x=1"))
    }
}
