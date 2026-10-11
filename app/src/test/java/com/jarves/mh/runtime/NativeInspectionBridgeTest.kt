package com.jarves.mh.runtime

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeInspectionBridgeTest {
    @Before fun setDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }
    private val context get() = RuntimeEnvironment.getApplication()
    private fun request(token: String, operation: String = "preview_dom"): File =
        File(context.filesDir, "runtime-inspection/$token/${UUID.randomUUID()}.request").apply {
            parentFile!!.mkdirs()
            writeText(JSONObject().put("token", token).put("operation", operation).put("args", JSONObject()).toString())
        }
    private fun response(file: File) = JSONObject(File(file.parentFile, file.name.replace(".request", ".response")).readText())

    @Test fun repliesAreScopedToTheRegisteredLaunchAndNotRepeated() = runBlocking {
        val sessions = InspectionSessions(); val workspace = File(context.filesDir, "workspaces/project")
        sessions.register("test", workspace, "/workspace/project") { true }
        var calls = 0
        val bridge = NativeInspectionBridge(context, sessions) { scope, _ ->
            assertEquals(workspace.canonicalFile, scope.workspace); assertEquals("/workspace/project", scope.guestRoot)
            calls++; JSONObject().put("ok", true).put("text", "fixture DOM")
        }
        val file = request("test"); bridge.handleFile(file); bridge.handleFile(file)
        assertEquals("fixture DOM", response(file).getString("text")); assertEquals(1, calls)
    }
    @Test fun aTokenFromAnotherLaunchCannotBeUsedInThisDirectory() = runBlocking {
        val sessions = InspectionSessions()
        sessions.register("one", context.filesDir, "/workspace/one") { true }
        sessions.register("two", context.filesDir, "/workspace/two") { true }
        val file = request("one")
        file.writeText(JSONObject().put("token", "two").put("operation", "preview_dom").toString())
        NativeInspectionBridge(context, sessions) { _, _ -> error("Cross-launch request must not be called") }.handleFile(file)
        assertFalse(response(file).getBoolean("ok"))
    }
    @Test fun deadAndUnknownLaunchesCannotInspect() = runBlocking {
        val sessions = InspectionSessions(); sessions.register("dead", context.filesDir, "/workspace/project") { false }
        val bridge = NativeInspectionBridge(context, sessions) { _, _ -> error("Must not be called") }
        for (token in listOf("dead", "unknown")) {
            val file = request(token); bridge.handleFile(file); assertFalse(response(file).getBoolean("ok"))
        }
    }
    @Test fun launchTerminationAndGuestCancellationDiscardPendingResults() = runBlocking {
        val sessions = InspectionSessions(); var alive = true
        sessions.register("test", context.filesDir, "/workspace/project") { alive }
        val file = request("test")
        val bridge = NativeInspectionBridge(context, sessions) { _, _ -> alive = false; JSONObject().put("ok", true) }
        bridge.handleFile(file); assertFalse(response(file).getBoolean("ok"))
        alive = true
        val cancelled = request("test")
        val cancelling = NativeInspectionBridge(context, sessions) { _, _ -> cancelled.delete(); JSONObject().put("ok", true) }
        sessions.register("test", context.filesDir, "/workspace/project") { alive }
        cancelling.handleFile(cancelled)
        assertFalse(File(cancelled.parentFile, cancelled.name.replace(".request", ".response")).exists())
    }
    @Test fun escapingRequestLinksAndUnknownOperationsAreRejected() = runBlocking {
        val sessions = InspectionSessions(); sessions.register("test", context.filesDir, "/workspace/project") { true }
        val bridge = NativeInspectionBridge(context, sessions) { _, _ -> error("Must not be called") }
        val unknown = request("test", "evaluate_arbitrary_script"); bridge.handleFile(unknown)
        assertFalse(response(unknown).getBoolean("ok"))
        val outside = File(context.filesDir, "outside-${UUID.randomUUID()}").apply { writeText("private") }
        val link = request("test").also { it.delete(); Files.createSymbolicLink(it.toPath(), outside.toPath()) }
        bridge.handleFile(link)
        assertEquals("private", outside.readText())
        assertFalse(File(link.parentFile, link.name.replace(".request", ".response")).exists())
    }
    @Test fun closedPreviewAndCrossProjectSurfaceRejectInspection() = runBlocking {
        val view = android.webkit.WebView(context)
        val first = File(context.filesDir, "workspaces/first").canonicalFile
        PreviewInspectionHost.attach(view, first, "/workspace/first")
        view.loadUrl("http://127.0.0.1:3000/")
        try {
            val second = InspectionSessions.Scope(File(context.filesDir, "workspaces/second").canonicalFile, "/workspace/second") { true }
            var failed = false
            try { PreviewInspectionHost.inspect(second, "preview_console") } catch (_: IllegalStateException) { failed = true }
            assertTrue(failed)
            PreviewInspectionHost.detach(view)
            failed = false
            try { PreviewInspectionHost.inspect(InspectionSessions.Scope(first, "/workspace/first") { true }, "preview_dom") }
            catch (_: IllegalStateException) { failed = true }
            assertTrue(failed)
        } finally { PreviewInspectionHost.detach(view); view.destroy() }
    }
}
