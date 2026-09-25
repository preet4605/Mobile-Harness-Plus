package com.jarves.mh.data

import com.jarves.mh.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class ProjectBrainMemoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var memoryStore: ContextMemoryStore

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("brain_test")
        memoryStore = ContextMemoryStore(baseDir)
    }

    @Test
    fun `test persistence and retrieval of layered memories in SQLite`() {
        val repo = memoryStore.repository
        val pId = "project-alpha"

        val working = MemoryEntry(
            projectId = pId,
            scope = MemoryScope.TASK,
            type = MemoryType.WORKING,
            key = "current-step",
            value = "Implement database migration",
            importance = 0.8f,
            confidence = 0.9f
        )
        val decision = MemoryEntry(
            projectId = pId,
            scope = MemoryScope.PROJECT,
            type = MemoryType.DECISION,
            key = "arch-database",
            value = "Adopt SQLite with FTS5 for local project brain",
            importance = 0.95f,
            confidence = 1.0f,
            source = MemorySource.TOOL_VERIFIED
        )
        val episodic = MemoryEntry(
            projectId = pId,
            scope = MemoryScope.SESSION,
            type = MemoryType.EPISODIC,
            key = "build-error",
            value = "AAPT2 daemon crash on API 28",
            importance = 0.7f,
            confidence = 0.9f,
            status = MemoryStatus.FAILED
        )

        repo.save(working)
        repo.save(decision)
        repo.save(episodic)

        // Filter by type
        val decisions = repo.filter(pId, type = MemoryType.DECISION)
        assertEquals(1, decisions.size)
        assertEquals("Adopt SQLite with FTS5 for local project brain", decisions[0].value)

        val activeWorking = repo.filter(pId, type = MemoryType.WORKING)
        assertEquals(1, activeWorking.size)
        assertEquals("Implement database migration", activeWorking[0].value)

        val failures = repo.filter(pId, status = MemoryStatus.FAILED)
        assertEquals(1, failures.size)
        assertEquals("AAPT2 daemon crash on API 28", failures[0].value)
    }

    @Test
    fun `test duplicate detection updates existing memory without creating duplicates`() {
        val repo = memoryStore.repository
        val pId = "project-dedup"

        val entry1 = MemoryEntry(
            projectId = pId,
            key = "project-framework",
            value = "Jetpack Compose",
            source = MemorySource.AUTO,
            confidence = 0.7f,
            importance = 0.6f,
            tags = listOf("ui")
        )
        repo.save(entry1)

        val initialList = repo.getByProject(pId, MemoryStatus.ACTIVE)
        assertEquals(1, initialList.size)
        val firstId = initialList[0].id

        // Duplicate with higher confidence and additional tag
        val entry2 = MemoryEntry(
            projectId = pId,
            key = "PROJECT-FRAMEWORK",
            value = "Jetpack Compose",
            source = MemorySource.TOOL_VERIFIED,
            confidence = 0.95f,
            importance = 0.85f,
            tags = listOf("android", "compose")
        )
        repo.save(entry2)

        val afterDedup = repo.getByProject(pId, MemoryStatus.ACTIVE)
        assertEquals(1, afterDedup.size)
        assertEquals(firstId, afterDedup[0].id)
        assertEquals(MemorySource.TOOL_VERIFIED, afterDedup[0].source)
        assertEquals(0.95f, afterDedup[0].confidence, 0.001f)
        assertEquals(0.85f, afterDedup[0].importance, 0.001f)
        assertTrue(afterDedup[0].tags.contains("ui"))
        assertTrue(afterDedup[0].tags.contains("compose"))
    }

    @Test
    fun `test conflict resolution and supersession based on trust hierarchy`() {
        val repo = memoryStore.repository
        val pId = "project-conflict"

        // 1. Initial fact from auto extraction (trust: 60)
        val oldEntry = MemoryEntry(
            projectId = pId,
            key = "database",
            value = "Room",
            source = MemorySource.AUTO,
            confidence = 0.7f
        )
        repo.save(oldEntry)

        val initial = repo.getByProject(pId, MemoryStatus.ACTIVE)
        assertEquals(1, initial.size)
        assertEquals("Room", initial[0].value)
        val oldId = initial[0].id

        // 2. Verified observation from tool / compiler inspection (trust: 100)
        val verifiedEntry = MemoryEntry(
            projectId = pId,
            key = "database",
            value = "SQLite",
            source = MemorySource.TOOL_VERIFIED,
            confidence = 1.0f
        )
        repo.save(verifiedEntry)

        // Active should now be SQLite, and Room must be SUPERSEDED
        val activeEntries = repo.getByProject(pId, MemoryStatus.ACTIVE)
        assertEquals(1, activeEntries.size)
        assertEquals("SQLite", activeEntries[0].value)
        assertEquals(2, activeEntries[0].version)

        val allEntries = repo.getByProject(pId, status = null)
        assertEquals(2, allEntries.size)
        val superseded = allEntries.first { it.status == MemoryStatus.SUPERSEDED }
        assertEquals(oldId, superseded.id)
        assertEquals("Room", superseded.value)
        assertEquals(activeEntries[0].id, superseded.supersededBy)

        // 3. Lower trust AI inference (trust: 40) attempting to overwrite verified fact should be rejected
        val hallucination = MemoryEntry(
            projectId = pId,
            key = "database",
            value = "MongoDB",
            source = MemorySource.AGENT_INFERRED,
            confidence = 0.5f
        )
        repo.save(hallucination)

        val afterAttempt = repo.getByProject(pId, MemoryStatus.ACTIVE)
        assertEquals(1, afterAttempt.size)
        assertEquals("SQLite", afterAttempt[0].value)
    }

    @Test
    fun `test MemoryRanker scoring and ranking behavior`() {
        val now = Instant.now()
        val e1 = MemoryEntry(
            key = "database",
            value = "SQLite with FTS5",
            importance = 0.9f,
            confidence = 0.95f,
            type = MemoryType.PROJECT,
            updatedAt = now
        )
        val e2 = MemoryEntry(
            key = "project-language",
            value = "Kotlin 2.0",
            importance = 0.8f,
            confidence = 0.9f,
            type = MemoryType.PROJECT,
            updatedAt = now.minusSeconds(86400)
        )
        val e3 = MemoryEntry(
            key = "minor-note",
            value = "Random note about SQLite",
            importance = 0.2f,
            confidence = 0.5f,
            type = MemoryType.WORKING,
            updatedAt = now.minusSeconds(86400 * 14)
        )

        val ranked = MemoryRanker.rank(listOf(e3, e2, e1), query = "database", targetType = MemoryType.PROJECT)
        assertEquals(3, ranked.size)
        assertEquals("database", ranked[0].key)
    }

    @Test
    fun `test MemoryRetriever answers the five core brain questions`() {
        val repo = memoryStore.repository
        val taskRepo = memoryStore.taskRepository
        val retriever = memoryStore.retriever
        val pId = "p-brain-5q"

        // 1. Setup project facts
        repo.save(
            MemoryEntry(
                projectId = pId,
                key = "project-language",
                value = "Kotlin",
                importance = 0.9f,
                type = MemoryType.PROJECT
            )
        )
        repo.save(
            MemoryEntry(
                projectId = pId,
                key = "database",
                value = "SQLite",
                importance = 0.85f,
                type = MemoryType.PROJECT
            )
        )

        // 2. Setup decisions
        repo.save(
            MemoryEntry(
                projectId = pId,
                key = "decision-architecture",
                value = "Use Clean Architecture with Repository pattern",
                importance = 0.85f,
                type = MemoryType.DECISION
            )
        )

        // 3. Setup task checkpoint
        taskRepo.saveCheckpoint(
            TaskCheckpoint(
                projectId = pId,
                goal = "Upgrade memory to persistent project brain",
                plan = listOf("Schema design", "Repository implementation", "Retrieval engine"),
                currentStep = "Retrieval engine",
                completedSteps = listOf("Schema design", "Repository implementation"),
                pendingSteps = listOf("Retrieval engine", "Unit tests"),
                blockers = listOf("Android SQLite stubbing in JVM"),
                nextAction = "Implement unit tests"
            )
        )

        // 4. Setup episodic failures
        repo.save(
            MemoryEntry(
                projectId = pId,
                key = "error-compiler",
                value = "Type mismatch in TaskStateRepository mapper",
                type = MemoryType.EPISODIC,
                status = MemoryStatus.FAILED
            )
        )

        // Q1: What do we know?
        val whatWeKnow = retriever.whatDoWeKnow(pId)
        assertTrue(whatWeKnow.any { it.key == "project-language" && it.value == "Kotlin" })
        assertTrue(whatWeKnow.any { it.key == "database" && it.value == "SQLite" })

        // Q2: What did we decide?
        val decisions = retriever.whatDidWeDecide(pId)
        assertTrue(decisions.any { it.value.contains("Clean Architecture") })

        // Q3: What are we doing?
        val (checkpoint, _) = retriever.whatAreWeDoing(pId)
        assertNotNull(checkpoint)
        assertEquals("Upgrade memory to persistent project brain", checkpoint?.goal)
        assertEquals("Retrieval engine", checkpoint?.currentStep)

        // Q4: What failed?
        val failures = retriever.whatFailed(pId)
        assertTrue(failures.any { it.value.contains("Type mismatch") })

        // Q5: What remains?
        val remains = retriever.whatRemains(pId)
        assertTrue(remains.any { it.contains("Retrieval engine") || it.contains("Unit tests") })
        assertTrue(remains.any { it.contains("Implement unit tests") })
        assertTrue(remains.any { it.contains("Android SQLite stubbing in JVM") })
    }

    @Test
    fun `test TaskCheckpoint survives process death and restart`() {
        val pId = "project-restart"
        val originalStore = ContextMemoryStore(baseDir)

        val cp = TaskCheckpoint(
            projectId = pId,
            goal = "Refactor authentication",
            plan = listOf("Update tokens", "Encrypt Keystore", "Verify biometrics"),
            currentStep = "Encrypt Keystore",
            completedSteps = listOf("Update tokens"),
            pendingSteps = listOf("Encrypt Keystore", "Verify biometrics"),
            blockers = emptyList(),
            nextAction = "Run Keystore tests"
        )
        originalStore.taskRepository.saveCheckpoint(cp)

        // Simulate app restart / new process instantiating new store from same directory
        val restartedStore = ContextMemoryStore(baseDir)
        val loaded = restartedStore.taskRepository.getActiveCheckpoint(pId)

        assertNotNull(loaded)
        assertEquals("Refactor authentication", loaded?.goal)
        assertEquals("Encrypt Keystore", loaded?.currentStep)
        assertEquals(listOf("Update tokens"), loaded?.completedSteps)
        assertEquals(listOf("Encrypt Keystore", "Verify biometrics"), loaded?.pendingSteps)
        assertEquals("Run Keystore tests", loaded?.nextAction)
    }

    @Test
    fun `test failure recovery when database errors occur`() {
        // Safe fallback when project ID is sanitized
        val mem = memoryStore.load("p/fail")
        assertNotNull(mem)
        assertEquals("p_fail", mem.projectId)

        // Store doesn't crash on invalid keys or blank values
        val unchanged = memoryStore.upsert("p/fail", " ", " ", MemorySource.AUTO)
        assertTrue(unchanged.entries.isEmpty())
    }

    @Test
    fun `test minimal requirement scenario A - User sets SQLite and query returns SQLite without message in active context`() {
        val pId = "proj-scenario-a"

        // 1. User says "Use SQLite for this project."
        val conversationTurn1 = listOf(
            ChatMessage(fromUser = true, text = "Use SQLite for this project."),
            ChatMessage(fromUser = false, text = "Understood. I will configure the project to use SQLite.")
        )
        val extracted = MemoryExtractor.extractRichMemories(conversationTurn1, pId)
        extracted.forEach { memoryStore.repository.save(it) }

        // Verify it was stored
        val verified = memoryStore.repository.findExact(pId, "database")
        assertNotNull(verified)
        assertEquals("SQLite", verified?.value)
        assertEquals(MemorySource.USER_PROVIDED, verified?.source)

        // 2. Later: Conversation is cleared or compacted! Active message context does NOT have the old message.
        // Query: "What database are we using?"
        val results = memoryStore.retriever.searchAndRank(pId, "What database are we using?")
        assertFalse("Results must not be empty", results.isEmpty())
        val top = results.first()
        assertEquals("database", top.key)
        assertEquals("SQLite", top.value)
    }

    @Test
    fun `test minimal requirement scenario B - Refactor authentication followed by Continue recovers active task`() {
        val pId = "proj-scenario-b"

        // 1. Initial turn: "Refactor authentication."
        val messages1 = listOf(
            ChatMessage(fromUser = true, text = "Refactor authentication."),
            ChatMessage(
                fromUser = false,
                text = "Starting authentication refactoring.\nGoal: Refactor authentication\nNext step: Migrate to Keystore AES-256 GCM"
            )
        )
        val checkpoint1 = MemoryExtractor.extractTaskCheckpoint(messages1, pId)
        assertNotNull(checkpoint1)
        memoryStore.taskRepository.saveCheckpoint(checkpoint1!!)

        // 2. App restarts or conversation advances to "Continue."
        val newStore = ContextMemoryStore(baseDir)
        val activeTask = newStore.taskRepository.getActiveCheckpoint(pId)

        assertNotNull("Must recover active task checkpoint", activeTask)
        assertEquals("Refactor authentication.", activeTask?.goal)
        assertEquals("Migrate to Keystore AES-256 GCM", activeTask?.nextAction)
    }
}
