package com.jarves.mh.data

import com.jarves.mh.model.brain.BrainKnowledgeEntry
import com.jarves.mh.model.brain.BrainKnowledgeType
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.RecoveryPlan
import com.jarves.mh.model.brain.RecoveryStrategy
import com.jarves.mh.model.brain.StepStatus
import com.jarves.mh.model.brain.TaskFailureRecord
import com.jarves.mh.model.brain.TaskOutcome
import com.jarves.mh.model.brain.toBrainKnowledgeEntry
import com.jarves.mh.model.brain.toMemoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class BrainDatabaseMigrationTest {

    @Test
    fun testFreshDatabaseInitializationIsVersion2() {
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()
        val db = BrainDatabase(driver)

        assertEquals(2, db.schemaVersion)

        val version = driver.query("PRAGMA user_version") { row ->
            row.getInt("user_version") ?: 0
        }.firstOrNull()
        assertEquals(2, version)

        // Verify V2 tables exist
        val tables = driver.query("SELECT name FROM sqlite_master WHERE type='table'") { row ->
            row.getString("name") ?: ""
        }.toSet()

        assertTrue("memory_entries table missing", "memory_entries" in tables)
        assertTrue("canonical_tasks table missing", "canonical_tasks" in tables)
        assertTrue("execution_steps table missing", "execution_steps" in tables)
        assertTrue("task_failures table missing", "task_failures" in tables)
        assertTrue("recovery_plans table missing", "recovery_plans" in tables)

        // Verify columns in memory_entries
        val columns = driver.query("PRAGMA table_info(memory_entries)") { row ->
            row.getString("name") ?: ""
        }.toSet()

        assertTrue("knowledge_type column missing", "knowledge_type" in columns)
        assertTrue("task_id column missing", "task_id" in columns)

        db.close()
    }

    @Test
    fun testMigrationFromV1ToV2PreservesDataAndBackfillsKnowledgeType() {
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()

        // 1. Manually set up a V1 schema without knowledge_type and task_id
        driver.execute(
            """
            CREATE TABLE memory_entries (
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
        driver.execute("PRAGMA user_version = 1")

        // 2. Insert Phase 1 legacy records
        val now = System.currentTimeMillis()
        driver.execute(
            """
            INSERT INTO memory_entries (
                id, project_id, scope, type, key, value, summary, importance, confidence,
                source, status, version, created_at, updated_at, last_accessed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf("1", "proj-1", "PROJECT", "PROJECT", "tool", "gradle", "build tool", 0.9, 1.0, "AUTO", "ACTIVE", 1, now, now, now)
        )
        driver.execute(
            """
            INSERT INTO memory_entries (
                id, project_id, scope, type, key, value, summary, importance, confidence,
                source, status, version, created_at, updated_at, last_accessed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf("2", "proj-1", "PROJECT", "DECISION", "arch", "sqlite", "use sqlite", 0.9, 1.0, "AUTO", "ACTIVE", 1, now, now, now)
        )
        driver.execute(
            """
            INSERT INTO memory_entries (
                id, project_id, scope, type, key, value, summary, importance, confidence,
                source, status, version, created_at, updated_at, last_accessed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf("3", "proj-1", "TASK", "TASK", "step1", "schema", "create schema", 0.8, 0.9, "AUTO", "ACTIVE", 1, now, now, now)
        )
        driver.execute(
            """
            INSERT INTO memory_entries (
                id, project_id, scope, type, key, value, summary, importance, confidence,
                source, status, version, created_at, updated_at, last_accessed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf("4", "proj-1", "SESSION", "EPISODIC", "session_event", "models created", "done", 0.6, 0.8, "AUTO", "ACTIVE", 1, now, now, now)
        )
        driver.execute(
            """
            INSERT INTO memory_entries (
                id, project_id, scope, type, key, value, summary, importance, confidence,
                source, status, version, created_at, updated_at, last_accessed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf("5", "proj-1", "SESSION", "WORKING", "scratch_state", "buffer state", "in progress", 0.5, 0.7, "AUTO", "ACTIVE", 1, now, now, now)
        )

        // 3. Open BrainDatabase which runs the migration
        val db = BrainDatabase(driver)
        assertEquals(2, db.schemaVersion)

        val repo = ContextMemoryRepository(db)
        val entry1 = repo.getById("1")
        assertNotNull(entry1)
        assertEquals(MemoryType.PROJECT, entry1!!.type)
        assertEquals(BrainKnowledgeType.FACT, entry1.knowledgeType)

        val entry2 = repo.getById("2")
        assertNotNull(entry2)
        assertEquals(MemoryType.DECISION, entry2!!.type)
        assertEquals(BrainKnowledgeType.DECISION, entry2.knowledgeType)

        val entry3 = repo.getById("3")
        assertNotNull(entry3)
        assertEquals(MemoryType.TASK, entry3!!.type)
        assertEquals(BrainKnowledgeType.TASK, entry3.knowledgeType)

        // Ambiguous legacy types remain as neutral FACT (not guessed as PROGRESS or FAILURE)
        val entry4 = repo.getById("4")
        assertNotNull(entry4)
        assertEquals(MemoryType.EPISODIC, entry4!!.type)
        assertEquals(BrainKnowledgeType.FACT, entry4.knowledgeType)

        val entry5 = repo.getById("5")
        assertNotNull(entry5)
        assertEquals(MemoryType.WORKING, entry5!!.type)
        assertEquals(BrainKnowledgeType.FACT, entry5.knowledgeType)

        // 4. Insert new entry with custom knowledgeType and taskId
        val newEntry = MemoryEntry(
            projectId = "proj-1",
            key = "discovery.aapt2",
            value = "maven override required on ARM64",
            knowledgeType = BrainKnowledgeType.DISCOVERY,
            taskId = "task-v2-001"
        )
        repo.insert(newEntry)

        val loadedNew = repo.getById(newEntry.id)
        assertNotNull(loadedNew)
        assertEquals(BrainKnowledgeType.DISCOVERY, loadedNew!!.knowledgeType)
        assertEquals("task-v2-001", loadedNew.taskId)

        db.close()
    }

    @Test
    fun testCanonicalTaskRepositoryPersistenceAndQueries() {
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()
        val db = BrainDatabase(driver)
        val repo = CanonicalTaskRepository(db)

        val taskId = UUID.randomUUID().toString()
        val step1 = ExecutionStep(
            stepOrder = 0,
            title = "Step 1: Models",
            description = "Define CanonicalTask",
            status = StepStatus.COMPLETED,
            expectedFiles = listOf("BrainDomainModels.kt")
        )
        val step2 = ExecutionStep(
            stepOrder = 1,
            title = "Step 2: Migration",
            description = "Upgrade SQLite schema to V2",
            status = StepStatus.RUNNING,
            expectedFiles = listOf("BrainDatabase.kt")
        )

        val failure = TaskFailureRecord(
            taskId = taskId,
            stepId = step2.stepId,
            classification = "COMPILATION_ERROR",
            errorMessage = "Missing import for BrainKnowledgeType",
            mutatedFiles = listOf("BrainDatabase.kt")
        )

        val recoveryPlan = RecoveryPlan(
            taskId = taskId,
            failureRecordId = failure.failureId,
            strategy = RecoveryStrategy.FORWARD_FIX_WITH_CONTEXT,
            rationale = "Forward fix missing import",
            targetStepIndex = 1
        )

        val task = CanonicalTask(
            taskId = taskId,
            projectId = "clever-kalam",
            projectSlug = "mobile-harness-plus",
            objective = "Phase 2 Step 1 Implementation",
            constraints = listOf("Containment within /workspace/clever-kalam"),
            acceptanceCriteria = listOf("Pass all tests"),
            plan = ExecutionPlan(
                title = "Execution Plan",
                steps = listOf(step1, step2)
            ),
            initialWorkspaceSha = "sha-initial-123",
            currentWorkspaceSha = "sha-current-456",
            failureHistory = listOf(failure),
            activeRecoveryPlan = recoveryPlan
        )

        repo.saveTask(task)

        // Verify retrieval
        val loaded = repo.getTask(taskId)
        assertNotNull(loaded)
        assertEquals(taskId, loaded!!.taskId)
        assertEquals("clever-kalam", loaded.projectId)
        assertEquals("mobile-harness-plus", loaded.projectSlug)
        assertEquals("Phase 2 Step 1 Implementation", loaded.objective)
        assertEquals(1, loaded.constraints.size)
        assertEquals(2, loaded.plan.steps.size)
        assertEquals("Step 1: Models", loaded.plan.steps[0].title)
        assertEquals(StepStatus.COMPLETED, loaded.plan.steps[0].status)
        assertEquals("Step 2: Migration", loaded.plan.steps[1].title)
        assertEquals(StepStatus.RUNNING, loaded.plan.steps[1].status)
        assertEquals(1, loaded.failureHistory.size)
        assertEquals("COMPILATION_ERROR", loaded.failureHistory[0].classification)
        assertNotNull(loaded.activeRecoveryPlan)
        assertEquals(RecoveryStrategy.FORWARD_FIX_WITH_CONTEXT, loaded.activeRecoveryPlan!!.strategy)

        // Complete the task with outcome
        val outcome = TaskOutcome(
            success = true,
            summary = "Step 1 complete and verified",
            filesModified = listOf("BrainDomainModels.kt", "BrainDatabase.kt", "CanonicalTaskRepository.kt"),
            testsExecuted = true,
            testsPassed = true,
            durationSeconds = 15L
        )
        repo.completeTask(taskId, outcome)

        val completedTask = repo.getTask(taskId)
        assertNotNull(completedTask)
        assertNotNull(completedTask!!.outcome)
        assertTrue(completedTask.outcome!!.success)
        assertEquals(3, completedTask.outcome!!.filesModified.size)

        // Project listing
        val projectTasks = repo.getTasksByProject("clever-kalam")
        assertEquals(1, projectTasks.size)
        assertEquals(taskId, projectTasks[0].taskId)

        // Delete task
        repo.deleteTask(taskId)
        assertNull(repo.getTask(taskId))

        db.close()
    }

    @Test
    fun testLegacyEpisodicAndWorkingMemoriesPreserveTypeLosslessly() {
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()

        // 1. Setup V1 schema
        driver.execute(
            """
            CREATE TABLE memory_entries (
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
        driver.execute("PRAGMA user_version = 1")

        val now = 1700000000000L
        // Insert records spanning diverse legacy types
        val legacyRecords = listOf(
            listOf("ep-1", "p1", "s1", "SESSION", "EPISODIC", "chat.turn1", "User asked for status", "Brief summary", 0.7, 0.9, "USER_PROVIDED", "ref1", "ACTIVE", 1, null, "tag1,tag2", now, now, now),
            listOf("wk-1", "p1", "s1", "SESSION", "WORKING", "scratch.diff", "Pending file diff", "Diff summary", 0.6, 0.85, "AGENT_INFERRED", "ref2", "ACTIVE", 1, null, "diff", now, now, now),
            listOf("proj-1", "p1", null, "PROJECT", "PROJECT", "proj.toolchain", "Gradle 8.14", null, 0.8, 1.0, "AUTO", null, "ACTIVE", 1, null, null, now, now, now),
            listOf("dec-1", "p1", null, "PROJECT", "DECISION", "dec.sqlite", "Use direct SQLite", null, 0.9, 1.0, "AUTO", null, "ACTIVE", 1, null, null, now, now, now),
            listOf("task-1", "p1", "s1", "PROJECT", "TASK", "task.build", "Build app debug apk", null, 0.9, 1.0, "AUTO", null, "ACTIVE", 1, null, null, now, now, now)
        )

        for (rec in legacyRecords) {
            driver.execute(
                """
                INSERT INTO memory_entries (
                    id, project_id, session_id, scope, type, key, value, summary, importance, confidence,
                    source, source_reference, status, version, superseded_by, tags, created_at, updated_at, last_accessed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                rec
            )
        }

        // Migrate to V2
        val db = BrainDatabase(driver)
        assertEquals(2, db.schemaVersion)
        val repo = ContextMemoryRepository(db)

        // 1. EPISODIC remains identifiable as legacy EPISODIC
        val ep = repo.getById("ep-1")
        assertNotNull(ep)
        assertEquals(MemoryType.EPISODIC, ep!!.type)
        assertEquals(BrainKnowledgeType.FACT, ep.knowledgeType)
        assertEquals("chat.turn1", ep.key)
        assertEquals("User asked for status", ep.value)
        assertEquals("Brief summary", ep.summary)
        assertEquals(0.7f, ep.importance, 0.001f)
        assertEquals(0.9f, ep.confidence, 0.001f)
        assertEquals(listOf("tag1", "tag2"), ep.tags)

        val episodicList = repo.filter(projectId = "p1", type = MemoryType.EPISODIC)
        assertEquals(1, episodicList.size)
        assertEquals("ep-1", episodicList[0].id)

        // 2. WORKING remains identifiable as legacy WORKING
        val wk = repo.getById("wk-1")
        assertNotNull(wk)
        assertEquals(MemoryType.WORKING, wk!!.type)
        assertEquals(BrainKnowledgeType.FACT, wk.knowledgeType)
        assertEquals("scratch.diff", wk.key)
        assertEquals("Pending file diff", wk.value)

        val workingList = repo.filter(projectId = "p1", type = MemoryType.WORKING)
        assertEquals(1, workingList.size)
        assertEquals("wk-1", workingList[0].id)

        // 3. PROJECT preserved as PROJECT and neutral FACT
        val proj = repo.getById("proj-1")
        assertNotNull(proj)
        assertEquals(MemoryType.PROJECT, proj!!.type)
        assertEquals(BrainKnowledgeType.FACT, proj.knowledgeType)

        // 4. Exact mappings DECISION -> DECISION and TASK -> TASK
        val dec = repo.getById("dec-1")
        assertNotNull(dec)
        assertEquals(MemoryType.DECISION, dec!!.type)
        assertEquals(BrainKnowledgeType.DECISION, dec.knowledgeType)

        val task = repo.getById("task-1")
        assertNotNull(task)
        assertEquals(MemoryType.TASK, task!!.type)
        assertEquals(BrainKnowledgeType.TASK, task.knowledgeType)

        db.close()
    }

    @Test
    fun testV1ToV2MigrationIsTransactional() {
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()

        // 1. Setup V1 schema
        driver.execute(
            """
            CREATE TABLE memory_entries (
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
        driver.execute("PRAGMA user_version = 1")
        driver.execute(
            """
            INSERT INTO memory_entries (
                id, project_id, scope, type, key, value, source, created_at, updated_at, last_accessed_at
            ) VALUES ('1', 'p1', 'PROJECT', 'EPISODIC', 'k', 'v', 'AUTO', 0, 0, 0)
            """.trimIndent()
        )

        // 2. Simulate failure inside a transaction before version update
        var rolledBack = false
        try {
            driver.transaction {
                driver.execute("ALTER TABLE memory_entries ADD COLUMN knowledge_type TEXT NOT NULL DEFAULT 'FACT'")
                throw RuntimeException("Simulated catastrophic crash before commit")
            }
        } catch (e: Exception) {
            rolledBack = true
        }
        assertTrue(rolledBack)

        // Verify rollback: user_version is still 1 and knowledge_type column does not exist
        val ver = driver.query("PRAGMA user_version") { it.getInt("user_version") ?: 0 }.firstOrNull()
        assertEquals(1, ver)
        val cols = driver.query("PRAGMA table_info(memory_entries)") { it.getString("name") ?: "" }.toSet()
        assertTrue("knowledge_type should have rolled back", "knowledge_type" !in cols)

        // 3. Now run the genuine BrainDatabase migration cleanly
        val db = BrainDatabase(driver)
        assertEquals(2, db.schemaVersion)
        val finalCols = driver.query("PRAGMA table_info(memory_entries)") { it.getString("name") ?: "" }.toSet()
        assertTrue("knowledge_type should exist now", "knowledge_type" in finalCols)
        assertTrue("canonical_tasks table should exist", "canonical_tasks" in driver.query("SELECT name FROM sqlite_master WHERE type='table'") { it.getString("name") ?: "" }.toSet())
        db.close()
    }

    @Test
    fun testBrainKnowledgeEntryAndMemoryEntryBidirectionalLosslessConversion() {
        for (legacyType in MemoryType.values()) {
            val original = MemoryEntry(
                projectId = "proj-x",
                sessionId = "sess-y",
                taskId = "task-z",
                scope = MemoryScope.SESSION,
                type = legacyType,
                key = "key.${legacyType.name.lowercase()}",
                value = "Content for ${legacyType.name}",
                summary = "Summary for ${legacyType.name}",
                importance = 0.85f,
                confidence = 0.95f,
                source = MemorySource.USER_PROVIDED,
                sourceReference = "spec.md",
                status = MemoryStatus.ACTIVE,
                version = 2,
                supersededBy = null,
                tags = listOf("test", legacyType.name.lowercase()),
                createdAt = Instant.ofEpochMilli(1700000000000L),
                updatedAt = Instant.ofEpochMilli(1700000001000L),
                lastAccessedAt = Instant.ofEpochMilli(1700000002000L),
                knowledgeType = when (legacyType) {
                    MemoryType.DECISION -> BrainKnowledgeType.DECISION
                    MemoryType.TASK -> BrainKnowledgeType.TASK
                    else -> BrainKnowledgeType.FACT
                }
            )

            val knowledgeEntry = original.toBrainKnowledgeEntry()
            assertEquals(legacyType, knowledgeEntry.legacyType)

            val roundTrip = knowledgeEntry.toMemoryEntry()
            assertEquals("Loss of legacy MemoryType for ${legacyType.name}", original.type, roundTrip.type)
            assertEquals(original.id, roundTrip.id)
            assertEquals(original.projectId, roundTrip.projectId)
            assertEquals(original.sessionId, roundTrip.sessionId)
            assertEquals(original.taskId, roundTrip.taskId)
            assertEquals(original.scope, roundTrip.scope)
            assertEquals(original.key, roundTrip.key)
            assertEquals(original.value, roundTrip.value)
            assertEquals(original.summary, roundTrip.summary)
            assertEquals(original.importance, roundTrip.importance, 0.001f)
            assertEquals(original.confidence, roundTrip.confidence, 0.001f)
            assertEquals(original.source, roundTrip.source)
            assertEquals(original.sourceReference, roundTrip.sourceReference)
            assertEquals(original.status, roundTrip.status)
            assertEquals(original.version, roundTrip.version)
            assertEquals(original.tags, roundTrip.tags)
            assertEquals(original.createdAt, roundTrip.createdAt)
            assertEquals(original.updatedAt, roundTrip.updatedAt)
            assertEquals(original.lastAccessedAt, roundTrip.lastAccessedAt)
            assertEquals(original.knowledgeType, roundTrip.knowledgeType)
        }
    }
}
