package com.jarves.mh.data

import com.jarves.mh.model.brain.BrainKnowledgeEntry
import java.io.Closeable

class BrainDatabase(val driver: BrainDatabaseDriver) : Closeable {

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }

    var schemaVersion: Int = 1
        private set

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
                    last_accessed_at INTEGER NOT NULL,
                    knowledge_type TEXT NOT NULL DEFAULT 'FACT',
                    task_id TEXT
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

        // Perform migration to V2 (canonical tasks, recovery plans, execution steps, knowledge taxonomy)
        migrateToV2()

        // FTS5 Virtual Table initialization with graceful fallback
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

    /**
     * Non-destructive migration to Schema Version 2.
     * Idempotently adds knowledge_type and task_id to memory_entries,
     * backfills knowledge_type based on legacy type, and creates canonical tasks,
     * execution steps, failure records, and recovery plan tables.
     */
    fun migrateToV2() {
        driver.transaction {
            val memoryCols = runCatching {
                driver.query("PRAGMA table_info(memory_entries)") { it.getString("name") ?: "" }.toSet()
            }.getOrDefault(emptySet())

            if (memoryCols.isNotEmpty()) {
                if ("knowledge_type" !in memoryCols) {
                    driver.execute("ALTER TABLE memory_entries ADD COLUMN knowledge_type TEXT NOT NULL DEFAULT 'FACT'")
                }
                if ("task_id" !in memoryCols) {
                    driver.execute("ALTER TABLE memory_entries ADD COLUMN task_id TEXT")
                }

                // Backfill knowledge_type for existing records where semantic mapping is exact.
                // Ambiguous legacy types (EPISODIC, WORKING, PROJECT, etc.) remain as neutral FACT
                // rather than guessing PROGRESS or FAILURE without explicit evidence.
                driver.execute("UPDATE memory_entries SET knowledge_type = 'DECISION' WHERE type = 'DECISION' AND knowledge_type = 'FACT'")
                driver.execute("UPDATE memory_entries SET knowledge_type = 'TASK' WHERE type = 'TASK' AND knowledge_type = 'FACT'")
            }

            driver.execute("CREATE INDEX IF NOT EXISTS idx_mem_knowledge_type ON memory_entries(project_id, knowledge_type, status)")
            driver.execute("CREATE INDEX IF NOT EXISTS idx_mem_task_id ON memory_entries(task_id)")

            // 4. Canonical tasks table
            driver.execute(
                """
                CREATE TABLE IF NOT EXISTS canonical_tasks (
                    task_id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    project_slug TEXT NOT NULL,
                    objective TEXT NOT NULL,
                    constraints_json TEXT NOT NULL,
                    acceptance_criteria_json TEXT NOT NULL,
                    initial_workspace_sha TEXT,
                    current_workspace_sha TEXT,
                    current_step_index INTEGER NOT NULL DEFAULT 0,
                    plan_id TEXT,
                    plan_title TEXT,
                    plan_status TEXT NOT NULL DEFAULT 'PENDING',
                    outcome_json TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            driver.execute("CREATE INDEX IF NOT EXISTS idx_canonical_tasks_proj ON canonical_tasks(project_id, updated_at)")

            // 5. Execution plans table
            driver.execute(
                """
                CREATE TABLE IF NOT EXISTS execution_plans (
                    plan_id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL UNIQUE,
                    title TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    current_step_index INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(task_id) REFERENCES canonical_tasks(task_id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            driver.execute("CREATE INDEX IF NOT EXISTS idx_execution_plans_task ON execution_plans(task_id)")

            // 6. Execution steps table
            driver.execute(
                """
                CREATE TABLE IF NOT EXISTS execution_steps (
                    step_id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    step_order INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL,
                    expected_files_json TEXT NOT NULL,
                    forbidden_files_json TEXT NOT NULL DEFAULT '[]',
                    expected_content_json TEXT NOT NULL DEFAULT '{}',
                    verification_command TEXT,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    attempts INTEGER NOT NULL DEFAULT 0,
                    max_attempts INTEGER NOT NULL DEFAULT 2,
                    result_summary TEXT,
                    checkpoint_tag TEXT,
                    started_at INTEGER,
                    completed_at INTEGER,
                    objective TEXT,
                    acceptance_criteria_json TEXT NOT NULL DEFAULT '[]',
                    plan_id TEXT,
                    FOREIGN KEY(task_id) REFERENCES canonical_tasks(task_id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            driver.execute("CREATE INDEX IF NOT EXISTS idx_execution_steps_task ON execution_steps(task_id, step_order)")

            // 7. Task failures table
            driver.execute(
                """
                CREATE TABLE IF NOT EXISTS task_failures (
                    failure_id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    step_id TEXT,
                    classification TEXT NOT NULL,
                    error_message TEXT NOT NULL,
                    error_snippet TEXT,
                    mutated_files_json TEXT NOT NULL,
                    matched_solution_id TEXT,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(task_id) REFERENCES canonical_tasks(task_id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            driver.execute("CREATE INDEX IF NOT EXISTS idx_task_failures_task ON task_failures(task_id, created_at)")

            // 8. Recovery plans table
            driver.execute(
                """
                CREATE TABLE IF NOT EXISTS recovery_plans (
                    recovery_id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    failure_record_id TEXT NOT NULL,
                    strategy TEXT NOT NULL,
                    rationale TEXT NOT NULL,
                    files_to_rollback_json TEXT NOT NULL,
                    forward_fix_instructions TEXT,
                    target_step_index INTEGER NOT NULL,
                    approved_by_user INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    step_id TEXT,
                    checkpoint_tag TEXT,
                    attempt_number INTEGER NOT NULL DEFAULT 1,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    recovery_result TEXT,
                    next_action TEXT,
                    FOREIGN KEY(task_id) REFERENCES canonical_tasks(task_id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            driver.execute("CREATE INDEX IF NOT EXISTS idx_recovery_plans_task ON recovery_plans(task_id)")

            // Column migrations for existing tables
            val taskCols = runCatching {
                driver.query("PRAGMA table_info(canonical_tasks)") { it.getString("name") ?: "" }.toSet()
            }.getOrDefault(emptySet())
            if (taskCols.isNotEmpty()) {
                if ("current_step_index" !in taskCols) {
                    driver.execute("ALTER TABLE canonical_tasks ADD COLUMN current_step_index INTEGER NOT NULL DEFAULT 0")
                }
                if ("plan_id" !in taskCols) {
                    driver.execute("ALTER TABLE canonical_tasks ADD COLUMN plan_id TEXT")
                }
                if ("plan_title" !in taskCols) {
                    driver.execute("ALTER TABLE canonical_tasks ADD COLUMN plan_title TEXT")
                }
                if ("plan_status" !in taskCols) {
                    driver.execute("ALTER TABLE canonical_tasks ADD COLUMN plan_status TEXT NOT NULL DEFAULT 'PENDING'")
                }
            }

            val stepCols = runCatching {
                driver.query("PRAGMA table_info(execution_steps)") { it.getString("name") ?: "" }.toSet()
            }.getOrDefault(emptySet())
            if (stepCols.isNotEmpty()) {
                if ("forbidden_files_json" !in stepCols) {
                    driver.execute("ALTER TABLE execution_steps ADD COLUMN forbidden_files_json TEXT NOT NULL DEFAULT '[]'")
                }
                if ("expected_content_json" !in stepCols) {
                    driver.execute("ALTER TABLE execution_steps ADD COLUMN expected_content_json TEXT NOT NULL DEFAULT '{}'")
                }
                if ("verification_command" !in stepCols) {
                    driver.execute("ALTER TABLE execution_steps ADD COLUMN verification_command TEXT")
                }
                if ("objective" !in stepCols) {
                    driver.execute("ALTER TABLE execution_steps ADD COLUMN objective TEXT")
                }
                if ("acceptance_criteria_json" !in stepCols) {
                    driver.execute("ALTER TABLE execution_steps ADD COLUMN acceptance_criteria_json TEXT NOT NULL DEFAULT '[]'")
                }
                if ("plan_id" !in stepCols) {
                    driver.execute("ALTER TABLE execution_steps ADD COLUMN plan_id TEXT")
                }
            }

            val recoveryCols = runCatching {
                driver.query("PRAGMA table_info(recovery_plans)") { it.getString("name") ?: "" }.toSet()
            }.getOrDefault(emptySet())
            if (recoveryCols.isNotEmpty()) {
                if ("step_id" !in recoveryCols) {
                    driver.execute("ALTER TABLE recovery_plans ADD COLUMN step_id TEXT")
                }
                if ("checkpoint_tag" !in recoveryCols) {
                    driver.execute("ALTER TABLE recovery_plans ADD COLUMN checkpoint_tag TEXT")
                }
                if ("attempt_number" !in recoveryCols) {
                    driver.execute("ALTER TABLE recovery_plans ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 1")
                }
                if ("status" !in recoveryCols) {
                    driver.execute("ALTER TABLE recovery_plans ADD COLUMN status TEXT NOT NULL DEFAULT 'PENDING'")
                }
                if ("recovery_result" !in recoveryCols) {
                    driver.execute("ALTER TABLE recovery_plans ADD COLUMN recovery_result TEXT")
                }
                if ("next_action" !in recoveryCols) {
                    driver.execute("ALTER TABLE recovery_plans ADD COLUMN next_action TEXT")
                }
            }

            driver.execute("PRAGMA user_version = 2")
            schemaVersion = CURRENT_SCHEMA_VERSION
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

    fun syncFtsInsert(entry: BrainKnowledgeEntry) {
        if (!isFts5Supported) return
        try {
            driver.execute(
                "INSERT INTO memory_fts(id, project_id, key, value, summary, tags) VALUES (?, ?, ?, ?, ?, ?)",
                listOf(
                    entry.id,
                    entry.projectId,
                    entry.key,
                    entry.content,
                    entry.summary ?: "",
                    entry.tags.joinToString(" ")
                )
            )
        } catch (_: Throwable) {
            // Ignore FTS sync errors to ensure failure safety
        }
    }

    fun syncFtsUpdate(entry: BrainKnowledgeEntry) {
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
