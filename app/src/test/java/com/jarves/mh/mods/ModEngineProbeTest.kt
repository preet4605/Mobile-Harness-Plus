package com.jarves.mh.mods

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ModEngineProbeTest {

    @Test
    fun `test modules reach the asked size and report their handler count`() {
        val module = ModEngineProbe.moduleOfSize(64 * 1024)
        assertTrue(module.length >= 64 * 1024)
        assertTrue(module.length < 64 * 1024 + 200)
        assertTrue(module.endsWith("String(__mod.length)"))
    }

    @Test
    fun `the shared report marks failures and keeps groups together`() {
        val report = EngineReport(
            listOf(
                EngineCheck("Phone", "Model", "Pixel"),
                EngineCheck("Support", "Stop a running script", "No", passed = false),
                EngineCheck("Speed", "Script round trip", "median 1.00 ms, p95 2.00 ms over 100", passed = true),
                EngineCheck("Support", "Mod log", "Yes", passed = true),
            ),
            ready = false,
            verdict = "Not ready: stop a running script",
            finishedAt = Instant.parse("2026-10-13T12:00:00Z"),
        )
        val text = report.text()
        assertTrue(text, "Verdict: Not ready: stop a running script" in text)
        assertTrue(text, "Support\n  [FAIL] Stop a running script: No\n  [ok] Mod log: Yes" in text)
        assertTrue(text, "[-] Model: Pixel" in text)
    }
}
