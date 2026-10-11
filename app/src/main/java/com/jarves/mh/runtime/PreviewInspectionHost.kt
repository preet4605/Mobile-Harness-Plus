package com.jarves.mh.runtime

import android.graphics.Bitmap
import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.ConsoleMessage
import android.webkit.WebView
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Accessed on Main. The registered surface disappears when its project tab is released. */
internal object PreviewInspectionHost {
    private data class Surface(val view: WeakReference<WebView>, val workspace: File, val guestRoot: String,
        var revision: Long = 0, val console: ArrayDeque<String> = ArrayDeque())
    private var active: Surface? = null
    private var capturePending = false
    fun attach(view: WebView, workspace: File, guestRoot: String) {
        val root = workspace.canonicalFile
        if (active?.let { it.view.get() === view && it.workspace == root && it.guestRoot == guestRoot } == true) return
        active = Surface(WeakReference(view), root, guestRoot)
    }
    fun detach(view: WebView) { if (active?.view?.get() === view) active = null }
    fun navigation(view: WebView) { active?.takeIf { it.view.get() === view }?.let { it.revision++; it.console.clear() } }
    fun console(view: WebView, message: ConsoleMessage) {
        active?.takeIf { it.view.get() === view }?.console?.apply {
            if (size >= 32) removeFirst()
            addLast("${message.messageLevel()}: ${message.message()}".take(256))
        }
    }
    private fun valid(surface: Surface, scope: InspectionSessions.Scope, revision: Long): Boolean =
        active === surface && surface.revision == revision && surface.workspace == scope.workspace &&
            surface.guestRoot == scope.guestRoot && surface.view.get()?.let { view ->
                val uri = runCatching { URI(view.url.orEmpty()) }.getOrNull()
                uri?.scheme in setOf("http", "https") && uri?.host in setOf("localhost", "127.0.0.1", "[::1]", "0.0.0.0") && uri?.userInfo == null
            } == true

    suspend fun inspect(scope: InspectionSessions.Scope, operation: String): JSONObject {
        val (surface, revision) = withContext(Dispatchers.Main.immediate) {
            val surface = active ?: error("Preview is closed")
            surface to surface.revision
        }
        val result = when (operation) {
            "preview_dom" -> withContext(Dispatchers.Main.immediate) {
                check(valid(surface, scope, revision))
                val text = suspendCancellableCoroutine<String> { continuation ->
                    surface.view.get()!!.evaluateJavascript("JSON.stringify({title:document.title.slice(0,512),dom:document.documentElement.outerHTML.slice(0,8192)})") { value ->
                        if (continuation.isActive) runCatching { JSONArray("[$value]").optString(0).take(12_000) }
                            .fold(continuation::resume, continuation::resumeWithException)
                    }
                }
                JSONObject().put("ok", true).put("text", text)
            }
            "preview_console" -> withContext(Dispatchers.Main.immediate) {
                check(valid(surface, scope, revision))
                JSONObject().put("ok", true).put("text", surface.console.joinToString("\n").take(8_192))
            }
            "preview_screenshot" -> {
                var ownedBitmap: Bitmap? = null
                var copyPending = false
                val bytes = try {
                    val bitmap = withContext(Dispatchers.Main.immediate) {
                        check(valid(surface, scope, revision))
                        val view = surface.view.get()!!
                        check(view.width > 0 && view.height > 0)
                        check(!capturePending) { "A viewport capture is still pending" }
                        var context = view.context
                        while (context is ContextWrapper && context !is Activity) context = context.baseContext
                        val window = (context as? Activity)?.window ?: error("Preview window unavailable")
                        val scale = minOf(1f, 1024f / view.width, 1024f / view.height)
                        val bitmap = Bitmap.createBitmap(maxOf(1, (view.width * scale).toInt()), maxOf(1, (view.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                        ownedBitmap = bitmap
                        val location = IntArray(2); view.getLocationInWindow(location)
                        suspendCancellableCoroutine<Unit> { continuation ->
                            capturePending = true; copyPending = true
                            try {
                                PixelCopy.request(window, Rect(location[0], location[1], location[0] + view.width, location[1] + view.height), bitmap, { status ->
                                    capturePending = false; copyPending = false
                                    if (continuation.isActive) {
                                        if (status == PixelCopy.SUCCESS) continuation.resume(Unit)
                                        else continuation.resumeWithException(IllegalStateException("Preview pixels are unavailable"))
                                    } else bitmap.recycle()
                                }, Handler(Looper.getMainLooper()))
                            } catch (error: Exception) { capturePending = false; copyPending = false; throw error }
                        }
                        bitmap
                    }
                    withContext(Dispatchers.IO) { ByteArrayOutputStream().use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.toByteArray()
                    } }
                } finally {
                    // PixelCopy owns the buffer until its callback, including after cancellation.
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                        if (!copyPending) ownedBitmap?.recycle()
                    }
                }
                check(bytes.size <= 2 * 1024 * 1024)
                withContext(Dispatchers.Main.immediate) { check(valid(surface, scope, revision)) }
                withContext(Dispatchers.IO) {
                    val directory = File(scope.workspace, ".harness/preview")
                    check(directory.canonicalFile.toPath().startsWith(scope.workspace.toPath()))
                    check(directory.isDirectory || directory.mkdirs())
                    check(directory.listFiles().orEmpty().sumOf(File::length) + bytes.size <= 16 * 1024 * 1024)
                    val file = File(directory, "${UUID.randomUUID()}.png")
                    Files.write(file.toPath(), bytes, CREATE_NEW, WRITE)
                    JSONObject().put("ok", true).put("imagePath", "${scope.guestRoot}/.harness/preview/${file.name}")
                        .put("text", "Preview viewport screenshot saved in ${scope.guestRoot}/.harness/preview/${file.name}")
                }
            }
            else -> error("Unknown Preview operation")
        }
        withContext(Dispatchers.Main.immediate) { check(valid(surface, scope, revision)) }
        return result
    }
}
