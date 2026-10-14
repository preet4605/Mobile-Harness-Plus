package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.runtime.task.DurableTaskRecord
import com.jarves.mh.runtime.task.TaskExecutionStatus
import com.jarves.mh.runtime.task.TaskStateStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexNativeControlTest {
    @Test fun nativeCommandsValidateArgumentsBeforeAdmission() {
        assertEquals("compact", CodexNativeControl.parse("compact", "").kind)
        assertThrows(IllegalArgumentException::class.java) { CodexNativeControl.parse("compact", "extra") }
        assertEquals("goalStatus", CodexNativeControl.parse("goal", "").kind)
        assertEquals("goalPause", CodexNativeControl.parse("goal", "pause").kind)
        assertEquals("goalResume", CodexNativeControl.parse("goal", "resume").kind)
        assertEquals("goalClear", CodexNativeControl.parse("goal", "clear").kind)
        val goal = CodexNativeControl.parse("goal", "--budget 42000 Keep tests green")
        assertEquals(42000L, goal.tokenBudget)
        assertEquals("Keep tests green", goal.text)
        assertThrows(IllegalArgumentException::class.java) { CodexNativeControl.parse("goal", "--budget 0 objective") }
        assertThrows(IllegalArgumentException::class.java) { CodexNativeControl.parse("goal", "--budget many objective") }
        assertThrows(IllegalArgumentException::class.java) { CodexNativeControl.parse("goal", "x".repeat(4001)) }
        assertEquals("baseBranch", CodexNativeControl.parse("review", "--base main").target)
        assertEquals("commit", CodexNativeControl.parse("review", "--commit abc1234").target)
        assertThrows(IllegalArgumentException::class.java) { CodexNativeControl.parse("review", "app/src") }
        assertThrows(IllegalArgumentException::class.java) { CodexNativeControl.parse("review", "--base main extra") }
        assertEquals("planOff", CodexNativeControl.parse("plan", "off").kind)
        assertEquals("planTurn", CodexNativeControl.parse("plan", "Analyze this feature").kind)
    }

    @Test fun commandEncodingCannotBeConfusedWithInjectedPromptText() {
        val control = CodexNativeControl.parse("goal", "--budget 1234 inspect \"quoted\" objective")
        assertEquals(control, CodexNativeControl.decode(control.encode()))
        assertThrows(IllegalArgumentException::class.java) { CodexNativeControl.decode("summarize /compact") }
        assertThrows(IllegalArgumentException::class.java) { CodexNativeControl.decode("{\"kind\":\"arbitrary\"}") }
    }

    @Test fun nativeLaunchRetainsRouteIsolationSkillsAndInspector() {
        val route = CodexRouteMapper.forProfile(ProviderProfile(kind = ProviderKind.CUSTOM, baseUrl = "https://fixture.invalid/v1", model = "model"))
        val args = CodexLaunchBuilder.appServerCommand(route, "high", true, "skills.config=[]")
        assertEquals(listOf(CodexLaunchBuilder.CODEX_GUEST_PATH, "app-server"), args.take(2))
        assertTrue(args.contains("approval_policy=\"never\""))
        assertTrue(args.contains("sandbox_mode=\"danger-full-access\""))
        assertTrue(args.contains("skills.config=[]"))
        assertTrue(args.any { it.startsWith("mcp_servers.pocket_native_inspection=") })
        assertTrue(args.any { it.startsWith("model_providers.") && it.contains("wire_api=\"responses\"") })
        assertFalse(args.contains("exec"))
    }

    @Test fun durableControlReopensAndLegacyRowsRemainReadable() {
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()
        val db = BrainDatabase(driver)
        val encoded = CodexNativeControl.parse("goal", "--budget 123 build feature").encode()
        val task = DurableTaskRecord("task", "project", "slug", "chat", "CODEX", "CHATGPT", "rules and attachment envelope", TaskExecutionStatus.CREATED,
            nativeControlJson = encoded)
        TaskStateStore(db).save(task)
        assertEquals(encoded, TaskStateStore(db).get("task")!!.nativeControlJson)
        TaskStateStore(db).save(task.copy(taskId = "legacy", nativeControlJson = null))
        assertNull(TaskStateStore(db).get("legacy")!!.nativeControlJson)
        assertEquals("rules and attachment envelope", TaskStateStore(db).get("task")!!.prompt)
        db.close()
    }

    @Test fun nativeGoalDisplaysReturnedLifecycleAndBudgetWithoutInferringCompletion() {
        val active = CodexNativeGoal.parse(JSONObject("""{"threadId":"thread","objective":"build","status":"budgetLimited","tokensUsed":4000,"timeUsedSeconds":50,"tokenBudget":3000}"""))
        assertEquals("budgetLimited", active.status)
        assertTrue(active.describe().contains("4000 / 3000"))
        assertFalse(active.describe().contains("completed"))
    }
}
