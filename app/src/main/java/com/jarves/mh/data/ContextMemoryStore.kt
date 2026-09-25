package com.jarves.mh.data

import android.content.Context
import java.io.File
import java.time.Instant

class ContextMemoryStore(private val baseDir: File) {

    constructor(context: Context) : this(context.filesDir)

    private val memoryDir: File get() = File(baseDir, "memory")
    private val dbFile: File get() = File(memoryDir, "project_brain.db")

    private val db: BrainDatabase by lazy {
        memoryDir.mkdirs()
        val driver = BrainDatabaseDriverFactory.createDriver(dbFile)
        BrainDatabase(driver)
    }

    val repository: ContextMemoryRepository by lazy { ContextMemoryRepository(db) }
    val taskRepository: TaskStateRepository by lazy { TaskStateRepository(db) }
    val retriever: MemoryRetriever by lazy { MemoryRetriever(repository, taskRepository) }

    companion object {
        const val MAX_ENTRIES_PER_PROJECT = 50
        const val MAX_VALUE_LENGTH = 500
    }

    private fun sanitizeProjectId(projectId: String): String {
        val sanitizedId = projectId.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        require(sanitizedId.isNotBlank() && !sanitizedId.contains("..")) {
            "Invalid projectId: $projectId"
        }
        val file = File(memoryDir, "$sanitizedId.json")
        require(file.canonicalFile.toPath().startsWith(memoryDir.canonicalFile.toPath())) {
            "Project memory file escapes memory directory: ${file.path}"
        }
        return sanitizedId
    }

    @Synchronized
    fun load(projectId: String): ContextMemory {
        val cleanId = sanitizeProjectId(projectId)
        return runCatching {
            // Check legacy JSON file for automatic migration
            val legacyFile = File(memoryDir, "$cleanId.json")
            if (legacyFile.exists()) {
                repository.migrateFromJson(legacyFile, cleanId)
            }

            val entries = repository.getByProject(cleanId, MemoryStatus.ACTIVE, limit = MAX_ENTRIES_PER_PROJECT)
            val mostRecent = entries.maxOfOrNull { it.updatedAt } ?: Instant.now()
            ContextMemory(
                projectId = cleanId,
                entries = entries,
                updatedAt = mostRecent
            )
        }.getOrElse {
            ContextMemory(cleanId)
        }
    }

    @Synchronized
    fun save(memory: ContextMemory) {
        val cleanId = sanitizeProjectId(memory.projectId)
        runCatching {
            memory.entries.forEach { entry ->
                repository.save(entry.copy(projectId = cleanId))
            }
        }
    }

    @Synchronized
    fun upsert(projectId: String, key: String, value: String, source: MemorySource): ContextMemory {
        val cleanId = sanitizeProjectId(projectId)
        val cleanKey = key.trim()
        val cleanValue = value.trim().take(MAX_VALUE_LENGTH)
        if (cleanKey.isBlank() || cleanValue.isBlank()) return load(cleanId)

        runCatching {
            val now = Instant.now()
            val existing = repository.findExact(cleanId, cleanKey, MemoryStatus.ACTIVE)

            if (existing != null) {
                // If user wrote earlier, an auto update doesn't overwrite with lower confidence
                val effectiveSource = if (existing.source.isUser && source.isAuto) {
                    MemorySource.USER
                } else {
                    source
                }
                repository.update(
                    existing.copy(
                        key = existing.key,
                        value = cleanValue,
                        source = effectiveSource,
                        updatedAt = now,
                        lastAccessedAt = now
                    )
                )
            } else {
                repository.insert(
                    MemoryEntry(
                        projectId = cleanId,
                        key = cleanKey,
                        value = cleanValue,
                        source = source,
                        importance = if (source.isUser) 0.9f else 0.7f,
                        confidence = if (source.isUser) 0.95f else 0.8f,
                        createdAt = now,
                        updatedAt = now,
                        lastAccessedAt = now
                    )
                )
            }

            // Eviction policy: max 50 entries, oldest AUTO entries evicted first
            val allActive = repository.getByProject(cleanId, MemoryStatus.ACTIVE, limit = 100)
            if (allActive.size > MAX_ENTRIES_PER_PROJECT) {
                val oldestAuto = allActive
                    .filter { it.source.isAuto }
                    .minByOrNull { it.updatedAt }
                if (oldestAuto != null) {
                    repository.delete(oldestAuto.id)
                } else {
                    val oldestUser = allActive.minByOrNull { it.updatedAt }
                    if (oldestUser != null) {
                        repository.delete(oldestUser.id)
                    }
                }
            }
        }

        return load(cleanId)
    }

    @Synchronized
    fun delete(projectId: String, entryId: String): ContextMemory {
        val cleanId = sanitizeProjectId(projectId)
        runCatching {
            repository.delete(entryId)
        }
        return load(cleanId)
    }

    @Synchronized
    fun clear(projectId: String): ContextMemory {
        val cleanId = sanitizeProjectId(projectId)
        runCatching {
            repository.clearProject(cleanId)
            taskRepository.clearCheckpoint(cleanId)
            val legacyFile = File(memoryDir, "$cleanId.json")
            if (legacyFile.exists()) legacyFile.delete()
        }
        return ContextMemory(cleanId)
    }

    @Synchronized
    fun clearAuto(projectId: String): ContextMemory {
        val cleanId = sanitizeProjectId(projectId)
        runCatching {
            repository.clearAuto(cleanId)
        }
        return load(cleanId)
    }
}
