package com.jarves.mh.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.jarves.mh.mods.InstalledMod
import com.jarves.mh.mods.ModHost
import com.jarves.mh.mods.ModManifestParser
import com.jarves.mh.mods.ModWebPanel
import com.jarves.mh.ui.kit.PocketButton
import com.jarves.mh.ui.kit.PocketButtonStyle
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType
import kotlinx.coroutines.flow.Flow
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * A mod's web panel (mod plan row 12, spec v1 §8.4/§8.5): one hardened WebView showing an HTML
 * file of the mod from its own origin `https://<mod id>.mod.invalid/`. The page reaches nothing
 * else: no files, no network (every other request is refused and the CSP allows only the mod's
 * own files), no new windows or permissions. It talks to the mod's code only through the message
 * port the app hands it after load. When its renderer dies, the panel says so and offers a
 * reload; the app keeps running. The caller shows at most one at a time.
 */
@Composable
fun ModWebPanelView(
    mod: InstalledMod,
    panel: ModWebPanel,
    fromMod: Flow<String>,
    toMod: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var stopped by remember(mod.id, panel.id) { mutableStateOf(false) }
    var generation by remember(mod.id, panel.id) { mutableIntStateOf(0) }
    val colors = PocketColors.current
    if (stopped) {
        Column(
            modifier.fillMaxSize().padding(PocketSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PocketSpacing.md, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("This panel stopped. The app and your other mods are fine.", style = PocketType.body, color = colors.secondaryLabel)
            PocketButton("Reload", { stopped = false; generation++ }, style = PocketButtonStyle.Tinted)
        }
        return
    }
    key(generation) {
        val holder = remember { ModWebPanels.Holder() }
        LaunchedEffect(holder) { fromMod.collect { holder.post(it) } }
        DisposableEffect(holder) { onDispose { holder.close() } }
        AndroidView(
            factory = { context -> ModWebPanels.create(context, mod, panel, holder, onMessage = toMod, onStopped = { stopped = true }) },
            onRelease = { view -> holder.close(); runCatching { view.stopLoading(); view.destroy() } },
            modifier = modifier,
        )
    }
}

internal object ModWebPanels {
    /** Each mod's own origin, so storage and the message port never cross mods. */
    fun origin(modId: String): String = "https://$modId.mod.invalid"

    private const val CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
        "connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'"
    /** Messages a page may send its mod per second; more are dropped. */
    private const val MESSAGES_PER_SECOND = 20

    /** The app's end of the panel's message port. Main thread. */
    class Holder {
        var port: WebMessagePort? = null
        private val queued = ArrayDeque<String>()
        var closed = false
            private set

        fun post(text: String) {
            if (closed) return
            val open = port
            if (open == null) { if (queued.size < 32) queued.addLast(text); return }
            runCatching { open.postMessage(WebMessage(text)) }
        }

        fun attach(open: WebMessagePort) {
            port = open
            while (queued.isNotEmpty()) runCatching { open.postMessage(WebMessage(queued.removeFirst())) }
        }

        fun close() {
            closed = true
            runCatching { port?.close() }
            port = null
            queued.clear()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: Context,
        mod: InstalledMod,
        panel: ModWebPanel,
        holder: Holder,
        onMessage: (String) -> Unit,
        onStopped: () -> Unit,
    ): WebView = WebView(context).apply {
        val origin = origin(mod.id)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
        }
        setDownloadListener(null)
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean = false
        }
        var windowStart = 0L
        var windowCount = 0
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https" || request.url.host != Uri.parse(origin).host

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                serve(mod, request.url, origin)

            override fun onPageFinished(view: WebView, url: String?) {
                if (holder.closed || holder.port != null || url?.startsWith("$origin/") != true) return
                val (mine, theirs) = runCatching { view.createWebMessageChannel() }.getOrNull() ?: return
                mine.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                    override fun onMessage(port: WebMessagePort, message: WebMessage?) {
                        val text = message?.data ?: return
                        val now = System.currentTimeMillis()
                        if (now - windowStart >= 1_000) { windowStart = now; windowCount = 0 }
                        if (++windowCount > MESSAGES_PER_SECOND || text.length > ModHost.MAX_PANEL_MESSAGE) return
                        onMessage(text)
                    }
                }, Handler(Looper.getMainLooper()))
                runCatching { view.postWebMessage(WebMessage("mh-port", arrayOf(theirs)), Uri.parse(origin)) }
                holder.attach(mine)
            }

            // The page's renderer died (a crash or memory): drop this WebView and say so, never crash the app.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                holder.close()
                onStopped()
                return true
            }
        }
        loadUrl("$origin/${panel.entry}")
    }

    /** Serves files of the installed package only, each with the panel CSP; everything else is refused. */
    internal fun serve(mod: InstalledMod, url: Uri, origin: String): WebResourceResponse {
        if (url.scheme != "https" || url.host != Uri.parse(origin).host) return refused()
        val path = url.path.orEmpty().removePrefix("/")
        if (!ModManifestParser.isSafeRelativePath(path)) return refused()
        val base = mod.dir.canonicalFile
        val file = File(base, path)
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || !file.canonicalPath.startsWith(base.path + File.separator) ||
            file.length() > 5L * 1024 * 1024) return refused()
        val headers = mapOf("Content-Security-Policy" to CSP, "X-Content-Type-Options" to "nosniff", "Cache-Control" to "no-store")
        return WebResourceResponse(mimeType(path), "utf-8", 200, "OK", headers, file.inputStream())
    }

    private fun refused() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "txt", "md" -> "text/plain"
        else -> "application/octet-stream"
    }
}
