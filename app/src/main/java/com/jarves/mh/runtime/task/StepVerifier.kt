package com.jarves.mh.runtime.task

import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.runtime.NativeSpawnProcess
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Result of deterministic runtime verification for an ExecutionStep.
 */
data class StepVerificationResult(
    val passed: Boolean,
    val summary: String,
    val failureReason: String? = null,
    val unverified: Boolean = false
)

/**
 * Exception thrown when trusted runtime verification for an ExecutionStep fails.
 */
class StepVerificationException(
    val step: ExecutionStep,
    val reason: String
) : RuntimeException("Step '${step.title}' verification failed: $reason")

/**
 * Exception thrown when step execution fails and recovery attempts are exhausted or unavailable.
 */
class StepExecutionException(
    val step: ExecutionStep,
    val reason: String
) : RuntimeException("Step '${step.title}' execution failed: $reason")

/**
 * Interface for verifying completion invariants of an ExecutionStep.
 * Agent self-reporting and process exit codes are not proof of completion.
 */
interface StepVerifier {
    fun verify(task: CanonicalTask, step: ExecutionStep, workspaceDir: File?): StepVerificationResult
}

/**
 * Validator enforcing path safety within the intended workspace boundary.
 * Rejects absolute paths, path traversal ('..'), blank paths, and workspace escapes.
 */
object StepPathValidator {
    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    fun validateRelativePath(path: String, workspaceDir: File?): ValidationResult {
        if (path.isBlank()) {
            return ValidationResult.Invalid("Path cannot be blank")
        }
        if (path.startsWith("/") || path.startsWith("\\") || File(path).isAbsolute) {
            return ValidationResult.Invalid("Absolute paths are forbidden: '$path'")
        }
        if (path.contains(":") && !path.startsWith("./")) {
            return ValidationResult.Invalid("Absolute or drive-qualified paths are forbidden: '$path'")
        }
        if (path.contains('\u0000')) {
            return ValidationResult.Invalid("Path contains invalid characters")
        }
        val segments = path.split('/', '\\')
        if (segments.any { it == ".." }) {
            return ValidationResult.Invalid("Path traversal (..) is forbidden: '$path'")
        }
        if (workspaceDir == null || !workspaceDir.exists()) {
            return ValidationResult.Invalid("Workspace directory does not exist or is invalid")
        }
        val canonicalWs = workspaceDir.canonicalFile
        val canonicalFile = File(workspaceDir, path).canonicalFile
        if (!canonicalFile.path.startsWith(canonicalWs.path + File.separator) && canonicalFile != canonicalWs) {
            return ValidationResult.Invalid("Path '$path' escapes workspace boundary: '$canonicalFile'")
        }
        return ValidationResult.Valid
    }
}

/**
 * Validator enforcing execution confinement for verification commands.
 * Rejects path traversal (..), cd / and cd outside workspace, absolute paths
 * pointing outside workspace, and symlink escapes.
 */
object StepCommandValidator {
    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    private val TRAVERSAL_PATTERN = Regex("""(^|[\s"'=<>|;&()`,/\\])\.\.([\s"'=<>|;&()`,/\\]|$)""")
    private val CD_PATTERN = Regex("""\bcd\s*($|\S+)""", RegexOption.IGNORE_CASE)
    private val ABS_PATH_TOKEN_PATTERN = Regex("""(?:^|[\s"'=<>|;&()`,])(/[\w.\-+/~@$]+)""")

    fun validateCommand(command: String, workspaceDir: File?): ValidationResult {
        if (command.isBlank()) {
            return ValidationResult.Invalid("Verification command is blank or invalid")
        }
        if (command.contains('\u0000')) {
            return ValidationResult.Invalid("Verification command contains invalid characters")
        }
        if (workspaceDir != null && !workspaceDir.exists()) {
            return ValidationResult.Invalid("Workspace directory does not exist or is invalid")
        }

        val canonicalWs = (workspaceDir?.takeIf { it.exists() } ?: File(".")).canonicalFile

        // 1. Path traversal rejection (..)
        if (TRAVERSAL_PATTERN.containsMatchIn(command)) {
            return ValidationResult.Invalid("Path traversal (..) is forbidden in verification command: '$command'")
        }

        // 2. cd / and cd outside workspace rejection
        val cdMatches = CD_PATTERN.findAll(command)
        for (match in cdMatches) {
            val target = match.groupValues[1].trim()
            if (target.isEmpty() || target == "/" || target.startsWith("/") || target.startsWith("~") || target == ".." || target.startsWith("../") || target == "\$HOME" || target == "\$ROOT") {
                return ValidationResult.Invalid("cd outside workspace is forbidden in verification command: '$command'")
            }
            val targetFile = File(canonicalWs, target).canonicalFile
            if (!targetFile.path.startsWith(canonicalWs.path + File.separator) && targetFile != canonicalWs) {
                return ValidationResult.Invalid("cd target '$target' escapes workspace boundary in verification command: '$command'")
            }
        }

        // 3. Absolute path outside workspace rejection
        val pathMatches = ABS_PATH_TOKEN_PATTERN.findAll(command)
        for (match in pathMatches) {
            val rawPath = match.groupValues[1]
            if (rawPath == "/dev/null") continue

            val resolved = runCatching { File(rawPath).canonicalPath }.getOrNull() ?: rawPath
            val isInsideWs = resolved.startsWith(canonicalWs.path + File.separator) || resolved == canonicalWs.path
            if (!isInsideWs) {
                return ValidationResult.Invalid("Absolute path outside workspace is forbidden in verification command: '$rawPath'")
            }
        }

        // 4. Workspace symlink escape check for command arguments
        val tokens = command.split(Regex("""[\s"'=<>|;&()`,]+""")).filter { it.isNotBlank() }
        for (token in tokens) {
            if (token.startsWith("-")) continue
            val fileCandidate = File(canonicalWs, token)
            if (fileCandidate.exists()) {
                val canonical = fileCandidate.canonicalFile
                if (!canonical.path.startsWith(canonicalWs.path + File.separator) && canonical != canonicalWs) {
                    return ValidationResult.Invalid("Path '$token' in verification command resolves to symlink escaping workspace boundary: '${canonical.path}'")
                }
            }
        }

        // 5. Audit workspace for symlinks escaping boundary
        val escapingSymlinks = findEscapingSymlinks(canonicalWs)
        if (escapingSymlinks.isNotEmpty()) {
            val first = escapingSymlinks.first()
            return ValidationResult.Invalid("Workspace contains symlink escaping workspace boundary: '${first.name}' -> '${first.canonicalPath}'")
        }

        return ValidationResult.Valid
    }

    fun findEscapingSymlinks(workspaceDir: File, maxDepth: Int = 4, maxFiles: Int = 2000): List<File> {
        val canonicalWs = workspaceDir.canonicalFile
        val escaping = mutableListOf<File>()
        var inspected = 0

        fun walk(dir: File, currentDepth: Int) {
            if (currentDepth > maxDepth || inspected >= maxFiles) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                inspected++
                if (inspected >= maxFiles) return
                if (child.name == ".git" || child.name == ".gradle" || child.name == "build") continue

                try {
                    if (Files.isSymbolicLink(child.toPath())) {
                        val canonical = child.canonicalFile
                        if (!canonical.path.startsWith(canonicalWs.path + File.separator) && canonical != canonicalWs) {
                            escaping.add(child)
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore I/O exceptions on broken nodes
                }

                if (child.isDirectory && !Files.isSymbolicLink(child.toPath())) {
                    walk(child, currentDepth + 1)
                }
            }
        }

        walk(canonicalWs, 0)
        return escaping
    }
}

/**
 * Controlled command runner enforcing workspace confinement for verification execution.
 */
interface StepCommandRunner {
    fun execute(command: String, workingDir: File, timeoutSeconds: Long = 60): Pair<Int, String>

    fun execute(task: CanonicalTask, command: String, workingDir: File, timeoutSeconds: Long): Pair<Int, String> =
        execute(command, workingDir, timeoutSeconds)
}

class ControlledStepCommandRunner(
    private val onProcessStarted: (Process) -> Unit = {},
    private val processLauncher: ((command: String, workingDir: File) -> Process)? = null
) : StepCommandRunner {
    override fun execute(command: String, workingDir: File, timeoutSeconds: Long): Pair<Int, String> {
        val canonicalWs = workingDir.canonicalFile
        if (!canonicalWs.isDirectory) return Pair(-1, "Working directory does not exist: ${canonicalWs.path}")
        val validation = StepCommandValidator.validateCommand(command, canonicalWs)
        if (validation is StepCommandValidator.ValidationResult.Invalid) {
            return Pair(-1, "Command confinement validation failed: ${validation.reason}")
        }

        val workers = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "step-verification-io").apply { isDaemon = true }
        }
        var process: Process? = null
        try {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Verification cancelled")
            val launched = processLauncher?.invoke(command, canonicalWs) ?: launchHostProcess(command, canonicalWs)
            process = launched
            val exit = workers.submit<Int> {
                if (launched is NativeSpawnProcess) {
                    // Use a bounded wait while leaving Stop able to inspect and kill the process.
                    if (!launched.waitFor(timeoutSeconds, TimeUnit.SECONDS)) throw TimeoutException()
                    launched.exitValue()
                } else launched.waitFor()
            }
            onProcessStarted(launched)
            // Verification never needs interactive input. Closing stdin also lets commands reach EOF.
            launched.outputStream.close()
            val nativeCapture = (launched as? NativeSpawnProcess)?.outputFile
            // A native PTY already drains to a bounded capture. Host pipes must be drained while waiting.
            val output = if (nativeCapture == null) workers.submit<String> {
                readBounded(launched.inputStream)
            } else null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            fun remaining() = (deadline - System.nanoTime()).coerceAtLeast(0)
            val exitCode = exit.get(remaining(), TimeUnit.NANOSECONDS)
            val text = if (nativeCapture != null) nativeCapture.inputStream().use { readBounded(it) }
                else output!!.get(remaining(), TimeUnit.NANOSECONDS)
            val escaped = StepCommandValidator.findEscapingSymlinks(canonicalWs)
            return if (escaped.isNotEmpty()) {
                Pair(-1, "Verification command created symlink escaping workspace boundary: ${escaped.first().name}")
            } else Pair(exitCode, text.trim())
        } catch (e: InterruptedException) {
            // runInterruptible propagates this as coroutine cancellation after cleanup.
            throw e
        } catch (_: TimeoutException) {
            return Pair(-1, "Verification command timed out after $timeoutSeconds seconds")
        } catch (e: ExecutionException) {
            return if (e.cause is TimeoutException) Pair(-1, "Verification command timed out after $timeoutSeconds seconds")
                else Pair(-1, e.cause?.message ?: "Verification command failed to execute")
        } catch (e: Exception) {
            return Pair(-1, e.message ?: "Verification command failed to execute")
        } finally {
            // NativeSpawnProcess signals the process group, including verification descendants.
            process?.let { launched ->
                runCatching { terminate(launched) }
                runCatching { launched.inputStream.close() }
                runCatching { launched.errorStream.close() }
                runCatching { launched.outputStream.close() }
            }
            workers.shutdown()
            // Give the wait/reap worker a bounded opportunity to finish after kill.
            try {
                workers.awaitTermination(2, TimeUnit.SECONDS)
            } finally {
                workers.shutdownNow()
                (process as? NativeSpawnProcess)?.outputFile?.delete()
            }
        }
    }

    private fun launchHostProcess(command: String, workspace: File): Process {
        val shellPath = when {
            File("/bin/sh").canExecute() -> "/bin/sh"
            File("/system/bin/sh").canExecute() -> "/system/bin/sh"
            else -> "sh"
        }
        val builder = ProcessBuilder(shellPath, "-c", command).directory(workspace).redirectErrorStream(true)
        val sandboxTmp = File(workspace, ".tmp_step_verify").apply { mkdirs() }
        builder.environment().apply {
            put("PWD", workspace.absolutePath)
            put("HOME", workspace.absolutePath)
            put("TMPDIR", sandboxTmp.absolutePath)
            put("TEMP", sandboxTmp.absolutePath)
            put("TMP", sandboxTmp.absolutePath)
        }
        return builder.start()
    }

    private fun terminate(process: Process) {
        if (process !is NativeSpawnProcess) {
            // JVM host verification uses ProcessHandle; Android production uses native group kill.
            // Reflection keeps this fallback compatible with Android versions without ProcessHandle.
            runCatching {
                val handleClass = Class.forName("java.lang.ProcessHandle")
                val handle = Process::class.java.getMethod("toHandle").invoke(process)
                val descendants = handleClass.getMethod("descendants").invoke(handle) as java.util.stream.Stream<*>
                descendants.use { stream ->
                    stream.toArray().reversed().forEach { child ->
                        handleClass.getMethod("destroyForcibly").invoke(child)
                    }
                }
            }
        }
        process.destroyForcibly()
    }

    private fun readBounded(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var truncated = false
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            val retained = minOf(count, MAX_OUTPUT_BYTES - output.size())
            if (retained > 0) output.write(buffer, 0, retained)
            if (retained < count) truncated = true
        }
        return output.toString("UTF-8") + if (truncated) "\n[Verification output truncated]" else ""
    }

    companion object { const val MAX_OUTPUT_BYTES = 65536 }
}

/**
 * Deterministic trusted verifier enforcing file presence/absence,
 * content regex/substring matching, and verification command exit codes.
 *
 * Enforces strict verification gates:
 * 1. Process exit code 0 alone never completes a step without deterministic criteria.
 * 2. All target paths must be strictly contained within the intended workspace.
 * 3. Security checks reject path traversal, absolute paths, and workspace escapes.
 */
class DefaultStepVerifier(
    private val commandRunner: ((command: String, workingDir: File) -> Pair<Int, String>)? = null,
    private val confinementRunner: StepCommandRunner = ControlledStepCommandRunner(),
    val commandTimeoutSeconds: Long = 60
) : StepVerifier {

    override fun verify(task: CanonicalTask, step: ExecutionStep, workspaceDir: File?): StepVerificationResult {
        // Validate verification command if explicitly provided
        if (step.verificationCommand != null) {
            if (step.verificationCommand.isBlank()) {
                return StepVerificationResult(
                    passed = false,
                    summary = "",
                    failureReason = "Verification command is blank or invalid"
                )
            }
            if (step.verificationCommand.contains('\u0000')) {
                return StepVerificationResult(
                    passed = false,
                    summary = "",
                    failureReason = "Verification command contains invalid characters"
                )
            }
        }

        // 0. Verification Criteria Presence Gate:
        // Process exit code 0 alone must never complete a step without deterministic criteria.
        val meaningfulCommand = !step.verificationCommand.isNullOrBlank() &&
            step.verificationCommand.trim() !in setOf("true", ":", "exit 0")
        val hasCriteria = step.expectedFiles.isNotEmpty() ||
            step.forbiddenFiles.isNotEmpty() ||
            step.expectedContent.isNotEmpty() ||
            meaningfulCommand

        if (!hasCriteria) {
            return StepVerificationResult(
                passed = false,
                summary = "Unverified: no deterministic verification criteria for step '${step.title}'",
                failureReason = "No verification criteria defined for step '${step.title}'. Process exit code 0 alone cannot complete a step without deterministic verification.",
                unverified = true
            )
        }

        // 1. Expected files must exist, be regular files, and reside safely within workspace
        if (step.expectedFiles.isNotEmpty()) {
            if (workspaceDir == null || !workspaceDir.exists()) {
                return StepVerificationResult(
                    passed = false,
                    summary = "",
                    failureReason = "Workspace directory does not exist for expected file verification: ${step.expectedFiles}"
                )
            }
            for (path in step.expectedFiles) {
                val validation = StepPathValidator.validateRelativePath(path, workspaceDir)
                if (validation is StepPathValidator.ValidationResult.Invalid) {
                    return StepVerificationResult(
                        passed = false,
                        summary = "",
                        failureReason = "Expected file path validation failed: ${validation.reason}"
                    )
                }
                val file = File(workspaceDir, path)
                if (!file.exists()) {
                    return StepVerificationResult(
                        passed = false,
                        summary = "",
                        failureReason = "Expected file does not exist: $path"
                    )
                }
                if (!file.isFile) {
                    return StepVerificationResult(
                        passed = false,
                        summary = "",
                        failureReason = "Expected file is not a regular file: $path"
                    )
                }
            }
        }

        // 2. Forbidden files must not exist and paths must be safe
        if (step.forbiddenFiles.isNotEmpty()) {
            if (workspaceDir == null || !workspaceDir.exists()) {
                return StepVerificationResult(
                    passed = false,
                    summary = "",
                    failureReason = "Workspace directory does not exist for forbidden file verification: ${step.forbiddenFiles}"
                )
            }
            for (path in step.forbiddenFiles) {
                val validation = StepPathValidator.validateRelativePath(path, workspaceDir)
                if (validation is StepPathValidator.ValidationResult.Invalid) {
                    return StepVerificationResult(
                        passed = false,
                        summary = "",
                        failureReason = "Forbidden file path validation failed: ${validation.reason}"
                    )
                }
                val file = File(workspaceDir, path)
                if (file.exists()) {
                    return StepVerificationResult(
                        passed = false,
                        summary = "",
                        failureReason = "Forbidden file exists: $path"
                    )
                }
            }
        }

        // 3. Expected content must match (regex or substring) safely with bounded reads
        if (step.expectedContent.isNotEmpty()) {
            if (workspaceDir == null || !workspaceDir.exists()) {
                return StepVerificationResult(
                    passed = false,
                    summary = "",
                    failureReason = "Workspace directory does not exist for content verification: ${step.expectedContent.keys}"
                )
            }
            for ((path, pattern) in step.expectedContent) {
                val validation = StepPathValidator.validateRelativePath(path, workspaceDir)
                if (validation is StepPathValidator.ValidationResult.Invalid) {
                    return StepVerificationResult(
                        passed = false,
                        summary = "",
                        failureReason = "Content verification path validation failed: ${validation.reason}"
                    )
                }
                if (pattern.isEmpty()) {
                    return StepVerificationResult(
                        passed = false,
                        summary = "",
                        failureReason = "Content pattern for '$path' cannot be empty"
                    )
                }
                val file = File(workspaceDir, path)
                if (!file.exists()) {
                    return StepVerificationResult(
                        passed = false,
                        summary = "",
                        failureReason = "File not found for content verification: $path"
                    )
                }
                if (!file.isFile) {
                    return StepVerificationResult(
                        passed = false,
                        summary = "",
                        failureReason = "Target for content verification is not a regular file: $path"
                    )
                }
                val maxBytes = 10 * 1024 * 1024L
                val content = runCatching {
                    if (file.length() > maxBytes) {
                        file.inputStream().use { input ->
                            val buf = ByteArray(maxBytes.toInt())
                            val read = input.read(buf)
                            if (read > 0) String(buf, 0, read, Charsets.UTF_8) else ""
                        }
                    } else {
                        file.readText()
                    }
                }.getOrDefault("")
                val regexMatches = runCatching {
                    Regex(pattern, setOf(RegexOption.MULTILINE)).containsMatchIn(content)
                }.getOrDefault(false)
                val substringMatches = content.contains(pattern)
                if (!regexMatches && !substringMatches) {
                    return StepVerificationResult(
                        passed = false,
                        summary = "",
                        failureReason = "File '$path' does not contain expected pattern/content: $pattern"
                    )
                }
            }
        }

        // 4. Verification command must succeed (exit code 0) inside workspace
        if (meaningfulCommand) {
            val verificationCommand = requireNotNull(step.verificationCommand)
            if (workspaceDir != null && !workspaceDir.exists()) {
                return StepVerificationResult(
                    passed = false,
                    summary = "",
                    failureReason = "Workspace directory does not exist for verification command: '${step.verificationCommand}'"
                )
            }
            val canonicalWs = (workspaceDir?.takeIf { it.exists() } ?: File(".")).canonicalFile

            // Confinement and escape validation gate
            val commandValidation = StepCommandValidator.validateCommand(verificationCommand, canonicalWs)
            if (commandValidation is StepCommandValidator.ValidationResult.Invalid) {
                return StepVerificationResult(
                    passed = false,
                    summary = "",
                    failureReason = commandValidation.reason
                )
            }

            val (exitCode, output) = if (commandRunner != null) {
                commandRunner.invoke(verificationCommand, canonicalWs)
            } else {
                confinementRunner.execute(task, verificationCommand, canonicalWs, commandTimeoutSeconds)
            }

            if (exitCode != 0) {
                return StepVerificationResult(
                    passed = false,
                    summary = "",
                    failureReason = "Verification command failed with exit code $exitCode: ${output.take(500)}"
                )
            }
        }

        return StepVerificationResult(
            passed = true,
            summary = "Trusted verification passed for step '${step.title}'",
            failureReason = null
        )
    }
}
