package com.jarves.mh.data

import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.RecoveryPlan
import com.jarves.mh.model.brain.RecoveryStrategy
import com.jarves.mh.model.brain.StepStatus
import com.jarves.mh.model.brain.TaskFailureRecord
import com.jarves.mh.model.brain.TaskOutcome
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * SQLite persistence repository for CanonicalTask, ExecutionPlan,
 * ExecutionStep, TaskFailureRecord, and RecoveryPlan.
 */
class CanonicalTaskRepository(private val db: BrainDatabase) {

    private fun jsonArrayToList(jsonStr: String?): List<String> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun listToJsonArray(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun outcomeToJson(outcome: TaskOutcome?): String? {
        if (outcome == null) return null
        return runCatching {
            val obj = JSONObject()
            obj.put("success", outcome.success)
            obj.put("summary", outcome.summary)
            val filesArr = JSONArray()
            outcome.filesModified.forEach { filesArr.put(it) }
            obj.put("filesModified", filesArr)
            obj.put("testsExecuted", outcome.testsExecuted)
            obj.put("testsPassed", outcome.testsPassed)
            obj.put("durationSeconds", outcome.durationSeconds)
            obj.put("completedAt", outcome.completedAt.toEpochMilli())
            obj.toString()
        }.getOrNull()
    }

    private fun jsonToOutcome(jsonStr: String?): TaskOutcome? {
        if (jsonStr.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(jsonStr)
            val filesArr = obj.optJSONArray("filesModified")
            val filesList = mutableListOf<String>()
            if (filesArr != null) {
                for (i in 0 until filesArr.length()) {
                    filesList.add(filesArr.getString(i))
                }
            }
            TaskOutcome(
                success = obj.optBoolean("success", false),
                summary = obj.optString("summary", ""),
                filesModified = filesList,
                testsExecuted = obj.optBoolean("testsExecuted", false),
                testsPassed = obj.optBoolean("testsPassed", false),
                durationSeconds = obj.optLong("durationSeconds", 0L),
                completedAt = Instant.ofEpochMilli(obj.optLong("completedAt", System.currentTimeMillis()))
            )
        }.getOrNull()
    }

    fun saveTask(task: CanonicalTask) {
        db.driver.transaction {
            val sql = """
                INSERT OR REPLACE INTO canonical_tasks (
                    task_id, project_id, project_slug, objective,
                    constraints_json, acceptance_criteria_json,
                    initial_workspace_sha, current_workspace_sha, outcome_json,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            db.driver.execute(
                sql,
                listOf(
                    task.taskId,
                    task.projectId,
                    task.projectSlug,
                    task.objective,
                    listToJsonArray(task.constraints),
                    listToJsonArray(task.acceptanceCriteria),
                    task.initialWorkspaceSha,
                    task.currentWorkspaceSha,
                    outcomeToJson(task.outcome),
                    task.createdAt.toEpochMilli(),
                    task.updatedAt.toEpochMilli()
                )
            )

            // Save steps
            db.driver.execute("DELETE FROM execution_steps WHERE task_id = ?", listOf(task.taskId))
            val stepSql = """
                INSERT INTO execution_steps (
                    step_id, task_id, step_order, title, description,
                    expected_files_json, status, attempts, max_attempts,
                    result_summary, checkpoint_tag, started_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            task.plan.steps.forEachIndexed { index, step ->
                db.driver.execute(
                    stepSql,
                    listOf(
                        step.stepId,
                        task.taskId,
                        index,
                        step.title,
                        step.description,
                        listToJsonArray(step.expectedFiles),
                        step.status.name,
                        step.attempts,
                        step.maxAttempts,
                        step.resultSummary,
                        step.checkpointTag,
                        step.startedAt?.toEpochMilli(),
                        step.completedAt?.toEpochMilli()
                    )
                )
            }

            // Save failures
            task.failureHistory.forEach { failure ->
                val failureSql = """
                    INSERT OR REPLACE INTO task_failures (
                        failure_id, task_id, step_id, classification,
                        error_message, error_snippet, mutated_files_json,
                        matched_solution_id, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()

                db.driver.execute(
                    failureSql,
                    listOf(
                        failure.failureId,
                        task.taskId,
                        failure.stepId,
                        failure.classification,
                        failure.errorMessage,
                        failure.errorSnippet,
                        listToJsonArray(failure.mutatedFiles),
                        failure.matchedSolutionId,
                        failure.timestamp.toEpochMilli()
                    )
                )
            }

            // Save active recovery plan if present
            task.activeRecoveryPlan?.let { recovery ->
                val recoverySql = """
                    INSERT OR REPLACE INTO recovery_plans (
                        recovery_id, task_id, failure_record_id, strategy,
                        rationale, files_to_rollback_json, forward_fix_instructions,
                        target_step_index, approved_by_user, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()

                db.driver.execute(
                    recoverySql,
                    listOf(
                        recovery.recoveryId,
                        task.taskId,
                        recovery.failureRecordId,
                        recovery.strategy.name,
                        recovery.rationale,
                        listToJsonArray(recovery.filesToRollback),
                        recovery.forwardFixInstructions,
                        recovery.targetStepIndex,
                        if (recovery.approvedByUser) 1 else 0,
                        recovery.createdAt.toEpochMilli()
                    )
                )
            }
        }
    }

    fun getTask(taskId: String): CanonicalTask? {
        val taskRow = db.driver.query(
            "SELECT * FROM canonical_tasks WHERE task_id = ? LIMIT 1",
            listOf(taskId)
        ) { row ->
            CanonicalTaskRow(
                taskId = row.getString("task_id") ?: "",
                projectId = row.getString("project_id") ?: "",
                projectSlug = row.getString("project_slug") ?: "",
                objective = row.getString("objective") ?: "",
                constraints = jsonArrayToList(row.getString("constraints_json")),
                acceptanceCriteria = jsonArrayToList(row.getString("acceptance_criteria_json")),
                initialWorkspaceSha = row.getString("initial_workspace_sha"),
                currentWorkspaceSha = row.getString("current_workspace_sha"),
                outcome = jsonToOutcome(row.getString("outcome_json")),
                createdAt = Instant.ofEpochMilli(row.getLong("created_at") ?: System.currentTimeMillis()),
                updatedAt = Instant.ofEpochMilli(row.getLong("updated_at") ?: System.currentTimeMillis()),
            )
        }.firstOrNull() ?: return null

        // Fetch execution steps
        val steps = db.driver.query(
            "SELECT * FROM execution_steps WHERE task_id = ? ORDER BY step_order ASC",
            listOf(taskId)
        ) { row ->
            ExecutionStep(
                stepId = row.getString("step_id") ?: "",
                stepOrder = row.getInt("step_order") ?: 0,
                title = row.getString("title") ?: "",
                description = row.getString("description") ?: "",
                expectedFiles = jsonArrayToList(row.getString("expected_files_json")),
                status = runCatching {
                    StepStatus.valueOf(row.getString("status") ?: "PENDING")
                }.getOrDefault(StepStatus.PENDING),
                attempts = row.getInt("attempts") ?: 0,
                maxAttempts = row.getInt("max_attempts") ?: 2,
                resultSummary = row.getString("result_summary"),
                checkpointTag = row.getString("checkpoint_tag"),
                startedAt = row.getLong("started_at")?.let { Instant.ofEpochMilli(it) },
                completedAt = row.getLong("completed_at")?.let { Instant.ofEpochMilli(it) },
            )
        }

        // Fetch failure history
        val failures = db.driver.query(
            "SELECT * FROM task_failures WHERE task_id = ? ORDER BY created_at ASC",
            listOf(taskId)
        ) { row ->
            TaskFailureRecord(
                failureId = row.getString("failure_id") ?: "",
                taskId = row.getString("task_id") ?: "",
                stepId = row.getString("step_id"),
                classification = row.getString("classification") ?: "",
                errorMessage = row.getString("error_message") ?: "",
                errorSnippet = row.getString("error_snippet"),
                mutatedFiles = jsonArrayToList(row.getString("mutated_files_json")),
                matchedSolutionId = row.getString("matched_solution_id"),
                timestamp = Instant.ofEpochMilli(row.getLong("created_at") ?: System.currentTimeMillis()),
            )
        }

        // Fetch active recovery plan (most recent)
        val recoveryPlan = db.driver.query(
            "SELECT * FROM recovery_plans WHERE task_id = ? ORDER BY created_at DESC LIMIT 1",
            listOf(taskId)
        ) { row ->
            RecoveryPlan(
                recoveryId = row.getString("recovery_id") ?: "",
                taskId = row.getString("task_id") ?: "",
                failureRecordId = row.getString("failure_record_id") ?: "",
                strategy = runCatching {
                    RecoveryStrategy.valueOf(row.getString("strategy") ?: "RETRY_STEP_DIRECT")
                }.getOrDefault(RecoveryStrategy.RETRY_STEP_DIRECT),
                rationale = row.getString("rationale") ?: "",
                filesToRollback = jsonArrayToList(row.getString("files_to_rollback_json")),
                forwardFixInstructions = row.getString("forward_fix_instructions"),
                targetStepIndex = row.getInt("target_step_index") ?: 0,
                approvedByUser = (row.getInt("approved_by_user") ?: 0) == 1,
                createdAt = Instant.ofEpochMilli(row.getLong("created_at") ?: System.currentTimeMillis()),
            )
        }.firstOrNull()

        val plan = ExecutionPlan(
            title = "Plan for: ${taskRow.objective.take(40)}",
            steps = steps,
            currentStepIndex = steps.indexOfFirst { it.status == StepStatus.RUNNING || it.status == StepStatus.PENDING }.let {
                if (it == -1) if (steps.isEmpty()) 0 else steps.size - 1 else it
            },
            createdAt = taskRow.createdAt,
            updatedAt = taskRow.updatedAt
        )

        return CanonicalTask(
            taskId = taskRow.taskId,
            projectId = taskRow.projectId,
            projectSlug = taskRow.projectSlug,
            objective = taskRow.objective,
            constraints = taskRow.constraints,
            acceptanceCriteria = taskRow.acceptanceCriteria,
            plan = plan,
            initialWorkspaceSha = taskRow.initialWorkspaceSha,
            currentWorkspaceSha = taskRow.currentWorkspaceSha,
            failureHistory = failures,
            activeRecoveryPlan = recoveryPlan,
            outcome = taskRow.outcome,
            createdAt = taskRow.createdAt,
            updatedAt = taskRow.updatedAt,
        )
    }

    fun getTasksByProject(projectId: String, limit: Int = 50): List<CanonicalTask> {
        val taskIds = db.driver.query(
            "SELECT task_id FROM canonical_tasks WHERE project_id = ? ORDER BY updated_at DESC LIMIT ?",
            listOf(projectId, limit)
        ) { row ->
            row.getString("task_id") ?: ""
        }.filter { it.isNotBlank() }

        return taskIds.mapNotNull { getTask(it) }
    }

    fun recordFailure(taskId: String, failure: TaskFailureRecord) {
        val sql = """
            INSERT OR REPLACE INTO task_failures (
                failure_id, task_id, step_id, classification,
                error_message, error_snippet, mutated_files_json,
                matched_solution_id, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        db.driver.execute(
            sql,
            listOf(
                failure.failureId,
                taskId,
                failure.stepId,
                failure.classification,
                failure.errorMessage,
                failure.errorSnippet,
                listToJsonArray(failure.mutatedFiles),
                failure.matchedSolutionId,
                failure.timestamp.toEpochMilli()
            )
        )
        db.driver.execute(
            "UPDATE canonical_tasks SET updated_at = ? WHERE task_id = ?",
            listOf(System.currentTimeMillis(), taskId)
        )
    }

    fun setRecoveryPlan(taskId: String, plan: RecoveryPlan) {
        val sql = """
            INSERT OR REPLACE INTO recovery_plans (
                recovery_id, task_id, failure_record_id, strategy,
                rationale, files_to_rollback_json, forward_fix_instructions,
                target_step_index, approved_by_user, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        db.driver.execute(
            sql,
            listOf(
                plan.recoveryId,
                taskId,
                plan.failureRecordId,
                plan.strategy.name,
                plan.rationale,
                listToJsonArray(plan.filesToRollback),
                plan.forwardFixInstructions,
                plan.targetStepIndex,
                if (plan.approvedByUser) 1 else 0,
                plan.createdAt.toEpochMilli()
            )
        )
        db.driver.execute(
            "UPDATE canonical_tasks SET updated_at = ? WHERE task_id = ?",
            listOf(System.currentTimeMillis(), taskId)
        )
    }

    fun completeTask(taskId: String, outcome: TaskOutcome) {
        val sql = "UPDATE canonical_tasks SET outcome_json = ?, updated_at = ? WHERE task_id = ?"
        db.driver.execute(
            sql,
            listOf(outcomeToJson(outcome), System.currentTimeMillis(), taskId)
        )
    }

    fun deleteTask(taskId: String) {
        db.driver.transaction {
            db.driver.execute("DELETE FROM recovery_plans WHERE task_id = ?", listOf(taskId))
            db.driver.execute("DELETE FROM task_failures WHERE task_id = ?", listOf(taskId))
            db.driver.execute("DELETE FROM execution_steps WHERE task_id = ?", listOf(taskId))
            db.driver.execute("DELETE FROM canonical_tasks WHERE task_id = ?", listOf(taskId))
        }
    }

    private data class CanonicalTaskRow(
        val taskId: String,
        val projectId: String,
        val projectSlug: String,
        val objective: String,
        val constraints: List<String>,
        val acceptanceCriteria: List<String>,
        val initialWorkspaceSha: String?,
        val currentWorkspaceSha: String?,
        val outcome: TaskOutcome?,
        val createdAt: Instant,
        val updatedAt: Instant,
    )
}
