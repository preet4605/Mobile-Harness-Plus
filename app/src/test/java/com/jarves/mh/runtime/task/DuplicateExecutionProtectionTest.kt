package com.jarves.mh.runtime.task

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DuplicateExecutionProtectionTest {

    private lateinit var lock: TaskExecutionLock

    @Before
    fun setUp() {
        lock = TaskExecutionLock()
    }

    @Test
    fun `test single task execution succeeds and releases lock`() = runBlocking {
        var executed = false
        lock.withExecutionLock("task-1", "project-1") {
            assertTrue(lock.isTaskActive("task-1"))
            assertTrue(lock.isProjectActive("project-1"))
            executed = true
        }

        assertTrue(executed)
        assertFalse(lock.isTaskActive("task-1"))
        assertFalse(lock.isProjectActive("project-1"))
    }

    @Test
    fun `test concurrent execution of same task throws DuplicateExecutionException`() = runBlocking {
        val successCount = AtomicInteger(0)
        val duplicateBlockedCount = AtomicInteger(0)

        val jobs = (1..5).map {
            async(Dispatchers.Default) {
                try {
                    lock.withExecutionLock("same-task-id", "project-1") {
                        successCount.incrementAndGet()
                        delay(100)
                    }
                } catch (e: DuplicateExecutionException) {
                    duplicateBlockedCount.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertEquals(1, successCount.get())
        assertEquals(4, duplicateBlockedCount.get())
    }

    @Test
    fun `test concurrent execution in same project throws DuplicateExecutionException`() = runBlocking {
        val successCount = AtomicInteger(0)
        val duplicateBlockedCount = AtomicInteger(0)

        val jobs = listOf("task-A", "task-B", "task-C").map { taskId ->
            async(Dispatchers.Default) {
                try {
                    lock.withExecutionLock(taskId, "same-project-id") {
                        successCount.incrementAndGet()
                        delay(100)
                    }
                } catch (e: DuplicateExecutionException) {
                    duplicateBlockedCount.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertEquals(1, successCount.get())
        assertEquals(2, duplicateBlockedCount.get())
    }

    @Test
    fun `test lock is released even when task throws exception`() = runBlocking {
        try {
            lock.withExecutionLock("failing-task", "proj-error") {
                error("Simulated crash")
            }
            fail("Expected exception not thrown")
        } catch (_: IllegalStateException) {
            // Expected
        }

        assertFalse(lock.isTaskActive("failing-task"))
        assertFalse(lock.isProjectActive("proj-error"))

        // Next task in same project must succeed
        var nextExecuted = false
        lock.withExecutionLock("next-task", "proj-error") {
            nextExecuted = true
        }
        assertTrue(nextExecuted)
    }
}
