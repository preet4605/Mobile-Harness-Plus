package com.jarves.mh.runtime.task

import android.content.Context
import android.os.Looper
import android.os.PowerManager
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WakeLockManagerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun advance(minutes: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(minutes))
    }

    @Test
    fun `lock stays held past the 15 minute guard while a task is active`() {
        val manager = WakeLockManager(context)
        manager.acquire("task-1")
        assertTrue(manager.isHeld)

        // A silent Gradle build: nothing calls acquire again for far longer than the guard.
        repeat(8) { advance(minutes = 5) }

        assertTrue("wake lock lapsed during a long silent task", manager.isHeld)
        manager.release("task-1")
    }

    @Test
    fun `test clock really expires an unrenewed 15 minute lock`() {
        // Guards the first test against being vacuous: without renewal the lock does lapse.
        val power = context.getSystemService(PowerManager::class.java)
        val raw = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "test:unrenewed")
        raw.acquire(WakeLockManager.TASK_WAKE_LOCK_TIMEOUT_MS)
        assertTrue(raw.isHeld)

        advance(minutes = 16)

        assertFalse(raw.isHeld)
    }

    @Test
    fun `release stops renewal and lets go of the lock`() {
        val manager = WakeLockManager(context)
        manager.acquire("task-1")
        manager.release("task-1")
        assertFalse(manager.isHeld)

        advance(minutes = 20)

        assertFalse("renewal re-took the lock after the task ended", manager.isHeld)
    }

    @Test
    fun `pausing the only task stops renewal`() {
        val manager = WakeLockManager(context)
        manager.acquire("task-1")
        manager.pause("task-1")
        assertFalse(manager.isHeld)

        advance(minutes = 20)

        assertFalse("renewal kept the lock while waiting for the user", manager.isHeld)

        manager.resume("task-1")
        assertTrue(manager.isHeld)
        manager.release("task-1")
    }

    @Test
    fun `lock survives while one of two tasks is still running`() {
        val manager = WakeLockManager(context)
        manager.acquire("task-1")
        manager.acquire("task-2")
        manager.release("task-1")

        repeat(5) { advance(minutes = 5) }

        assertTrue(manager.isHeld)
        manager.release("task-2")
        assertFalse(manager.isHeld)
    }

    @Test
    fun `manager without a context is a safe no-op`() {
        val manager = WakeLockManager(null)
        manager.acquire("task-1")
        advance(minutes = 20)
        assertFalse(manager.isHeld)
        manager.release("task-1")
    }
}
