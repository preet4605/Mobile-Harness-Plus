package com.jarves.mh.runtime

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuthForegroundServiceTest {
    @Test fun `one login finishing cannot stop another login service`() {
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(context)
        val a = AuthForegroundService.acquire(context, "A")
        val b = AuthForegroundService.acquire(context, "B")
        assertEquals(RuntimeExecutionService.ACTION_START, shadow.nextStartedService.action)
        assertEquals(RuntimeExecutionService.ACTION_START, shadow.nextStartedService.action)
        AuthForegroundService.release(context, a)
        assertNull(shadow.nextStartedService)
        AuthForegroundService.release(context, a)
        assertNull(shadow.nextStartedService)
        AuthForegroundService.release(context, b)
        assertEquals(RuntimeExecutionService.ACTION_CANCELLED, shadow.nextStartedService.action)
    }

    @Test fun `login finishing cannot stop a running coding task service`() {
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(context)
        val owner = AuthForegroundService.acquire(context, "Fixture")
        shadow.nextStartedService
        RuntimeTaskController.stopAction = {}
        try {
            AuthForegroundService.release(context, owner)
            assertNull(shadow.nextStartedService)
        } finally { RuntimeTaskController.stopAction = null }
    }
}
