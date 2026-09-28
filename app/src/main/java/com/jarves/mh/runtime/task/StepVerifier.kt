package com.jarves.mh.runtime.task

import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.StepStatus
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Result of deterministic runtime verification for an ExecutionStep.
 */
data class StepVerificationResult(
    val passed: Boolean,
    val summary: String,
    val failureReason: String? = null
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
}

class ControlledStepCommandRunner : StepCommandRunner {
    override fun execute(command: String, workingDir: File, timeoutSeconds: Long): Pair<Int, String> {
        val canonicalWs = workingDir.canonicalFile
        if (!canonicalWs.exists()) {
            return Pair(-1, "Working directory does not exist: ${canonicalWs.path}")
        }

        val validation = StepCommandValidator.validateCommand(command, canonicalWs)
        if (validation is StepCommandValidator.ValidationResult.Invalid) {
            return Pair(-1, "Command confinement validation failed: ${validation.reason}")
        }

        return runCatching {
            val shellPath = when {
                File("/bin/sh").canExecute() -> "/bin/sh"
                File("/system/bin/sh").canExecute() -> "/system/bin/sh"
                else -> "sh"
            }

            val processBuilder = ProcessBuilder(shellPath, "-c", command)
                .directory(canonicalWs)
                .redirectErrorStream(true)

            val env = processBuilder.environment()
            env["PWD"] = canonicalWs.absolutePath
            env["HOME"] = canonicalWs.absolutePath
            val sandboxTmp = File(canonicalWs, ".tmp_step_verify").apply { mkdirs() }
            env["TMPDIR"] = sandboxTmp.absolutePath
            env["TEMP"] = sandboxTmp.absolutePath
            env["TMP"] = sandboxTmp.absolutePath

            val process = processBuilder.start()
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                Pair(-1, "Verification command timed out after $timeoutSeconds seconds")
            } else {
                val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val postEscaped = StepCommandValidator.findEscapingSymlinks(canonicalWs)
                if (postEscaped.isNotEmpty()) {
                    Pair(-1, "Verification command created symlink escaping workspace boundary: ${postEscaped.first().name}")
                } else {
                    Pair(process.exitValue(), out)
                }
            }
        }.getOrElse { Pair(-1, it.message ?: "Verification command failed to execute") }
    }
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
        val hasCriteria = step.expectedFiles.isNotEmpty() ||
            step.forbiddenFiles.isNotEmpty() ||
            step.expectedContent.isNotEmpty() ||
            !step.verificationCommand.isNullOrBlank()

        if (!hasCriteria) {
            return StepVerificationResult(
                passed = false,
                summary = "",
                failureReason = "No verification criteria defined for step '${step.title}'. Process exit code 0 alone cannot complete a step without deterministic verification."
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
        if (!step.verificationCommand.isNullOrBlank()) {
            if (workspaceDir != null && !workspaceDir.exists()) {
                return StepVerificationResult(
                    passed = false,
                    summary = "",
                    failureReason = "Workspace directory does not exist for verification command: '${step.verificationCommand}'"
                )
            }
            val canonicalWs = (workspaceDir?.takeIf { it.exists() } ?: File(".")).canonicalFile

            // Confinement and escape validation gate
            val commandValidation = StepCommandValidator.validateCommand(step.verificationCommand, canonicalWs)
            if (commandValidation is StepCommandValidator.ValidationResult.Invalid) {
                return StepVerificationResult(
                    passed = false,
                    summary = "",
                    failureReason = commandValidation.reason
                )
            }

            val (exitCode, output) = if (commandRunner != null) {
                commandRunner.invoke(step.verificationCommand, canonicalWs)
            } else {
                confinementRunner.execute(step.verificationCommand, canonicalWs, commandTimeoutSeconds)
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
