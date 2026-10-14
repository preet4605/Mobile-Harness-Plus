package com.jarves.mh.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import org.json.JSONObject

internal data class CodexConversationOwner(val accountId: String, val accountScope: String, val threadId: String)

/** Complete CLI rollouts only. Never copies account configuration, credentials or databases. */
internal class CodexConversationHandoff(rootfs: File) {
    private val root = rootfs.canonicalFile
    private val accounts = CodexAccounts(root)
    private fun directory(key: String) = contained(File(root, "root/.codex-harness-conversations/${digest(key.toByteArray())}"))
    private fun read(key: String) = Properties().apply { File(directory(key), "state.properties").takeIf { it.isFile }?.let(::contained)?.inputStream()?.use(::load) }
    private fun write(key: String, state: Properties) {
        state.setProperty("key", key)
        val dir = directory(key).apply { mkdirs() }
        val target = contained(File(dir, "state.properties"))
        val temp = contained(File(dir, "state-${UUID.randomUUID()}.tmp"))
        try {
            temp.outputStream().use { state.store(it, "Native Codex conversation ownership; no credentials") }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    fun owner(key: String): CodexConversationOwner? = synchronized(lock) { owner(read(key)) }
    private fun owner(p: Properties): CodexConversationOwner? {
        val account = p.getProperty("account") ?: return null
        return CodexConversationOwner(account, p.getProperty("scope") ?: error("Missing Codex context scope."),
            NativeConversationScope.validId(p.getProperty("thread")) ?: error("Invalid Codex context thread."))
    }
    fun goalOwner(key: String): String? = synchronized(lock) { read(key).let { if (it.getProperty("goalPinned", "true") == "true") it.getProperty("goalAccount") else null } }
    fun savedGoalOwner(key: String): String? = synchronized(lock) { read(key).getProperty("goalAccount") }
    fun childThreadOwner(key: String): String? = synchronized(lock) {
        read(key).let { if (it.getProperty("childThreads") == "true") owner(it)?.accountId else null }
    }
    fun hasState(key: String): Boolean = synchronized(lock) { read(key).isNotEmpty() }
    fun wasReset(key: String): Boolean = synchronized(lock) { read(key).getProperty("reset") == "true" }
    fun planMode(key: String): Boolean = synchronized(lock) { read(key).getProperty("plan", "false").toBoolean() }
    fun hasPlanMode(key: String): Boolean = synchronized(lock) { read(key).containsKey("plan") }
    fun savePlanMode(key: String, chatKey: String, enabled: Boolean) = synchronized(lock) {
        val p = read(key); p.setProperty("chat", chatKey); p.setProperty("plan", enabled.toString()); write(key, p)
    }
    fun requireReady(key: String) = synchronized(lock) {
        check(read(key).getProperty("pendingTask") == null) { RECOVERY_MESSAGE }
    }
    fun needsHistory(key: String): Boolean = synchronized(lock) { read(key).getProperty("history") == "true" }
    fun completedTask(key: String): String? = synchronized(lock) { read(key).getProperty("completedTask") }

    /** Terminal task status alone is insufficient: callers must verify the owned process is closed. */
    fun recoverInterrupted(key: String, isStopped: (String) -> Boolean) = synchronized(lock) {
        val task = read(key).getProperty("pendingTask") ?: return@synchronized
        check(isStopped(task)) { RECOVERY_MESSAGE }
        continueFromHistory(key, task)
        val p = read(key); p.remove("pendingTask"); p.remove("pendingGoal"); write(key, p)
    }

    /** Only the runtime that has reaped this claim's process may release it directly. */
    fun releaseInterrupted(key: String, taskId: String) = synchronized(lock) {
        if (read(key).getProperty("pendingTask") == taskId) recoverInterrupted(key) { it == taskId }
    }

    /** Preserve old rollouts/snapshots for inspection; never restore stale context over interrupted work. */
    fun continueFromHistory(key: String, taskId: String? = null) = synchronized(lock) {
        val p = read(key)
        check(p.getProperty("pendingTask") == taskId) { RECOVERY_MESSAGE }
        listOf("account", "scope", "thread", "relative", "snapshot", "sha256", "childThreads",
            "goalAccount", "goalScope", "goalPinned", "completedTask").forEach(p::remove)
        p.setProperty("history", "true")
        write(key, p)
    }
    /** A crash leaves the claim visible; a later task cannot overwrite uncertain work. */
    fun claim(key: String, taskId: String, account: CodexAccount, workspace: String, chatKey: String = key, goal: Boolean = false, goalControl: Boolean = false) = synchronized(lock) {
        val p = read(key)
        check(p.getProperty("pendingTask") == null || goalControl && p.getProperty("pendingGoal") == "true" && p.getProperty("goalAccount") == account.id) { RECOVERY_MESSAGE }
        if (p.getProperty("childThreads") == "true" && !goalControl) {
            require(owner(p)?.let { it.accountId == account.id && it.accountScope == account.scope } == true) { CHILD_MESSAGE }
        }
        p.getProperty("workspace")?.let { require(it == workspace) { "Codex context belongs to another workspace." } }
        if (goalControl || p.getProperty("goalPinned", "true") == "true") {
            p.getProperty("goalAccount")?.let { require(it == account.id) { "The native goal must stay on its owning ChatGPT account." } }
            p.getProperty("goalScope")?.let { require(it == account.scope) { "This native goal's ChatGPT account was reconnected. Start a new chat to retain this history." } }
        }
        p.setProperty("pendingTask", taskId); p.setProperty("chat", chatKey); p.setProperty("workspace", workspace)
        p.setProperty("pendingGoal", (goal || goalControl).toString())
        if (goal) { p.setProperty("goalAccount", account.id); p.setProperty("goalScope", account.scope); p.setProperty("goalPinned", "true") }
        write(key, p)
    }
    fun adopt(key: String, chatKey: String, account: CodexAccount, threadId: String, sourcePath: String, workspace: String, goal: Boolean = false, completedTask: String? = null) = synchronized(lock) {
        val p = read(key)
        check(owner(p) == null && p.getProperty("pendingTask") == null && p.getProperty("reset") != "true")
        p.setProperty("chat", chatKey); p.setProperty("workspace", workspace)
        freeze(key, p, account, threadId, sourcePath, workspace)
        completedTask?.let { p.setProperty("completedTask", it) }
        if (goal) { p.setProperty("goalAccount", account.id); p.setProperty("goalScope", account.scope); p.setProperty("goalPinned", "true") }
        write(key, p)
        pruneSnapshots(key, p.getProperty("snapshot"))
    }
    /** Import the whole most recently completed native context, even when returning to an old account. */
    fun importForTask(key: String, taskId: String, target: CodexAccount, workspace: String): String? = synchronized(lock) {
        val p = read(key); check(p.getProperty("pendingTask") == taskId) { "This task does not own the Codex context handoff." }
        val source = owner(p) ?: return@synchronized null
        require(target.scope == accounts.requireUsable(target.id).scope) { "This task's ChatGPT account changed." }
        if (p.getProperty("childThreads") == "true") {
            require(source.accountId == target.id && source.accountScope == target.scope) { CHILD_MESSAGE }
        }
        if (p.getProperty("goalAccount") != null && p.getProperty("goalPinned", "true") == "true") {
            require(source.accountId == target.id && source.accountScope == target.scope) { "Native goals cannot migrate between accounts." }
            return@synchronized source.threadId
        }
        val snapshot = contained(File(directory(key), p.getProperty("snapshot") ?: error("Missing native Codex context snapshot.")))
        validate(snapshot, source.threadId, workspace)
        require(digest(snapshot.readBytes()) == p.getProperty("sha256")) { "Native Codex context snapshot changed. $RECOVERY_MESSAGE" }
        val relative = p.getProperty("relative") ?: error("Missing native Codex rollout path.")
        val destination = rolloutPath(target, relative, source.threadId)
        destination.parentFile!!.mkdirs()
        val staging = contained(File(destination.parentFile, ".handoff-${UUID.randomUUID()}.tmp"))
        try {
            Files.copy(snapshot.toPath(), staging.toPath())
            validate(staging, source.threadId, workspace)
            require(digest(staging.readBytes()) == p.getProperty("sha256")) { "Native Codex context changed during import." }
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { staging.delete() }
        source.threadId
    }
    /** Call only after the native server has completed and actually exited/reaped. */
    fun complete(key: String, taskId: String, account: CodexAccount, threadId: String, sourcePath: String, workspace: String, goalStatus: String? = null) = synchronized(lock) {
        val p = read(key); check(p.getProperty("pendingTask") == taskId) { "A stale task cannot promote native Codex context." }
        require(accounts.requireUsable(account.id).scope == account.scope) { "The ChatGPT account changed before context was saved." }
        freeze(key, p, account, threadId, sourcePath, workspace)
        p.remove("pendingTask"); p.remove("pendingGoal"); p.remove("reset"); p.remove("history")
        p.setProperty("completedTask", taskId)
        releaseGoal(p, goalStatus)
        write(key, p)
        pruneSnapshots(key, p.getProperty("snapshot"))
    }
    fun completeControl(key: String, taskId: String, goalStatus: String? = null) = synchronized(lock) {
        val p = read(key); check(p.getProperty("pendingTask") == taskId)
        p.remove("pendingTask"); p.remove("pendingGoal"); p.setProperty("completedTask", taskId); releaseGoal(p, goalStatus); write(key, p)
    }
    fun pinSavedGoal(key: String, taskId: String, account: CodexAccount) = synchronized(lock) {
        val p = read(key)
        check(p.getProperty("pendingTask") == taskId)
        require(p.getProperty("goalAccount") == account.id && p.getProperty("goalScope") == account.scope) { "This saved goal belongs to another account identity." }
        p.setProperty("goalPinned", "true"); write(key, p)
    }
    private fun releaseGoal(p: Properties, status: String?) {
        if (status == "complete") p.setProperty("goalPinned", "false")
        if (status == "cleared") { p.remove("goalAccount"); p.remove("goalScope"); p.remove("goalPinned") }
    }
    private fun freeze(key: String, p: Properties, account: CodexAccount, thread: String, sourcePath: String, workspace: String) {
        require(NativeConversationScope.validId(thread) != null) { "Invalid native Codex thread." }
        val source = contained(File(sourcePath))
        val home = accounts.hostHome(account.id)
        require(source.toPath().startsWith(home.toPath())) { "Codex rollout is outside its account home." }
        val relative = source.relativeTo(home).invariantSeparatorsPath
        require(rolloutPath(account, relative, thread) == source) { "Invalid native Codex rollout path." }
        validate(source, thread, workspace)
        val snapshot = contained(File(directory(key), "snapshots/${UUID.randomUUID()}.jsonl"))
        snapshot.parentFile!!.mkdirs()
        Files.copy(source.toPath(), snapshot.toPath())
        if (validate(snapshot, thread, workspace)) p.setProperty("childThreads", "true")
        p.setProperty("account", account.id); p.setProperty("scope", account.scope); p.setProperty("thread", thread)
        p.setProperty("relative", relative); p.setProperty("snapshot", snapshot.relativeTo(directory(key)).invariantSeparatorsPath)
        p.setProperty("sha256", digest(snapshot.readBytes()))
    }
    fun findRollout(account: CodexAccount, thread: String): File {
        require(NativeConversationScope.validId(thread) != null)
        val sessions = contained(File(accounts.hostHome(account.id), "sessions"))
        require(sessions.isDirectory) { "Native Codex transcript is unavailable. $RECOVERY_MESSAGE" }
        val matches = mutableListOf<File>()
        var entries = 0
        Files.walk(sessions.toPath(), 4).use { stream -> stream.forEach { path ->
            require(++entries <= 20_000) { "Native Codex transcript discovery exceeded its bound." }
            val file = contained(path.toFile())
            if (file.isFile && file.name.endsWith("-$thread.jsonl")) matches += file
        } }
        require(matches.size == 1) { "Native Codex transcript could not be identified uniquely. $RECOVERY_MESSAGE" }
        return matches.single()
    }
    private fun rolloutPath(account: CodexAccount, relative: String, thread: String): File {
        require(Regex("sessions/[0-9]{4}/[0-9]{2}/[0-9]{2}/rollout-[0-9T:-]+-${Regex.escape(thread)}\\.jsonl").matches(relative)) { "Invalid native Codex rollout filename." }
        return contained(File(accounts.hostHome(account.id), relative))
    }
    private fun validate(file: File, thread: String, workspace: String): Boolean {
        contained(file)
        require(file.isFile && file.length() in 1..MAX_BYTES) { "Native Codex transcript is missing or exceeds the 32 MiB complete-handoff limit. Start a new chat to retain this history." }
        file.inputStream().use { input -> input.skip(file.length() - 1); require(input.read() == '\n'.code) { "Native Codex transcript is truncated." } }
        var lines = 0
        var childThreads = false
        file.bufferedReader().useLines { content -> content.forEach { line ->
            require(++lines <= 100_000 && line.length <= 2 * 1024 * 1024) { "Native Codex transcript exceeded its record bound." }
            val item = runCatching { JSONObject(line) }.getOrElse { throw IllegalArgumentException("Native Codex transcript is malformed.", it) }
            require(item.optString("type").isNotBlank() && item.optJSONObject("payload") != null) { "Invalid native Codex transcript record." }
            val payload = item.getJSONObject("payload")
            // Child rollouts and their lifecycle live outside the parent transcript.
            if (payload.optString("type").startsWith("collab_") ||
                payload.optString("type") == "collabAgentToolCall" ||
                payload.optString("type") in setOf("function_call", "custom_tool_call") &&
                payload.optString("name").substringAfterLast('.') in setOf("spawn_agent", "resume_agent", "send_input", "wait", "close_agent")) {
                childThreads = true
            }
            if (lines == 1) {
                val meta = item.getJSONObject("payload")
                require(item.getString("type") == "session_meta" && meta.optString("id") == thread && meta.optString("cwd") == workspace && meta.optString("cli_version") == "0.161.0") {
                    "Native Codex transcript identity, workspace or CLI version does not match this chat."
                }
            }
        } }
        require(lines > 1) { "Native Codex transcript has no completed context." }
        return childThreads
    }
    fun clearChat(chatKey: String) = synchronized(lock) {
        val base = contained(File(root, "root/.codex-harness-conversations"))
        base.listFiles()?.forEach { entry ->
            val state = contained(File(contained(entry), "state.properties"))
            val p = Properties().apply { if (state.isFile) state.inputStream().use(::load) }
            if (p.getProperty("chat") == chatKey) {
                val key = p.getProperty("key") ?: error("Missing native Codex context key.")
                require(directory(key) == entry) { "Invalid native Codex context key." }
                reset(key, chatKey)
                pruneSnapshots(key, null)
            }
        }
    }
    fun reset(key: String, chatKey: String) = synchronized(lock) {
        write(key, Properties().apply { setProperty("chat", chatKey); setProperty("reset", "true") })
        pruneSnapshots(key, null)
    }
    private fun pruneSnapshots(key: String, keep: String?, directDirectory: File = directory(key)) {
        val snapshots = contained(File(directDirectory, "snapshots"))
        snapshots.listFiles()?.forEach { file ->
            contained(file)
            if (file.isFile && NativeConversationScope.validId(file.name.removeSuffix(".jsonl")) != null && "snapshots/${file.name}" != keep) file.delete()
        }
    }
    private fun contained(file: File): File {
        var component: File? = file.absoluteFile
        while (component != null && component != root) {
            require(!Files.isSymbolicLink(component.toPath())) { "Native Codex context paths must not contain symbolic links." }
            component = component.parentFile
        }
        require(file.absoluteFile.toPath().startsWith(root.toPath()) && file.canonicalFile == file.absoluteFile) { "Native Codex context path escapes private runtime storage." }
        return file.absoluteFile
    }
    companion object {
        private val lock = Any()
        private const val MAX_BYTES = 32L * 1024 * 1024
        private const val CHILD_MESSAGE = "This chat has native subagent threads and must stay on its owning ChatGPT account to preserve their context."
        const val RECOVERY_MESSAGE = "Previous Codex work is still running or its process has not been verified closed. Stop the running task before continuing."
        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
