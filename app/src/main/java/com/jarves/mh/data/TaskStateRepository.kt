package com.jarves.mh.data

import org.json.JSONArray
import java.time.Instant

class TaskStateRepository(private val db: BrainDatabase) {

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

    private fun mapRow(row: SqlRow): TaskCheckpoint {
        return TaskCheckpoint(
            id = row.getString("id") ?: "",
            projectId = row.getString("project_id") ?: "",
            sessionId = row.getString("session_id"),
            goal = row.getString("goal") ?: "",
            plan = jsonArrayToList(row.getString("plan_json")),
            currentStep = row.getString("current_step"),
            completedSteps = jsonArrayToList(row.getString("completed_steps_json")),
            pendingSteps = jsonArrayToList(row.getString("pending_steps_json")),
            blockers = jsonArrayToList(row.getString("blockers_json")),
            recentActions = jsonArrayToList(row.getString("recent_actions_json")),
            currentFiles = jsonArrayToList(row.getString("current_files_json")),
            lastError = row.getString("last_error"),
            lastSuccess = row.getString("last_success"),
            nextAction = row.getString("next_action"),
            updatedAt = Instant.ofEpochMilli(row.getLong("updated_at") ?: System.currentTimeMillis()),
        )
    }

    fun saveCheckpoint(checkpoint: TaskCheckpoint) {
        val sql = """
            INSERT OR REPLACE INTO task_checkpoints (
                id, project_id, session_id, goal, plan_json, current_step,
                completed_steps_json, pending_steps_json, blockers_json,
                recent_actions_json, current_files_json, last_error, last_success,
                next_action, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        db.driver.execute(
            sql,
            listOf(
                checkpoint.id,
                checkpoint.projectId,
                checkpoint.sessionId,
                checkpoint.goal,
                listToJsonArray(checkpoint.plan),
                checkpoint.currentStep,
                listToJsonArray(checkpoint.completedSteps),
                listToJsonArray(checkpoint.pendingSteps),
                listToJsonArray(checkpoint.blockers),
                listToJsonArray(checkpoint.recentActions),
                listToJsonArray(checkpoint.currentFiles),
                checkpoint.lastError,
                checkpoint.lastSuccess,
                checkpoint.nextAction,
                checkpoint.updatedAt.toEpochMilli()
            )
        )
    }

    fun getActiveCheckpoint(projectId: String): TaskCheckpoint? {
        val sql = "SELECT * FROM task_checkpoints WHERE project_id = ? ORDER BY updated_at DESC LIMIT 1"
        return db.driver.query(sql, listOf(projectId), ::mapRow).firstOrNull()
    }

    fun clearCheckpoint(projectId: String) {
        db.driver.execute("DELETE FROM task_checkpoints WHERE project_id = ?", listOf(projectId))
    }

    fun recordError(projectId: String, error: String) {
        val current = getActiveCheckpoint(projectId) ?: TaskCheckpoint(projectId = projectId)
        val updated = current.copy(
            lastError = error,
            updatedAt = Instant.now()
        )
        saveCheckpoint(updated)
    }

    fun recordSuccess(projectId: String, success: String) {
        val current = getActiveCheckpoint(projectId) ?: TaskCheckpoint(projectId = projectId)
        val updated = current.copy(
            lastSuccess = success,
            lastError = null,
            updatedAt = Instant.now()
        )
        saveCheckpoint(updated)
    }

    fun advanceStep(projectId: String, completedStep: String?, nextStep: String?) {
        val current = getActiveCheckpoint(projectId) ?: return
        val newCompleted = if (completedStep != null) {
            (current.completedSteps + completedStep).distinct()
        } else {
            current.completedSteps
        }
        val newPending = if (completedStep != null) {
            current.pendingSteps.filterNot { it.equals(completedStep, ignoreCase = true) }
        } else {
            current.pendingSteps
        }

        val updated = current.copy(
            completedSteps = newCompleted,
            pendingSteps = newPending,
            currentStep = nextStep ?: newPending.firstOrNull(),
            nextAction = nextStep,
            updatedAt = Instant.now()
        )
        saveCheckpoint(updated)
    }
}
