package com.jarves.mh.runtime

import com.jarves.mh.model.AntigravityAccount
import com.jarves.mh.model.AntigravityAccountStatus
import com.jarves.mh.provider.ProviderFailureClassifier
import com.jarves.mh.ui.sanitizeTerminalOutput
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

const val ANTIGRAVITY_MODELS_TIMEOUT_MILLIS = 30_000L

class AntigravityModelDiscoveryException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal val ANTIGRAVITY_MODEL_EFFORT = Regex("^(.*)-(low|medium|high)$")

internal fun antigravityEffortFromModel(model: String): String? =
    ANTIGRAVITY_MODEL_EFFORT.matchEntire(model)?.groupValues?.get(2)

internal fun antigravityModelWithEffort(model: String, effort: String): String? {
    val match = ANTIGRAVITY_MODEL_EFFORT.matchEntire(model) ?: return null
    return "${match.groupValues[1]}-$effort"
}

private val IGNORED_HEADER_KEYWORDS = setOf(
    "model", "models", "id", "name", "available", "alias",
    "description", "status", "version", "type", "error",
    "warning", "info", "loading", "fetching", "total",
)

private val MODEL_ID_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]+$")

/**
 * Parses CLI output from `agy models`.
 * - Sanitizes ANSI escape and terminal control sequences
 * - Strips leading bullets or markers (*, -, •, >)
 * - Rejects obvious headers, noise, and non-model lines
 * - Removes duplicates while preserving CLI order
 */
internal fun parseAntigravityModelList(rawOutput: String): List<String> {
    if (rawOutput.isBlank()) return emptyList()
    val clean = sanitizeTerminalOutput(rawOutput)
    val result = mutableListOf<String>()

    for (rawLine in clean.lines()) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed.startsWith("#") || trimmed.startsWith("//") || trimmed.startsWith("---") || trimmed.startsWith("===")) continue
        if (trimmed.endsWith(":")) continue

        val lower = trimmed.lowercase()
        if (lower.startsWith("connecting to") || lower.startsWith("loaded cached") ||
            lower.startsWith("using account") || lower.startsWith("welcome to")
        ) {
            continue
        }

        val stripped = trimmed
            .removePrefix("*")
            .removePrefix("-")
            .removePrefix("•")
            .removePrefix(">")
            .trim()
        if (stripped.isEmpty()) continue

        val token = stripped.split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
        if (token.isEmpty()) continue
        if (token.lowercase() in IGNORED_HEADER_KEYWORDS) continue
        if (MODEL_ID_REGEX.matches(token)) {
            result.add(token)
        }
    }

    return result.distinct()
}

/**
 * Reconciles model selection and effort after a model refresh:
 * - Keeps the currently selected model if it still exists
 * - Reconciles effort if the model/effort was configured
 * - Otherwise falls back to the first available model
 */
internal fun reconcileAntigravityModelSelection(
    currentModel: String,
    currentEffort: String,
    availableModels: List<String>,
): Pair<String, String> {
    if (availableModels.isEmpty()) return currentModel to currentEffort
    val preferred = antigravityModelWithEffort(currentModel, currentEffort)?.takeIf(availableModels::contains)
    val selected = preferred
        ?: currentModel.takeIf(availableModels::contains)
        ?: availableModels.first()
    val selectedEffort = antigravityEffortFromModel(selected) ?: currentEffort
    return selected to selectedEffort
}

/**
 * Redacts secrets, access tokens, and refresh tokens from CLI error output.
 */
internal fun redactSensitiveOutput(text: String): String {
    var redacted = ProviderFailureClassifier.redact(text)
    redacted = redacted.replace(Regex("(?i)ya29\\.[A-Za-z0-9._~+/=-]+"), "ya29.••••")
    redacted = redacted.replace(Regex("(?i)(refresh_token[\"':\\s=]+)[A-Za-z0-9._~+/=-]{10,}")) {
        "${it.groupValues[1]}••••"
    }
    return redacted
}

/**
 * Maps non-zero CLI exits and error output to human-friendly, actionable messages.
 */
internal fun mapAntigravityDiscoveryError(exitCode: Int, rawOutput: String): String {
    val clean = sanitizeTerminalOutput(rawOutput).trim()
    return when {
        isAuthError(clean) -> "Antigravity authentication failed. Reconnect your Google account and try again."
        isNetworkError(clean) -> "Could not refresh Antigravity models because the network connection was interrupted."
        isQuotaError(clean) -> "Antigravity quota exhausted. Check your account quota and try again."
        else -> {
            val redacted = redactSensitiveOutput(clean)
            val preview = redacted.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(3)
                .joinToString(" ")
                .take(200)
            if (preview.isNotBlank()) {
                "Could not list Antigravity models: $preview"
            } else {
                "Could not list Antigravity models (exit $exitCode)"
            }
        }
    }
}

/**
 * Selects a usable Antigravity account for model discovery.
 * Reconciles account state via reload() first.
 * Returns null if no usable account exists.
 */
internal fun selectUsableAntigravityAccount(accountManager: AntigravityAccountManager): AntigravityAccount? {
    accountManager.reload()
    val fromTurn = accountManager.selectAccountForTurn()
    if (fromTurn != null) return fromTurn

    // Fallback: If selectAccountForTurn() returned null (e.g. all accounts hit turn quota cooldown),
    // check for any configured account that is not DISABLED and not AUTH_ERROR
    val primary = accountManager.getPrimaryAccount()
    if (primary != null && primary.status != AntigravityAccountStatus.DISABLED && primary.status != AntigravityAccountStatus.AUTH_ERROR) {
        return primary
    }

    return accountManager.accountsList().firstOrNull {
        it.status != AntigravityAccountStatus.DISABLED && it.status != AntigravityAccountStatus.AUTH_ERROR
    }
}

/**
 * Core model discovery workflow.
 * Usable with arbitrary CLI runner for testing without a real Android subprocess.
 */
internal suspend fun executeAntigravityModelDiscovery(
    accountManager: AntigravityAccountManager,
    runner: suspend (env: Map<String, String>) -> Pair<Int, String>,
): List<String> {
    val account = selectUsableAntigravityAccount(accountManager)
        ?: throw AntigravityModelDiscoveryException("No usable Antigravity account. Sign in or reconnect an account first.")

    accountManager.getAccountAccessToken(account.id)
    val env = mapOf("HOME" to accountManager.getAccountHomeGuestPath(account.id))

    val (exitCode, rawOutput) = runner(env)

    if (exitCode != 0) {
        val errorMsg = mapAntigravityDiscoveryError(exitCode, rawOutput)
        throw AntigravityModelDiscoveryException(errorMsg)
    }

    val models = parseAntigravityModelList(rawOutput)
    if (models.isEmpty()) {
        val clean = sanitizeTerminalOutput(rawOutput).trim()
        val errorMsg = when {
            isAuthError(clean) -> "Antigravity authentication failed. Reconnect your Google account and try again."
            isNetworkError(clean) -> "Could not refresh Antigravity models because the network connection was interrupted."
            isQuotaError(clean) -> "Antigravity quota exhausted. Check your account quota and try again."
            else -> "Antigravity returned no models"
        }
        throw AntigravityModelDiscoveryException(errorMsg)
    }

    return models
}

/**
 * Discovers Antigravity models using the installed RuntimeInstaller proot environment.
 * Executes boundedly with timeouts, cleans up processes on timeout/cancellation,
 * and deletes temporary output files.
 */
suspend fun discoverAntigravityModels(
    installer: RuntimeInstaller,
    accountManager: AntigravityAccountManager,
    workspaceDir: File,
    cacheDir: File? = null,
    timeoutMillis: Long = ANTIGRAVITY_MODELS_TIMEOUT_MILLIS,
): List<String> {
    return executeAntigravityModelDiscovery(accountManager) { env ->
        val runtime = installer.installedRuntime()
        workspaceDir.mkdirs()
        val targetCacheDir = cacheDir ?: workspaceDir.parentFile ?: workspaceDir
        val outputFile = File(targetCacheDir, "agy-models-${System.nanoTime()}.log")
        var process: Process? = null
        var rawOutput = ""
        var exitCode = -1
        try {
            try {
                withTimeout(timeoutMillis) {
                    val proc = installer.process(
                        runtime.proot,
                        runtime.rootfs,
                        workspaceDir,
                        env,
                        listOf(RuntimeInstaller.AGY_GUEST_PATH, "models"),
                        guestWorkspacePath = "/workspace/antigravity-models",
                        emulateHardLinks = false,
                        outputFile = outputFile,
                    )
                    process = proc
                    while (proc.isAlive) {
                        delay(50)
                    }
                    exitCode = proc.waitFor()
                }
            } catch (e: TimeoutCancellationException) {
                throw AntigravityModelDiscoveryException(
                    "Antigravity model refresh timed out. Check your connection and try again.",
                    cause = e,
                )
            } finally {
                withContext(NonCancellable) {
                    if (process?.isAlive == true) {
                        process?.destroy()
                        runCatching {
                            delay(50)
                            if (process?.isAlive == true) {
                                process?.destroyForcibly()
                            }
                        }
                    }
                }
            }
            rawOutput = outputFile.takeIf { it.isFile }?.readText().orEmpty()
        } finally {
            runCatching { outputFile.delete() }
        }
        Pair(exitCode, rawOutput)
    }
}
