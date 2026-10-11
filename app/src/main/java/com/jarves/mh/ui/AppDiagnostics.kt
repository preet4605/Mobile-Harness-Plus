package com.jarves.mh.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A one-off snapshot for the Diagnostics section in Settings. It is collected off the main thread
 * only when the section is shown or the user taps Refresh; nothing polls it.
 */
data class AppDiagnostics(
    val loading: Boolean = false,
    val appPssBytes: Long = 0L,
    val javaHeapUsedBytes: Long = 0L,
    val javaHeapMaxBytes: Long = 0L,
    /** Runtime work the app tracks right now, e.g. "Agent task (Claude Code)" or "Project terminal". */
    val activeProcesses: List<String> = emptyList(),
    val chatMessageCount: Int = 0,
    val liveActivityCount: Int = 0,
    val terminalLineCount: Int = 0,
    val terminalOutputBytes: Long = 0L,
    val runtimeCacheBytes: Long = 0L,
    val runtimeCacheFiles: Int = 0,
    /** The most recent abnormal app exit, or null when none is recorded or the system cannot report it. */
    val recentExit: String? = null,
    val refreshedAtMillis: Long? = null,
)

/** Prefix of the per-process output files [com.jarves.mh.runtime.RuntimeInstaller.process] writes to the cache directory. */
internal const val RUNTIME_OUTPUT_PREFIX = "runtime-output-"
internal const val RUNTIME_OUTPUT_SUFFIX = ".log"

/** A runtime output file left by a finished process this long ago may be removed. */
internal const val RUNTIME_OUTPUT_IDLE_MS = 60_000L

/** True for a runtime output log (not its capture FIFO) that is a candidate for the cache size. */
internal fun isRuntimeOutputName(name: String): Boolean =
    name.startsWith(RUNTIME_OUTPUT_PREFIX) && name.endsWith(RUNTIME_OUTPUT_SUFFIX)

/**
 * Whether a runtime output file is safe to delete: it is a plain runtime output log, no descriptor in
 * this process still has it open, and nothing has written to it for [RUNTIME_OUTPUT_IDLE_MS].
 */
internal fun isDisposableRuntimeOutput(
    name: String,
    isRegularFile: Boolean,
    lastModifiedMillis: Long,
    nowMillis: Long,
    openInProcess: Boolean,
): Boolean = isRegularFile && isRuntimeOutputName(name) && !openInProcess &&
    nowMillis - lastModifiedMillis >= RUNTIME_OUTPUT_IDLE_MS

/** Counts UTF-8 storage without allocating an encoded copy of a large buffer. */
internal fun utf8SizeInBytes(value: String): Long {
    var bytes = 0L
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char.code < 0x80 -> bytes += 1
            char.code < 0x800 -> bytes += 2
            char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                bytes += 4
                index += 1
            }
            else -> bytes += 3
        }
        index += 1
    }
    return bytes
}

internal fun formatDiagnosticBytes(bytes: Long): String = when {
    bytes < 0L -> "0 B"
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
    bytes < 1_073_741_824L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
}

// Values of android.app.ApplicationExitInfo.REASON_*, kept here so the formatting stays pure.
internal const val EXIT_REASON_LOW_MEMORY = 3
internal const val EXIT_REASON_CRASH = 4
internal const val EXIT_REASON_CRASH_NATIVE = 5
internal const val EXIT_REASON_ANR = 6
internal const val EXIT_REASON_EXCESSIVE_RESOURCE_USAGE = 9

/** A short label for an abnormal exit reason, or null for normal exits the section does not report. */
internal fun abnormalExitLabel(reason: Int): String? = when (reason) {
    EXIT_REASON_ANR -> "App not responding"
    EXIT_REASON_CRASH -> "Crash"
    EXIT_REASON_CRASH_NATIVE -> "Native crash"
    EXIT_REASON_LOW_MEMORY -> "Low memory"
    EXIT_REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource use"
    else -> null
}

private const val MAX_EXIT_DESCRIPTION_CHARS = 160

/** "Low memory · 2026-10-06 12:23 · detail", or null when [reason] is not an abnormal exit. */
internal fun formatExitSummary(
    reason: Int,
    timestampMillis: Long,
    description: String?,
    timeZone: TimeZone = TimeZone.getDefault(),
): String? {
    val label = abnormalExitLabel(reason) ?: return null
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { this.timeZone = timeZone }
        .format(Date(timestampMillis))
    val detail = description?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_EXIT_DESCRIPTION_CHARS)
    return if (detail == null) "$label · $time" else "$label · $time · $detail"
}
