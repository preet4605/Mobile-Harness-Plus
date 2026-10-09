package com.jarves.mh.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.system.Os
import android.util.Log
import java.io.File
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Headless Linux browser bridge for PRoot.
 *
 * Forwards URLs opened inside the PRoot environment (e.g. by Claude Code OAuth)
 * directly to the Android browser without modifying any URL parameters or
 * implementing custom OAuth clients.
 */
class AndroidBrowserBridge(
    private val context: Context,
    private val onUrlOpened: ((String) -> Unit)? = null,
) {
    private val bridgeDir = File(context.filesDir, "runtime-bridge")

    fun ensureBridgeInstalled(rootfs: File) {
        bridgeDir.mkdirs()
        val script = File(bridgeDir, "open-url.sh")
        script.writeText(
            """#!/bin/sh
URL="${'$'}1"
if [ -n "${'$'}URL" ]; then
    BRIDGE_DIR="${'$'}(dirname "${'$'}0")"
    FILE="${'$'}{BRIDGE_DIR}/open-url-${'$'}(date +%s%N)-${'$'}${'$'}.url"
    printf '%s' "${'$'}URL" > "${'$'}FILE"
fi
exit 0
""".trimIndent() + "\n",
        )
        runCatching { Os.chmod(script.absolutePath, 0b111101101) }

        val xdgOpen = File(rootfs, "usr/local/bin/xdg-open")
        xdgOpen.parentFile?.mkdirs()
        xdgOpen.writeText(
            """#!/bin/sh
exec /pocket-bridge/open-url.sh "${'$'}@"
""".trimIndent() + "\n",
        )
        runCatching { Os.chmod(xdgOpen.absolutePath, 0b111101101) }
    }

    suspend fun watch(scope: CoroutineScope) {
        bridgeDir.mkdirs()
        while (scope.isActive) {
            val urlFiles = bridgeDir.listFiles { file ->
                file.name.startsWith("open-url-") && file.name.endsWith(".url")
            }.orEmpty()
            for (file in urlFiles) {
                val url = runCatching { file.readText().trim() }.getOrNull()
                file.delete()
                if (!url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                    openBrowser(url)
                }
            }
            delay(150)
        }
    }

    fun openBrowser(url: String) {
        Log.i("BrowserBridge", "Opening browser URL: ${redactedForLog(url)}")
        // A caller-supplied handler owns opening the link (and de-duplicating it); launching
        // the browser here as well would open every link twice.
        if (onUrlOpened != null) {
            onUrlOpened.invoke(url)
            return
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure { error ->
            Log.w("BrowserBridge", "Failed to open browser for ${redactedForLog(url)}: ${error.javaClass.simpleName}")
        }
    }

    companion object {
        const val BROWSER_ENV_PATH = "/pocket-bridge/open-url.sh"

        /**
         * Scheme, host and path only. A browser URL can carry OAuth codes or state in its query or
         * fragment, so logs never get those parts.
         */
        internal fun redactedForLog(url: String): String {
            val uri = runCatching { URI(url) }.getOrNull() ?: return "<unparseable url>"
            val scheme = uri.scheme ?: return "<unparseable url>"
            return "$scheme://${uri.host.orEmpty()}${uri.rawPath.orEmpty()}"
        }
    }
}
