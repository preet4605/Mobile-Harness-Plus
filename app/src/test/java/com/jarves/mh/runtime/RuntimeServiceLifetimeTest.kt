package com.jarves.mh.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RuntimeServiceLifetimeTest {
    @Test fun `finishing stops the service through an ordered intent, not stopService`() {
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(context)
        RuntimeExecutionService.finish(context, "Task completed", "done", failed = false)
        assertEquals(RuntimeExecutionService.ACTION_CANCELLED, shadow.nextStartedService.action)
        assertNull(shadow.nextStoppedService)
    }

    @Test fun `finishing while another task runs keeps the service`() {
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(context)
        RuntimeExecutionService.finish(context, "Task failed", "busy", failed = true, stopService = false)
        assertNull(shadow.nextStartedService)
        assertNull(shadow.nextStoppedService)
    }

    @Test fun `cancel is ordered too`() {
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(context)
        RuntimeExecutionService.cancel(context)
        assertEquals(RuntimeExecutionService.ACTION_CANCELLED, shadow.nextStartedService.action)
        assertNull(shadow.nextStoppedService)
    }

    @Test fun `finalizeTask leaves the shared service to the last running task`() {
        val source = File("src/main/java/com/jarves/mh/runtime/task/TaskSupervisor.kt").readText()
        val finalize = source.substringAfter("fun finalizeTask(").substringBefore("return finalizedRecord")
        assertTrue(finalize.contains("val anotherTaskRunning = activeJobs.any { (id, job) -> id != targetTaskId && job.isActive }"))
        assertEquals(2, Regex("stopService = !anotherTaskRunning").findAll(finalize).count())
        assertTrue(finalize.contains("if (appContext != null && !anotherTaskRunning) {\n                    RuntimeExecutionService.cancel(appContext)"))
        assertTrue("Each task still drops its own wake-lock hold", finalize.contains("wakeLockManager?.release(targetTaskId)"))
    }

    @Test fun `service releases every task it held a wake lock for`() {
        val source = File("src/main/java/com/jarves/mh/runtime/RuntimeExecutionService.kt").readText()
        assertTrue(source.contains("acquiredWakeLockTaskIds.forEach { supervisor.wakeLockManager.release(it) }"))
    }
}
