package com.jarves.mh.data

import java.io.Closeable

class BrainDatabase(val driver: BrainDatabaseDriver) : Closeable {

    var isFts5Supported: Boolean = false
        private set

    init {
        initializeSchema()
    }

    private fun initializeSchema() {
        driver.transaction {
            // 1. Core memory table
            driver.execute(
                """
                CREATE TABLE IF NOT EXISTS memory_entries (
                    id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    session_id TEXT,
                    scope TEXT NOT NULL,
                    type TEXT NOT NULL,
                    key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    summary TEXT,
                    importance REAL NOT NULL DEFAULT 0.5,
                    confidence REAL NOT NULL DEFAULT 0.8,
                    source TEXT NOT NULL,
                    source_reference TEXT,
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    version INTEGER NOT NULL DEFAULT 1,
                    superseded_by TEXT,
                    tags TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    last_accessed_at INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // Performance indexes
            driver.execute("CREATE INDEX IF NOT EXISTS idx_mem_proj_status ON memory_entries(project_id, status)")
            driver.execute("CREATE INDEX IF NOT EXISTS idx_mem_proj_key ON memory_entries(project_id, key)")
            driver.execute("CREATE INDEX IF NOT EXISTS idx_mem_type ON memory_entries(project_id, type, status)")
            driver.execute("CREATE INDEX IF NOT EXISTS idx_mem_scope ON memory_entries(project_id, scope, status)")
            driver.execute("CREATE INDEX IF NOT EXISTS idx_mem_updated ON memory_entries(project_id, updated_at)")

            // 2. Persistent Task Checkpoints table
            driver.execute(
                """
                CREATE TABLE IF NOT EXISTS task_checkpoints (
                    id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    session_id TEXT,
                    goal TEXT,
                    plan_json TEXT,
                    current_step TEXT,
                    completed_steps_json TEXT,
                    pending_steps_json TEXT,
                    blockers_json TEXT,
                    recent_actions_json TEXT,
                    current_files_json TEXT,
                    last_error TEXT,
                    last_success TEXT,
                    next_action TEXT,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )

            driver.execute("CREATE INDEX IF NOT EXISTS idx_task_proj ON task_checkpoints(project_id, updated_at)")

            // 3. Durable Task States table (Phase 1 Runtime Reliability)
            driver.execute(
                """
                CREATE TABLE IF NOT EXISTS durable_task_states (
                    task_id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    project_slug TEXT NOT NULL,
                    chat_id TEXT NOT NULL,
                    agent_kind TEXT NOT NULL,
                    provider_json TEXT NOT NULL,
                    prompt TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    started_at INTEGER,
                    updated_at INTEGER NOT NULL,
                    completed_at INTEGER,
                    session_id TEXT,
                    pid INTEGER,
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    max_retries INTEGER NOT NULL DEFAULT 2,
                    last_known_step TEXT,
                    last_error TEXT,
                    cancellation_requested INTEGER NOT NULL DEFAULT 0,
                    recovery_required INTEGER NOT NULL DEFAULT 0,
                    checkpoint_id TEXT
                )
                """.trimIndent()
            )

            driver.execute("CREATE INDEX IF NOT EXISTS idx_durable_tasks_proj ON durable_task_states(project_id, status)")
            driver.execute("CREATE INDEX IF NOT EXISTS idx_durable_tasks_status ON durable_task_states(status)")
            driver.execute("CREATE INDEX IF NOT EXISTS idx_durable_tasks_session ON durable_task_states(session_id)")
        }

        // 3. FTS5 Virtual Table initialization with graceful fallback
        try {
            driver.execute(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
                    id UNINDEXED,
                    project_id UNINDEXED,
                    key,
                    value,
                    summary,
                    tags
                )
                """.trimIndent()
            )
            isFts5Supported = true
        } catch (_: Throwable) {
            isFts5Supported = false
        }
    }

    fun syncFtsInsert(entry: MemoryEntry) {
        if (!isFts5Supported) return
        try {
            driver.execute(
                "INSERT INTO memory_fts(id, project_id, key, value, summary, tags) VALUES (?, ?, ?, ?, ?, ?)",
                listOf(
                    entry.id,
                    entry.projectId,
                    entry.key,
                    entry.value,
                    entry.summary ?: "",
                    entry.tags.joinToString(" ")
                )
            )
        } catch (_: Throwable) {
            // Ignore FTS sync errors to ensure failure safety
        }
    }

    fun syncFtsDelete(entryId: String) {
        if (!isFts5Supported) return
        try {
            driver.execute("DELETE FROM memory_fts WHERE id = ?", listOf(entryId))
        } catch (_: Throwable) {
            // Ignore FTS sync errors to ensure failure safety
        }
    }

    fun syncFtsUpdate(entry: MemoryEntry) {
        if (!isFts5Supported) return
        try {
            syncFtsDelete(entry.id)
            syncFtsInsert(entry)
        } catch (_: Throwable) {
            // Ignore FTS sync errors to ensure failure safety
        }
    }

    override fun close() {
        driver.close()
    }
}
