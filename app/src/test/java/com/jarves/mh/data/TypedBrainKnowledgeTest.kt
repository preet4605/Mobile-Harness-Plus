package com.jarves.mh.data

import com.jarves.mh.model.brain.BrainKnowledgeEntry
import com.jarves.mh.model.brain.BrainKnowledgeType
import com.jarves.mh.model.brain.toBrainKnowledgeEntry
import com.jarves.mh.model.brain.toMemoryEntry
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
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class TypedBrainKnowledgeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var memoryStore: ContextMemoryStore
    private lateinit var brainRepo: BrainKnowledgeRepository

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("brain_knowledge_test_${System.nanoTime()}")
        memoryStore = ContextMemoryStore(baseDir)
        brainRepo = memoryStore.brainKnowledgeRepository
    }

    // 1. CRUD for all 10 knowledge types
    @Test
    fun test1_crudForAll10KnowledgeTypes() {
        val pId = "crud-proj"
        val types = BrainKnowledgeType.values()
        assertEquals(10, types.size)

        for (type in types) {
            val entry = BrainKnowledgeEntry(
                id = UUID.randomUUID().toString(),
                projectId = pId,
                knowledgeType = type,
                key = "key-${type.name.lowercase()}",
                content = "Content for ${type.name}",
                summary = "Summary for ${type.name}",
                importance = 0.8f,
                confidence = 0.9f
            )

            // Insert
            val inserted = brainRepo.insert(entry)
            assertEquals(entry.id, inserted.id)

            // GetById
            val fetched = brainRepo.getById(entry.id)
            assertNotNull("Entry of type $type should be found", fetched)
            assertEquals(type, fetched?.knowledgeType)
            assertEquals(entry.key, fetched?.key)
            assertEquals(entry.content, fetched?.content)
            assertEquals(entry.summary, fetched?.summary)
            assertEquals(entry.importance, fetched?.importance)
            assertEquals(entry.confidence, fetched?.confidence)

            // Update
            val updated = fetched!!.copy(content = "Updated content for ${type.name}", importance = 0.95f)
            brainRepo.update(updated)
            val fetchedAfterUpdate = brainRepo.getById(entry.id)
            assertEquals("Updated content for ${type.name}", fetchedAfterUpdate?.content)
            assertEquals(0.95f, fetchedAfterUpdate?.importance)

            // Delete
            brainRepo.delete(entry.id)
            val fetchedAfterDelete = brainRepo.getById(entry.id)
            assertNull("Entry of type $type should be null after delete", fetchedAfterDelete)
        }
    }

    // 2. Project isolation
    @Test
    fun test2_projectIsolation() {
        val projA = "project-alpha"
        val projB = "project-beta"

        brainRepo.insert(
            BrainKnowledgeEntry(
                projectId = projA,
                key = "isolated-key",
                content = "Alpha secret configuration",
                knowledgeType = BrainKnowledgeType.FACT
            )
        )
        brainRepo.insert(
            BrainKnowledgeEntry(
                projectId = projB,
                key = "isolated-key",
                content = "Beta secret configuration",
                knowledgeType = BrainKnowledgeType.FACT
            )
        )

        val alphaEntries = brainRepo.findByProject(projA)
        assertEquals(1, alphaEntries.size)
        assertEquals("Alpha secret configuration", alphaEntries[0].content)

        val betaEntries = brainRepo.findByProject(projB)
        assertEquals(1, betaEntries.size)
        assertEquals("Beta secret configuration", betaEntries[0].content)

        val alphaSearch = brainRepo.search(projA, "secret")
        assertEquals(1, alphaSearch.size)
        assertEquals(projA, alphaSearch[0].projectId)

        val betaSearch = brainRepo.search(projB, "secret")
        assertEquals(1, betaSearch.size)
        assertEquals(projB, betaSearch[0].projectId)

        val alphaRelevant = brainRepo.retrieveRelevant(projA, "configuration")
        assertEquals(1, alphaRelevant.size)
        assertEquals(projA, alphaRelevant[0].projectId)
    }

    // 3. Task filtering
    @Test
    fun test3_taskFiltering() {
        val pId = "task-filter-proj"
        val t1 = "task-101"
        val t2 = "task-202"

        brainRepo.insert(BrainKnowledgeEntry(projectId = pId, taskId = t1, key = "task1-step", content = "Compile code"))
        brainRepo.insert(BrainKnowledgeEntry(projectId = pId, taskId = t2, key = "task2-step", content = "Run tests"))
        brainRepo.insert(BrainKnowledgeEntry(projectId = pId, taskId = null, key = "global-fact", content = "Gradle build"))

        val task1Results = brainRepo.findByTask(t1)
        assertEquals(1, task1Results.size)
        assertEquals("task1-step", task1Results[0].key)

        val task2Results = brainRepo.findByTask(t2)
        assertEquals(1, task2Results.size)
        assertEquals("task2-step", task2Results[0].key)

        // retrieveRelevant with taskId should prioritize task-101 over task-202
        val relevantT1 = brainRepo.retrieveRelevant(pId, query = null, taskId = t1, limit = 10)
        assertTrue(relevantT1.isNotEmpty())
        assertEquals("task1-step", relevantT1[0].key)
    }

    // 4. Type filtering
    @Test
    fun test4_typeFiltering() {
        val pId = "type-filter-proj"

        brainRepo.insert(BrainKnowledgeEntry(projectId = pId, knowledgeType = BrainKnowledgeType.DECISION, key = "d1", content = "Use SQLite"))
        brainRepo.insert(BrainKnowledgeEntry(projectId = pId, knowledgeType = BrainKnowledgeType.CONSTRAINT, key = "c1", content = "Never edit outside workspace"))
        brainRepo.insert(BrainKnowledgeEntry(projectId = pId, knowledgeType = BrainKnowledgeType.SOLUTION, key = "s1", content = "Override AAPT2 maven flag"))

        val decisions = brainRepo.findByType(pId, BrainKnowledgeType.DECISION)
        assertEquals(1, decisions.size)
        assertEquals("Use SQLite", decisions[0].content)

        val constraints = brainRepo.findByType(pId, BrainKnowledgeType.CONSTRAINT)
        assertEquals(1, constraints.size)
        assertEquals("Never edit outside workspace", constraints[0].content)

        val filteredRelevant = brainRepo.retrieveRelevant(pId, query = null, knowledgeTypes = setOf(BrainKnowledgeType.CONSTRAINT))
        assertEquals(1, filteredRelevant.size)
        assertEquals(BrainKnowledgeType.CONSTRAINT, filteredRelevant[0].knowledgeType)
    }

    // 5. Key lookup
    @Test
    fun test5_keyLookup() {
        val pId = "key-lookup-proj"
        brainRepo.insert(BrainKnowledgeEntry(projectId = pId, key = "compiler-version", content = "Kotlin 2.0.21"))

        val exact = brainRepo.findByKey(pId, "compiler-version")
        assertNotNull(exact)
        assertEquals("Kotlin 2.0.21", exact?.content)

        val caseInsensitive = brainRepo.findByKey(pId, "Compiler-Version")
        assertNotNull(caseInsensitive)
        assertEquals("Kotlin 2.0.21", caseInsensitive?.content)

        val normalized = brainRepo.findByKey(pId, "compiler_version")
        assertNotNull(normalized)
        assertEquals("Kotlin 2.0.21", normalized?.content)
    }

    // 6. FTS search
    @Test
    fun test6_ftsSearch() {
        val pId = "fts-search-proj"
        brainRepo.insert(BrainKnowledgeEntry(projectId = pId, key = "arch-pattern", content = "Clean architecture repository pattern"))
        brainRepo.insert(BrainKnowledgeEntry(projectId = pId, key = "network-client", content = "OkHttpClient timeout tuning"))

        val results = brainRepo.search(pId, "architecture")
        assertTrue("Search for 'architecture' should find entry", results.any { it.key == "arch-pattern" })
        assertFalse("Search for 'architecture' should not include unrelated network entry", results.any { it.key == "network-client" })
    }

    // 7. LIKE fallback
    @Test
    fun test7_likeFallback() {
        val pId = "like-fallback-proj"
        brainRepo.insert(BrainKnowledgeEntry(projectId = pId, key = "special-term", content = "Subsequence pattern matching check"))

        val results = brainRepo.search(pId, "subsequence")
        assertTrue(results.isNotEmpty())
        assertEquals("special-term", results[0].key)
    }

    // 8. Deterministic ranking
    @Test
    fun test8_deterministicRanking() {
        val pId = "det-rank-proj"
        for (i in 1..10) {
            brainRepo.insert(
                BrainKnowledgeEntry(
                    projectId = pId,
                    key = "item-$i",
                    content = "System service $i processing pipeline",
                    importance = (i % 5) * 0.2f,
                    confidence = 0.8f
                )
            )
        }

        val run1 = brainRepo.retrieveRelevant(pId, "system", limit = 10)
        val run2 = brainRepo.retrieveRelevant(pId, "system", limit = 10)
        val run3 = brainRepo.retrieveRelevant(pId, "system", limit = 10)

        assertEquals(run1.size, run2.size)
        assertEquals(run1.size, run3.size)
        for (i in run1.indices) {
            assertEquals("Index $i must be identical across deterministic runs", run1[i].id, run2[i].id)
            assertEquals("Index $i must be identical across deterministic runs", run1[i].id, run3[i].id)
        }
    }

    // 9. Ranking tie-breakers
    @Test
    fun test9_rankingTieBreakers() {
        val now = Instant.now()
        val entryOld = BrainKnowledgeEntry(
            id = "id-old",
            projectId = "p-tie",
            key = "tie-key-old",
            content = "same content",
            importance = 0.8f,
            confidence = 0.8f,
            updatedAt = now.minusSeconds(100),
            createdAt = now.minusSeconds(100)
        )
        val entryNew = BrainKnowledgeEntry(
            id = "id-new",
            projectId = "p-tie",
            key = "tie-key-new",
            content = "same content",
            importance = 0.8f,
            confidence = 0.8f,
            updatedAt = now,
            createdAt = now
        )

        val scoredOld = MemoryRanker.scoreKnowledge(entryOld, "same content", now = now)
        val scoredNew = MemoryRanker.scoreKnowledge(entryNew, "same content", now = now)

        val ranked = MemoryRanker.rankKnowledge(listOf(entryOld, entryNew), "same content", now = now)
        assertEquals("Newer entry should win tie-break", "id-new", ranked[0].id)
    }

    // 10. Importance weighting
    @Test
    fun test10_importanceWeighting() {
        val now = Instant.now()
        val lowImportance = BrainKnowledgeEntry(
            id = "low-imp",
            projectId = "p-imp",
            key = "key1",
            content = "common query string",
            importance = 0.2f,
            confidence = 0.8f,
            updatedAt = now
        )
        val highImportance = BrainKnowledgeEntry(
            id = "high-imp",
            projectId = "p-imp",
            key = "key2",
            content = "common query string",
            importance = 0.95f,
            confidence = 0.8f,
            updatedAt = now
        )

        val ranked = MemoryRanker.rankKnowledge(listOf(lowImportance, highImportance), "common query string", now = now)
        assertEquals("high-imp", ranked[0].id)
    }

    // 11. Confidence weighting
    @Test
    fun test11_confidenceWeighting() {
        val now = Instant.now()
        val lowConf = BrainKnowledgeEntry(
            id = "low-conf",
            projectId = "p-conf",
            key = "key1",
            content = "common phrase",
            importance = 0.8f,
            confidence = 0.3f,
            updatedAt = now
        )
        val highConf = BrainKnowledgeEntry(
            id = "high-conf",
            projectId = "p-conf",
            key = "key2",
            content = "common phrase",
            importance = 0.8f,
            confidence = 0.99f,
            updatedAt = now
        )

        val ranked = MemoryRanker.rankKnowledge(listOf(lowConf, highConf), "common phrase", now = now)
        assertEquals("high-conf", ranked[0].id)
    }

    // 12. Recency weighting
    @Test
    fun test12_recencyWeighting() {
        val now = Instant.now()
        val staleEntry = BrainKnowledgeEntry(
            id = "stale",
            projectId = "p-recency",
            key = "k1",
            content = "matching topic",
            importance = 0.8f,
            confidence = 0.8f,
            updatedAt = now.minusSeconds(86400 * 30) // 30 days old
        )
        val freshEntry = BrainKnowledgeEntry(
            id = "fresh",
            projectId = "p-recency",
            key = "k2",
            content = "matching topic",
            importance = 0.8f,
            confidence = 0.8f,
            updatedAt = now // Fresh
        )

        val ranked = MemoryRanker.rankKnowledge(listOf(staleEntry, freshEntry), "matching topic", now = now)
        assertEquals("fresh", ranked[0].id)
    }

    // 13. Constraint/decision/solution weighting
    @Test
    fun test13_constraintDecisionSolutionWeighting() {
        val now = Instant.now()
        val progressEntry = BrainKnowledgeEntry(
            id = "progress",
            projectId = "p-types",
            knowledgeType = BrainKnowledgeType.PROGRESS,
            key = "target-key",
            content = "target-key payload",
            importance = 0.7f,
            confidence = 0.8f,
            updatedAt = now
        )
        val constraintEntry = BrainKnowledgeEntry(
            id = "constraint",
            projectId = "p-types",
            knowledgeType = BrainKnowledgeType.CONSTRAINT,
            key = "target-key",
            content = "target-key payload",
            importance = 0.7f,
            confidence = 0.8f,
            updatedAt = now
        )
        val decisionEntry = BrainKnowledgeEntry(
            id = "decision",
            projectId = "p-types",
            knowledgeType = BrainKnowledgeType.DECISION,
            key = "target-key",
            content = "target-key payload",
            importance = 0.7f,
            confidence = 0.8f,
            updatedAt = now
        )
        val solutionEntry = BrainKnowledgeEntry(
            id = "solution",
            projectId = "p-types",
            knowledgeType = BrainKnowledgeType.SOLUTION,
            key = "target-key",
            content = "target-key payload",
            importance = 0.7f,
            confidence = 0.8f,
            updatedAt = now
        )

        val ranked = MemoryRanker.rankKnowledge(
            listOf(progressEntry, solutionEntry, decisionEntry, constraintEntry),
            "target-key",
            now = now
        )

        // Constraint (1.0), Decision (0.95), Solution (0.90) all rank above Progress (0.60)
        assertTrue(ranked.indexOfFirst { it.id == "constraint" } < ranked.indexOfFirst { it.id == "progress" })
        assertTrue(ranked.indexOfFirst { it.id == "decision" } < ranked.indexOfFirst { it.id == "progress" })
        assertTrue(ranked.indexOfFirst { it.id == "solution" } < ranked.indexOfFirst { it.id == "progress" })
    }

    // 14. Same-key conflict detection
    @Test
    fun test14_sameKeyConflictDetection() {
        val e1 = BrainKnowledgeEntry(projectId = "p1", key = "build-tool", content = "Gradle 8.14")
        val eSame = BrainKnowledgeEntry(projectId = "p1", key = "build-tool", content = "gradle 8.14")
        val eDiff = BrainKnowledgeEntry(projectId = "p1", key = "build-tool", content = "Maven 3.9")

        val conflictSame = MemoryConflictResolver.detectConflict(eSame, e1)
        assertEquals(ConflictType.SAME_KEY_SAME_CONTENT, conflictSame)

        val conflictDiff = MemoryConflictResolver.detectConflict(eDiff, e1)
        assertEquals(ConflictType.SUPERSEDES, conflictDiff) // Default AUTO incoming has equal trust to AUTO existing -> SUPERSEDES

        // Lower trust incoming
        val eLowerTrust = BrainKnowledgeEntry(
            projectId = "p1",
            key = "build-tool",
            content = "Bazel",
            source = MemorySource.AGENT_INFERRED
        )
        val eUserExisting = BrainKnowledgeEntry(
            projectId = "p1",
            key = "build-tool",
            content = "Gradle 8.14",
            source = MemorySource.USER
        )
        val conflictLower = MemoryConflictResolver.detectConflict(eLowerTrust, eUserExisting)
        assertEquals(ConflictType.SAME_KEY_DIFFERENT_CONTENT, conflictLower)
    }

    // 15. Supersession behavior
    @Test
    fun test15_supersessionBehavior() {
        val pId = "supersede-proj"
        val original = brainRepo.insert(
            BrainKnowledgeEntry(
                projectId = pId,
                key = "active-framework",
                content = "Android Views",
                source = MemorySource.AUTO,
                version = 1
            )
        )

        val incomingUser = BrainKnowledgeEntry(
            projectId = pId,
            key = "active-framework",
            content = "Jetpack Compose",
            source = MemorySource.USER
        )

        val saved = brainRepo.save(incomingUser)
        assertEquals("Jetpack Compose", saved.content)
        assertEquals(2, saved.version)

        val oldEntry = brainRepo.getById(original.id)
        assertNotNull(oldEntry)
        assertEquals(MemoryStatus.SUPERSEDED, oldEntry?.status)
        assertEquals(saved.id, oldEntry?.supersededBy)
    }

    // 16. Historical conflicting entries remain queryable
    @Test
    fun test16_historicalConflictingEntriesRemainQueryable() {
        val pId = "history-proj"
        val original = brainRepo.insert(
            BrainKnowledgeEntry(
                projectId = pId,
                key = "jdk-version",
                content = "JDK 17",
                source = MemorySource.AUTO
            )
        )

        brainRepo.save(
            BrainKnowledgeEntry(
                projectId = pId,
                key = "jdk-version",
                content = "JDK 21",
                source = MemorySource.USER
            )
        )

        // Query including historical/superseded
        val allEntries = brainRepo.findByProject(pId, status = null)
        assertEquals(2, allEntries.size)

        val supersededEntries = brainRepo.findByProject(pId, status = MemoryStatus.SUPERSEDED)
        assertEquals(1, supersededEntries.size)
        assertEquals("JDK 17", supersededEntries[0].content)
        assertEquals(original.id, supersededEntries[0].id)
    }

    // 17. Legacy MemoryType preservation
    @Test
    fun test17_legacyMemoryTypePreservation() {
        val pId = "legacy-proj"
        val entry = BrainKnowledgeEntry(
            projectId = pId,
            knowledgeType = BrainKnowledgeType.DECISION,
            key = "architecture-style",
            content = "MVI architecture",
            legacyType = MemoryType.DECISION
        )

        brainRepo.insert(entry)
        val fetched = brainRepo.getById(entry.id)
        assertNotNull(fetched)
        assertEquals(MemoryType.DECISION, fetched?.legacyType)

        // Lossless conversion check
        val memEntry = fetched!!.toMemoryEntry()
        assertEquals(MemoryType.DECISION, memEntry.type)
        val brainAgain = memEntry.toBrainKnowledgeEntry()
        assertEquals(MemoryType.DECISION, brainAgain.legacyType)
    }

    // 18. Retrieval character limit
    @Test
    fun test18_retrievalCharacterLimit() {
        val pId = "char-limit-proj"
        // Insert 5 entries with exact 40-char content + 10-char key = 50 chars each
        for (i in 1..5) {
            brainRepo.insert(
                BrainKnowledgeEntry(
                    projectId = pId,
                    key = "char-key-$i", // 10 chars
                    content = "A".repeat(40), // 40 chars -> total 50 chars per entry
                    importance = 0.5f + (i * 0.1f)
                )
            )
        }

        // Budget = 120 chars. Each entry is 50 chars. Exactly 2 entries should fit (100 <= 120, 3rd would be 150 > 120)
        val retrieved = brainRepo.retrieveRelevant(pId, query = null, limit = 10, maxCharacters = 120)
        assertEquals(2, retrieved.size)
        val totalChars = retrieved.sumOf { it.key.length + it.content.length }
        assertTrue("Total characters ($totalChars) must not exceed 120", totalChars <= 120)
    }

    // 19. Retrieval result-count limit
    @Test
    fun test19_retrievalResultCountLimit() {
        val pId = "count-limit-proj"
        for (i in 1..20) {
            brainRepo.insert(
                BrainKnowledgeEntry(
                    projectId = pId,
                    key = "item-$i",
                    content = "common topic message $i"
                )
            )
        }

        val retrieved = brainRepo.retrieveRelevant(pId, query = "common", limit = 4)
        assertEquals(4, retrieved.size)
    }

    // 20. Empty-query behavior
    @Test
    fun test20_emptyQueryBehavior() {
        val pId = "empty-query-proj"
        brainRepo.insert(BrainKnowledgeEntry(projectId = pId, key = "key1", content = "Important fact", importance = 0.9f))
        brainRepo.insert(BrainKnowledgeEntry(projectId = pId, key = "key2", content = "Secondary fact", importance = 0.4f))

        val fromNull = brainRepo.retrieveRelevant(pId, query = null, limit = 10)
        assertTrue(fromNull.isNotEmpty())
        assertEquals("key1", fromNull[0].key) // High importance ranked first

        val fromBlank = brainRepo.retrieveRelevant(pId, query = "   ", limit = 10)
        assertTrue(fromBlank.isNotEmpty())
        assertEquals("key1", fromBlank[0].key)
    }

    // 21. Duplicate retrieval stability
    @Test
    fun test21_duplicateRetrievalStability() {
        val pId = "dup-stability-proj"
        for (i in 1..8) {
            brainRepo.insert(BrainKnowledgeEntry(projectId = pId, key = "stability-$i", content = "Payload $i"))
        }

        val res1 = brainRepo.retrieveRelevant(pId, query = "Payload", limit = 5)
        val res2 = brainRepo.retrieveRelevant(pId, query = "Payload", limit = 5)

        assertEquals(res1.size, res2.size)
        for (i in res1.indices) {
            assertEquals(res1[i].id, res2[i].id)
            assertEquals(res1[i].key, res2[i].key)
        }
    }

    // 22. Cache maximum size
    @Test
    fun test22_cacheMaximumSize() {
        val pId = "cache-bound-proj"
        val customCache = BoundedProjectKnowledgeCache(maxEntriesPerProject = 100)
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()
        val db = BrainDatabase(driver)
        val repoWithCustomCache = BrainKnowledgeRepository(db, customCache)

        // Insert 125 entries
        for (i in 1..125) {
            repoWithCustomCache.insert(
                BrainKnowledgeEntry(
                    projectId = pId,
                    key = "cached-entry-$i",
                    content = "Value $i"
                )
            )
        }

        // Cache must be bounded at exactly 100
        assertEquals(100, customCache.size(pId))
    }

    // 23. Project isolation in cache
    @Test
    fun test23_projectIsolationInCache() {
        val cache = BoundedProjectKnowledgeCache(maxEntriesPerProject = 100)
        val eA = BrainKnowledgeEntry(projectId = "projA", key = "kA", content = "valA")
        val eB = BrainKnowledgeEntry(projectId = "projB", key = "kB", content = "valB")

        cache.put(eA)
        cache.put(eB)

        assertEquals(1, cache.size("projA"))
        assertEquals(1, cache.size("projB"))
        assertNotNull(cache.get("projA", eA.id))
        assertNull(cache.get("projB", eA.id)) // A cannot be retrieved under B
        assertNotNull(cache.get("projB", eB.id))
    }

    // 24. Concurrent repository access
    @Test
    fun test24_concurrentRepositoryAccess() {
        val pId = "concurrent-proj"
        val executor = Executors.newFixedThreadPool(8)
        val tasks = mutableListOf<Callable<Unit>>()

        for (i in 1..40) {
            tasks.add(Callable {
                brainRepo.insert(
                    BrainKnowledgeEntry(
                        projectId = pId,
                        key = "thread-item-$i",
                        content = "Concurrent test data $i"
                    )
                )
                Unit
            })
            tasks.add(Callable {
                brainRepo.retrieveRelevant(pId, query = "Concurrent", limit = 5)
                Unit
            })
        }

        val futures = executor.invokeAll(tasks)
        for (f in futures) {
            f.get() // Will throw if any exception occurred
        }
        executor.shutdown()

        val total = brainRepo.findByProject(pId)
        assertEquals(40, total.size)
    }

    // 25. SQLite transaction integrity
    @Test
    fun test25_sqliteTransactionIntegrity() {
        val pId = "tx-proj"
        val old = brainRepo.insert(BrainKnowledgeEntry(projectId = pId, key = "tx-key", content = "Initial"))

        val replacement = BrainKnowledgeEntry(projectId = pId, key = "tx-key", content = "Replaced")
        brainRepo.supersede(old.id, replacement)

        val oldFetched = brainRepo.getById(old.id)
        val newFetched = brainRepo.getById(replacement.id)

        assertEquals(MemoryStatus.SUPERSEDED, oldFetched?.status)
        assertEquals(replacement.id, oldFetched?.supersededBy)
        assertEquals(MemoryStatus.ACTIVE, newFetched?.status)
        assertEquals("Replaced", newFetched?.content)
    }
}
