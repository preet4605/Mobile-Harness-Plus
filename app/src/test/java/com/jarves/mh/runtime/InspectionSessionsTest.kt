package com.jarves.mh.runtime

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class InspectionSessionsTest {
    @Test fun aRevokedOwnerLosesAccessBeforeItsProcessFinishesStopping() {
        val sessions = InspectionSessions(); var allowed = true
        sessions.register("owner", File("project"), "/workspace/project") { allowed }
        assertNotNull(sessions.resolve("owner")); allowed = false
        assertNull(sessions.resolve("owner"))
    }
    @Test fun terminatedProcessesLoseAccessAndOtherLaunchesKeepTheirScopes() {
        val sessions = InspectionSessions(); var firstAlive = true
        sessions.register("one", File("project-one"), "/workspace/one") { firstAlive }
        sessions.register("two", File("project-two"), "/workspace/two") { true }
        assertEquals("/workspace/one", sessions.resolve("one")!!.guestRoot)
        firstAlive = false
        assertNull(sessions.resolve("one")); assertEquals("/workspace/two", sessions.resolve("two")!!.guestRoot)
    }
    @Test fun capacityDoesNotEvictLiveLaunchesAndDeadEntriesAreReclaimed() {
        val sessions = InspectionSessions(); var alive = true
        repeat(64) { sessions.register("$it", File("project"), "/workspace/project") { alive } }
        assertFalse(sessions.register("overflow", File("other"), "/workspace/other") { true })
        assertNotNull(sessions.resolve("0")); alive = false
        assertTrue(sessions.register("next", File("other"), "/workspace/other") { true })
    }
}
