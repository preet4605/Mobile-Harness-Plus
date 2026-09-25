package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.SqlRow
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe SQLite persistence store for durable background tasks.
 * Centralizes all task state transitions and prevents contradictory states.
 */
class TaskStateStore(private val db: BrainDatabase) {

    private val inMemoryCache = ConcurrentHashMap<String, DurableTaskRecord>()

    private fun mapRow(row: SqlRow): DurableTaskRecord {
        return DurableTaskRecord(
            taskId = row.getString("task_id") ?: "",
            projectId = row.getString("project_id") ?: "",
            projectSlug = row.getString("project_slug") ?: "",
            chatId = row.getString("chat_id") ?: "",
            agentKind = row.getString("agent_kind") ?: "",
            providerJson = row.getString("provider_json") ?: "",
            prompt = row.getString("prompt") ?: "",
            status = runCatching {
                TaskExecutionStatus.valueOf(row.getString("status") ?: "CREATED")
            }.getOrDefault(TaskExecutionStatus.CREATED),
            createdAt = row.getLong("created_at") ?: 0L,
            startedAt = row.getLong("started_at"),
            updatedAt = row.getLong("updated_at") ?: 0L,
            completedAt = row.getLong("completed_at"),
            sessionId = row.getString("session_id"),
            pid = row.getInt("pid"),
            retryCount = row.getInt("retry_count") ?: 0,
            maxRetries = row.getInt("max_retries") ?: 2,
            lastKnownStep = row.getString("last_known_step"),
            lastError = row.getString("last_error"),
            cancellationRequested = (row.getInt("cancellation_requested") ?: 0) == 1,
            recoveryRequired = (row.getInt("recovery_required") ?: 0) == 1,
            checkpointId = row.getString("checkpoint_id"),
        )
    }

    @Synchronized
    fun save(record: DurableTaskRecord) {
        val sql = """
            INSERT OR REPLACE INTO durable_task_states (
                task_id, project_id, project_slug, chat_id, agent_kind,
                provider_json, prompt, status, created_at, started_at,
                updated_at, completed_at, session_id, pid, retry_count,
                max_retries, last_known_step, last_error, cancellation_requested,
                recovery_required, checkpoint_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        db.driver.execute(
            sql,
            listOf(
                record.taskId,
                record.projectId,
                record.projectSlug,
                record.chatId,
                record.agentKind,
                record.providerJson,
                record.prompt,
                record.status.name,
                record.createdAt,
                record.startedAt,
                record.updatedAt,
                record.completedAt,
                record.sessionId,
                record.pid,
                record.retryCount,
                record.maxRetries,
                record.lastKnownStep,
                record.lastError,
                if (record.cancellationRequested) 1 else 0,
                if (record.recoveryRequired) 1 else 0,
                record.checkpointId
            )
        )
        inMemoryCache[record.taskId] = record
    }

    @Synchronized
    fun get(taskId: String): DurableTaskRecord? {
        val cached = inMemoryCache[taskId]
        if (cached != null) return cached

        val sql = "SELECT * FROM durable_task_states WHERE task_id = ? LIMIT 1"
        val found = db.driver.query(sql, listOf(taskId), ::mapRow).firstOrNull()
        if (found != null) {
            inMemoryCache[taskId] = found
        }
        return found
    }

    @Synchronized
    fun getBySessionId(sessionId: String): DurableTaskRecord? {
        val cached = inMemoryCache.values.firstOrNull { it.sessionId == sessionId }
        if (cached != null) return cached

        val sql = "SELECT * FROM durable_task_states WHERE session_id = ? ORDER BY updated_at DESC LIMIT 1"
        val found = db.driver.query(sql, listOf(sessionId), ::mapRow).firstOrNull()
        if (found != null) {
            inMemoryCache[found.taskId] = found
        }
        return found
    }

    @Synchronized
    fun getActiveTasks(): List<DurableTaskRecord> {
        val activeNames = TaskExecutionStatus.values().filter { it.isActive }.map { it.name }
        val placeholders = activeNames.joinToString(", ") { "?" }
        val sql = "SELECT * FROM durable_task_states WHERE status IN ($placeholders) ORDER BY updated_at DESC"
        return db.driver.query(sql, activeNames, ::mapRow).also { list ->
            list.forEach { inMemoryCache[it.taskId] = it }
        }
    }

    @Synchronized
    fun getTasksForProject(projectId: String, limit: Int = 20): List<DurableTaskRecord> {
        val sql = "SELECT * FROM durable_task_states WHERE project_id = ? ORDER BY updated_at DESC LIMIT ?"
        return db.driver.query(sql, listOf(projectId, limit), ::mapRow)
    }

    /**
     * Safely executes a centralized state transition with validation.
     * Throws IllegalStateException if transition is contradictory.
     */
    @Synchronized
    fun transition(
        taskId: String,
        newStatus: TaskExecutionStatus,
        updateBlock: (DurableTaskRecord) -> DurableTaskRecord = { it }
    ): DurableTaskRecord {
        val existing = get(taskId) ?: getBySessionId(taskId)
            ?: error("Cannot transition non-existent task $taskId to $newStatus")

        if (!TaskStateMachine.canTransition(existing.status, newStatus)) {
            val message = "Illegal state transition for task ${existing.taskId}: ${existing.status} -> $newStatus"
            runCatching { android.util.Log.w("TaskStateStore", message) }
            error(message)
        }

        val now = System.currentTimeMillis()
        val base = existing.copy(
            status = newStatus,
            updatedAt = now,
            startedAt = if (newStatus == TaskExecutionStatus.RUNNING && existing.startedAt == null) now else existing.startedAt,
            completedAt = if (newStatus.isTerminal) now else existing.completedAt
        )
        val updated = updateBlock(base)
        save(updated)
        return updated
    }

    /**
     * Authoritatively binds a native runtime process and PID to a task and session.
     * Guarantees that PID and session mapping are persisted atomically to SQLite.
     */
    @Synchronized
    fun bindProcess(taskId: String, sessionId: String, pid: Int): DurableTaskRecord {
        val existing = get(taskId) ?: getBySessionId(sessionId) ?: getBySessionId(taskId)
            ?: error("Cannot bind process: task $taskId (session $sessionId) not found in store")

        if (existing.status.isTerminal) {
            error("Cannot bind process to terminal task ${existing.taskId} (${existing.status})")
        }

        val now = System.currentTimeMillis()
        val nextStatus = if (existing.status == TaskExecutionStatus.STARTING || existing.status == TaskExecutionStatus.CREATED) {
            TaskExecutionStatus.RUNNING
        } else {
            existing.status
        }
        val updated = existing.copy(
            sessionId = sessionId,
            pid = pid,
            status = nextStatus,
            startedAt = existing.startedAt ?: now,
            updatedAt = now
        )
        save(updated)
        return updated
    }

    @Synchronized
    fun markPid(taskId: String, pid: Int): DurableTaskRecord? {
        val existing = get(taskId) ?: getBySessionId(taskId) ?: return null
        val updated = existing.copy(
            pid = pid,
            updatedAt = System.currentTimeMillis()
        )
        save(updated)
        return updated
    }

    @Synchronized
    fun markSessionId(taskId: String, sessionId: String): DurableTaskRecord? {
        val existing = get(taskId) ?: getBySessionId(sessionId) ?: return null
        val updated = existing.copy(
            sessionId = sessionId,
            updatedAt = System.currentTimeMillis()
        )
        save(updated)
        return updated
    }

    @Synchronized
    fun recordError(
        taskId: String,
        error: String,
        canRetry: Boolean,
        markRecoveryRequired: Boolean = false
    ): DurableTaskRecord? {
        val existing = get(taskId) ?: return null
        val newRetry = existing.retryCount + (if (canRetry) 1 else 0)
        val nextStatus = if (canRetry && newRetry <= existing.maxRetries) {
            TaskExecutionStatus.RECOVERING
        } else {
            TaskExecutionStatus.FAILED
        }
        return transition(taskId, nextStatus) { record ->
            record.copy(
                lastError = error,
                retryCount = newRetry,
                recoveryRequired = markRecoveryRequired || (canRetry && newRetry <= existing.maxRetries)
            )
        }
    }

    @Synchronized
    fun delete(taskId: String) {
        db.driver.execute("DELETE FROM durable_task_states WHERE task_id = ?", listOf(taskId))
        inMemoryCache.remove(taskId)
    }
}
