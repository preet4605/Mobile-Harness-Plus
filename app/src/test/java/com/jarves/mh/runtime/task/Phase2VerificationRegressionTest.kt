package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.StepStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.jarves.mh.runtime.WorkspaceCheckpoints
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Phase2VerificationRegressionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun supervisor(): TaskSupervisor = TaskSupervisor.createForTesting(
        database = BrainDatabase(BrainDatabaseDriverFactory.createInMemoryDriver())
    ).apply {
        val root = tmp.newFolder()
        workspaceDirectoryResolver = { root }
        val checkpoints = WorkspaceCheckpoints(tmp.newFolder())
        checkpointsResolver = { checkpoints }
    }

    @Test fun generatedPlansDoNotInventVerification() {
        val plan = DefaultTaskDecomposer().decompose("task", "Create result.txt containing DONE",
            TaskDecompositionContext(acceptanceCriteria = listOf("result.txt contains DONE")))
        assertNull(plan.steps.single().verificationCommand)
        val task = CanonicalTask(taskId = "task", projectId = "p", projectSlug = "p", objective = "Create result.txt", plan = plan)
        val result = DefaultStepVerifier().verify(task, plan.steps.single(), tmp.newFolder())
        assertFalse("Missing result.txt must not be verified", result.passed)
    }

    @Test fun generatedMilestonesDoNotInventVerification() {
        val plan = DefaultTaskDecomposer().decompose("task", "1. Inspect files\n2. Explain findings",
            TaskDecompositionContext(acceptanceCriteria = listOf("Explain findings")))
        assertEquals(2, plan.steps.size)
        assertTrue(plan.steps.all { it.verificationCommand == null })
    }

    @Test fun noCriteriaFinishesUnverifiedWithoutReplayOrSuccessMemory() = runBlocking {
        val supervisor = supervisor()
        val task = supervisor.createTask(taskId = "task", projectId = "p", projectSlug = "p", chatId = "c",
            agentKind = "CODEX", providerJson = "{}", prompt = "Explain the project")
        val runs = AtomicInteger()
        supervisor.executeTask(task.taskId) { _ -> runs.incrementAndGet(); Unit }.join()
        assertEquals(1, runs.get())
        assertEquals("UNVERIFIED", supervisor.stateStore.get(task.taskId)!!.status.name)
        val saved = supervisor.canonicalTaskRepository.getTask(task.taskId)!!
        assertEquals("UNVERIFIED", saved.plan.steps.single().status.name)
        assertEquals("UNVERIFIED", saved.plan.status.name)
        assertFalse(saved.outcome?.success == true)
        assertTrue(supervisor.brainKnowledgeRepository.findByProject("p").none {
            it.key.contains("verified-outcome") || it.key.contains("step:")
        })
        val again = TaskSupervisor.createForTesting(database = supervisor.database)
        assertEquals("UNVERIFIED", again.stateStore.get(task.taskId)!!.status.name)
        again.executeTask(task.taskId) { _ -> runs.incrementAndGet(); Unit }.join()
        assertEquals("Terminal unverified tasks must not replay after recreation", 1, runs.get())
    }

    @Test fun legacyTrueCannotProveTaskSuccess() = runBlocking {
        val supervisor = supervisor()
        val step = ExecutionStep(stepOrder = 0, title = "Execute Task", description = "Create result.txt", verificationCommand = "true")
        val task = supervisor.createTask(taskId = "legacy", projectId = "p", projectSlug = "p", chatId = "c",
            agentKind = "CLAUDE_CODE", providerJson = "{}", prompt = "Create result.txt", plan = ExecutionPlan(steps = listOf(step)))
        supervisor.executeTask(task.taskId) { _ -> }.join()
        assertEquals("UNVERIFIED", supervisor.stateStore.get(task.taskId)!!.status.name)
    }

    @Test fun concreteCriteriaRemainAuthoritative() = runBlocking {
        val supervisor = supervisor()
        val step = ExecutionStep(stepOrder = 0, title = "Write result", description = "Write result",
            expectedFiles = listOf("result.txt"), expectedContent = mapOf("result.txt" to "DONE"))
        supervisor.createTask(taskId = "concrete", projectId = "p", projectSlug = "p", chatId = "c",
            agentKind = "CODEX", providerJson = "{}", prompt = "Write result", plan = ExecutionPlan(steps = listOf(step)))
        supervisor.executeTask("concrete") { _ -> File(supervisor.workspaceDirectoryResolver!!.invoke("p")!!, "result.txt").writeText("DONE") }.join()
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get("concrete")!!.status)
    }

    @Test fun verboseOutputIsDrainedAndBounded() {
        val result = ControlledStepCommandRunner().execute(
            "i=0; while [ \$i -lt 20000 ]; do printf '0123456789012345678901234567890123456789012345678901234567890123456789'; i=\$((i+1)); done",
            tmp.newFolder(), 5)
        assertEquals(result.second, 0, result.first)
        assertTrue("Verification output must be bounded", result.second.length <= 65536 + 128)
    }

    @Test fun interruptedVerifierDoesNotSwallowCancellation() {
        val root = tmp.newFolder()
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val thread = Thread {
            started.countDown()
            try {
                ControlledStepCommandRunner().execute("while :; do :; done", root, 30)
            } catch (_: InterruptedException) {
                // Expected cancellation.
            } finally { stopped.countDown() }
        }
        thread.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        thread.interrupt()
        assertTrue("Cancellation must promptly leave the command runner", stopped.await(3, TimeUnit.SECONDS))
    }
    @Test fun restartDuringUnverifiedVerificationDoesNotReexecute() = runBlocking {
        val supervisor = supervisor()
        val step = ExecutionStep(stepOrder = 0, title = "Explain", description = "Explain findings", status = StepStatus.VERIFYING)
        supervisor.createTask(taskId = "restart", projectId = "p", projectSlug = "p", chatId = "c", agentKind = "CODEX",
            providerJson = "{}", prompt = "Explain", plan = ExecutionPlan(steps = listOf(step)))
        val runs = AtomicInteger()
        supervisor.executeTask("restart") { _ -> runs.incrementAndGet(); Unit }.join()
        assertEquals(0, runs.get())
        assertEquals(TaskExecutionStatus.UNVERIFIED, supervisor.stateStore.get("restart")!!.status)
    }

    private class HangingProcess : Process() {
        val waiting = CountDownLatch(1)
        val killed = CountDownLatch(1)
        val reaped = CountDownLatch(1)
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int { waiting.countDown(); killed.await(); reaped.countDown(); return 137 }
        override fun exitValue(): Int = if (killed.count == 0L) 137 else throw IllegalThreadStateException()
        override fun destroy() { killed.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    @Test fun timeoutKillsAndReapsVerificationProcess() {
        val process = HangingProcess()
        val runner = ControlledStepCommandRunner { _, _ -> process }
        val result = runner.execute("check-result", tmp.newFolder(), 1)
        assertEquals(-1, result.first)
        assertTrue(result.second.contains("timed out"))
        assertEquals(0L, process.killed.count)
        assertEquals(0L, process.reaped.count)
    }

    @Test fun stopDuringVerificationCleansUpWithoutRecovery() = runBlocking {
        val supervisor = supervisor()
        val process = HangingProcess()
        supervisor.stepVerifier = DefaultStepVerifier(confinementRunner = ControlledStepCommandRunner { _, _ -> process })
        val step = ExecutionStep(stepOrder = 0, title = "Check", description = "Check output", verificationCommand = "check-result")
        supervisor.createTask(taskId = "cancel", projectId = "p", projectSlug = "p", chatId = "c", agentKind = "CODEX",
            providerJson = "{}", prompt = "Check", plan = ExecutionPlan(steps = listOf(step)))
        val runs = AtomicInteger()
        val job = supervisor.executeTask("cancel") { _ -> runs.incrementAndGet(); Unit }
        assertTrue(withContext(Dispatchers.IO) { process.waiting.await(5, TimeUnit.SECONDS) })
        supervisor.requestStop("cancel", force = true)
        withTimeout(4000) { job.join() }
        assertEquals(0L, process.killed.count)
        assertEquals(0L, process.reaped.count)
        assertEquals(1, runs.get())
        assertEquals(TaskExecutionStatus.CANCELLED, supervisor.stateStore.get("cancel")!!.status)
        assertTrue(supervisor.canonicalTaskRepository.getTask("cancel")!!.failureHistory.isEmpty())
    }

    @Test fun rejectedProcessOwnershipStillKillsAndReaps() {
        val process = HangingProcess()
        val runner = ControlledStepCommandRunner(onProcessStarted = { error("stale owner") }) { _, _ -> process }
        val result = runner.execute("check-result", tmp.newFolder(), 1)
        assertEquals(-1, result.first)
        assertTrue(result.second.contains("stale owner"))
        assertEquals(0L, process.killed.count)
        assertEquals(0L, process.reaped.count)
    }

    @Test fun commandRunnerReceivesTaskIdentityAndSelectedRoot() {
        val root = tmp.newFolder("selected-root")
        val task = CanonicalTask(taskId = "task-id", projectId = "project-id", projectSlug = "project-slug", objective = "Check")
        val step = ExecutionStep(stepOrder = 0, title = "Check", description = "Check result", verificationCommand = "check-result")
        val runner = object : StepCommandRunner {
            override fun execute(command: String, workingDir: File, timeoutSeconds: Long): Pair<Int, String> =
                error("Task context must not be dropped")
            override fun execute(owner: CanonicalTask, command: String, workingDir: File, timeoutSeconds: Long): Pair<Int, String> {
                assertEquals("task-id", owner.taskId)
                assertEquals("project-id", owner.projectId)
                assertEquals(root.canonicalFile, workingDir)
                assertEquals("check-result", command)
                return Pair(0, "result checked")
            }
        }
        assertTrue(DefaultStepVerifier(confinementRunner = runner).verify(task, step, root).passed)
    }

    @Test fun processWaitTimeoutIsReportedAsTimeout() {
        val process = object : Process() {
            override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun waitFor(): Int = throw TimeoutException()
            override fun exitValue(): Int = 137
            override fun destroy() = Unit
            override fun destroyForcibly(): Process = this
        }
        val result = ControlledStepCommandRunner { _, _ -> process }.execute("check-result", tmp.newFolder(), 1)
        assertEquals(-1, result.first)
        assertTrue(result.second.contains("timed out"))
    }

}
