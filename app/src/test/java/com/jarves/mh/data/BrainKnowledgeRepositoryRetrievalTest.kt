package com.jarves.mh.data

import com.jarves.mh.model.brain.BrainKnowledgeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BrainKnowledgeRepositoryRetrievalTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var memoryStore: ContextMemoryStore
    private lateinit var brainRepo: BrainKnowledgeRepository

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("brain_retrieval_test_${System.nanoTime()}")
        memoryStore = ContextMemoryStore(baseDir)
        brainRepo = memoryStore.brainKnowledgeRepository
    }

    @Test
    fun searchWith1000PlusTermsDoesNotExceedSqliteExpressionDepth() {
        val projectId = "expression-depth-search"

        brainRepo.insert(
            BrainKnowledgeEntry(
                projectId = projectId,
                key = "target-entry",
                content = "needle-expression-depth regression target"
            )
        )

        val query = buildList {
            add("needle-expression-depth")
            repeat(1500) { index ->
                add("noise-term-$index")
            }
        }.joinToString(" ")

        val results = brainRepo.search(projectId, query, limit = 20)

        assertTrue(
            "1000+ term search must complete without SQLite expression-depth failure",
            results.any { it.key == "target-entry" }
        )
    }

    @Test
    fun retrieveRelevantWith1000PlusTermsDoesNotExceedSqliteExpressionDepth() {
        val projectId = "expression-depth-relevant"

        brainRepo.insert(
            BrainKnowledgeEntry(
                projectId = projectId,
                key = "target-entry",
                content = "needle-relevant regression target"
            )
        )

        val query = buildList {
            add("needle-relevant")
            repeat(1500) { index ->
                add("irrelevant-term-$index")
            }
        }.joinToString(" ")

        val results = brainRepo.retrieveRelevant(
            projectId = projectId,
            query = query,
            limit = 20
        )

        assertTrue(
            "retrieveRelevant must complete without SQLite expression-depth failure",
            results.any { it.key == "target-entry" }
        )
    }

    @Test
    fun veryLongQueryIsBoundedWithoutFailure() {
        val projectId = "long-query"

        brainRepo.insert(
            BrainKnowledgeEntry(
                projectId = projectId,
                key = "long-query-target",
                content = "bounded lexical retrieval target"
            )
        )

        val hugeQuery = buildString {
            append("bounded")
            repeat(500) {
                append(" ")
                append("x".repeat(200))
            }
        }

        val results = brainRepo.search(projectId, hugeQuery, limit = 20)

        assertTrue(
            "Very long lexical query must be safely bounded",
            results.any { it.key == "long-query-target" }
        )
    }

    @Test
    fun punctuationOnlyQueryDoesNotBuildInvalidSqlExpression() {
        val projectId = "punctuation-query"

        brainRepo.insert(
            BrainKnowledgeEntry(
                projectId = projectId,
                key = "entry-one",
                content = "first entry"
            )
        )

        brainRepo.insert(
            BrainKnowledgeEntry(
                projectId = projectId,
                key = "entry-two",
                content = "second entry"
            )
        )

        val results = brainRepo.search(
            projectId,
            "!@#\$%^&*()-=[]{}:;,./?"
        )

        assertEquals(
            "Punctuation-only query should safely fall back to project retrieval",
            2,
            results.size
        )
        assertTrue(results.any { it.key == "entry-one" })
        assertTrue(results.any { it.key == "entry-two" })
    }

    @Test
    fun normalShortQueryBehaviorRemainsFunctional() {
        val projectId = "normal-query"

        brainRepo.insert(
            BrainKnowledgeEntry(
                projectId = projectId,
                key = "gradle-build",
                content = "Use Gradle for Android build verification"
            )
        )

        brainRepo.insert(
            BrainKnowledgeEntry(
                projectId = projectId,
                key = "network-config",
                content = "Configure network client timeout"
            )
        )

        val results = brainRepo.search(projectId, "Gradle")

        assertFalse(results.isEmpty())
        assertTrue(results.any { it.key == "gradle-build" })
    }
}
