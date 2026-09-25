package com.jarves.mh.runtime.task

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class DuplicateExecutionException(message: String) : IllegalStateException(message)

/**
 * Concurrency-safe execution lock ensuring that a taskId or project
 * never has two concurrent active agent processes.
 */
class TaskExecutionLock {

    private val mutex = Mutex()
    private val activeTasks = ConcurrentHashMap.newKeySet<String>()
    private val activeProjects = ConcurrentHashMap.newKeySet<String>()

    fun isTaskActive(taskId: String): Boolean = activeTasks.contains(taskId)

    fun isProjectActive(projectId: String): Boolean = activeProjects.contains(projectId)

    suspend fun <T> withExecutionLock(
        taskId: String,
        projectId: String,
        exclusiveProject: Boolean = true,
        block: suspend () -> T
    ): T {
        mutex.withLock {
            if (activeTasks.contains(taskId)) {
                throw DuplicateExecutionException("Task $taskId is already actively executing")
            }
            if (exclusiveProject && activeProjects.contains(projectId)) {
                throw DuplicateExecutionException("Project $projectId already has an active task running")
            }
            activeTasks.add(taskId)
            if (exclusiveProject) {
                activeProjects.add(projectId)
            }
        }

        try {
            return block()
        } finally {
            mutex.withLock {
                activeTasks.remove(taskId)
                if (exclusiveProject) {
                    activeProjects.remove(projectId)
                }
            }
        }
    }

    suspend fun release(taskId: String, projectId: String) {
        mutex.withLock {
            activeTasks.remove(taskId)
            activeProjects.remove(projectId)
        }
    }

    fun getActiveTaskCount(): Int = activeTasks.size

    fun getActiveTaskIds(): List<String> = activeTasks.toList()
}
