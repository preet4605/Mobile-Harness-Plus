package com.jarves.mh.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

class ContextMemoryStore(private val baseDir: File) {

    constructor(context: Context) : this(context.filesDir)

    private val memoryDir: File get() = File(baseDir, "memory")

    companion object {
        const val MAX_ENTRIES_PER_PROJECT = 50
        const val MAX_VALUE_LENGTH = 500
    }

    private fun fileForProject(projectId: String): File {
        return File(memoryDir, "$projectId.json")
    }

    @Synchronized
    fun load(projectId: String): ContextMemory {
        val file = fileForProject(projectId)
        if (!file.exists() || file.length() == 0L) {
            return ContextMemory(projectId)
        }
        return runCatching {
            val json = JSONObject(file.readText())
            val pId = json.optString("projectId", projectId).ifBlank { projectId }
            val updatedAtStr = json.optString("updatedAt")
            val updatedAt = runCatching { Instant.parse(updatedAtStr) }.getOrElse { Instant.now() }
            val entriesArr = json.optJSONArray("entries") ?: JSONArray()
            val entries = mutableListOf<MemoryEntry>()
            for (i in 0 until entriesArr.length()) {
                val obj = entriesArr.getJSONObject(i)
                val id = obj.optString("id")
                val key = obj.optString("key")
                val value = obj.optString("value")
                val sourceStr = obj.optString("source", MemorySource.AUTO.name)
                val source = runCatching { MemorySource.valueOf(sourceStr) }.getOrDefault(MemorySource.AUTO)
                val createdAt = runCatching { Instant.parse(obj.optString("createdAt")) }.getOrElse { Instant.now() }
                val entryUpdatedAt = runCatching { Instant.parse(obj.optString("updatedAt")) }.getOrElse { Instant.now() }
                if (key.isNotBlank() && value.isNotBlank()) {
                    entries.add(
                        MemoryEntry(
                            id = id.ifBlank { java.util.UUID.randomUUID().toString() },
                            key = key,
                            value = value,
                            source = source,
                            createdAt = createdAt,
                            updatedAt = entryUpdatedAt,
                        )
                    )
                }
            }
            ContextMemory(
                projectId = pId,
                entries = entries,
                updatedAt = updatedAt,
            )
        }.getOrElse {
            ContextMemory(projectId)
        }
    }

    @Synchronized
    fun save(memory: ContextMemory) {
        memoryDir.mkdirs()
        val destination = fileForProject(memory.projectId)
        val temporary = File(memoryDir, ".${memory.projectId}.json.tmp")

        val root = JSONObject().apply {
            put("projectId", memory.projectId)
            put("updatedAt", memory.updatedAt.toString())
            val entriesArr = JSONArray()
            memory.entries.forEach { entry ->
                entriesArr.put(
                    JSONObject().apply {
                        put("id", entry.id)
                        put("key", entry.key)
                        put("value", entry.value)
                        put("source", entry.source.name)
                        put("createdAt", entry.createdAt.toString())
                        put("updatedAt", entry.updatedAt.toString())
                    }
                )
            }
            put("entries", entriesArr)
        }

        temporary.writeText(root.toString())
        if (!temporary.renameTo(destination)) {
            // Fallback for filesystems where atomic rename across files requires delete
            destination.delete()
            temporary.renameTo(destination)
        }
    }

    @Synchronized
    fun upsert(projectId: String, key: String, value: String, source: MemorySource): ContextMemory {
        val cleanKey = key.trim()
        val cleanValue = value.trim().take(MAX_VALUE_LENGTH)
        if (cleanKey.isBlank() || cleanValue.isBlank()) return load(projectId)

        val current = load(projectId)
        val now = Instant.now()
        val existingIndex = current.entries.indexOfFirst { it.key.equals(cleanKey, ignoreCase = true) }

        val updatedEntries = current.entries.toMutableList()
        if (existingIndex >= 0) {
            val existing = updatedEntries[existingIndex]
            val effectiveSource = if (existing.source == MemorySource.USER && source == MemorySource.AUTO) {
                MemorySource.USER
            } else {
                source
            }
            updatedEntries[existingIndex] = existing.copy(
                key = existing.key,
                value = cleanValue,
                source = effectiveSource,
                updatedAt = now,
            )
        } else {
            val newEntry = MemoryEntry(
                key = cleanKey,
                value = cleanValue,
                source = source,
                createdAt = now,
                updatedAt = now,
            )
            updatedEntries.add(newEntry)
        }

        // Eviction policy: max 50 entries, oldest AUTO entries evicted first
        while (updatedEntries.size > MAX_ENTRIES_PER_PROJECT) {
            val oldestAuto = updatedEntries
                .filter { it.source == MemorySource.AUTO }
                .minByOrNull { it.updatedAt }
            if (oldestAuto != null) {
                updatedEntries.remove(oldestAuto)
            } else {
                val oldestUser = updatedEntries.minByOrNull { it.updatedAt }
                if (oldestUser != null) {
                    updatedEntries.remove(oldestUser)
                } else {
                    break
                }
            }
        }

        val updatedMemory = ContextMemory(
            projectId = projectId,
            entries = updatedEntries,
            updatedAt = now,
        )
        save(updatedMemory)
        return updatedMemory
    }

    @Synchronized
    fun delete(projectId: String, entryId: String): ContextMemory {
        val current = load(projectId)
        val filtered = current.entries.filterNot { it.id == entryId }
        val updated = current.copy(entries = filtered, updatedAt = Instant.now())
        save(updated)
        return updated
    }

    @Synchronized
    fun clear(projectId: String): ContextMemory {
        val file = fileForProject(projectId)
        if (file.exists()) file.delete()
        return ContextMemory(projectId)
    }

    @Synchronized
    fun clearAuto(projectId: String): ContextMemory {
        val current = load(projectId)
        val userOnly = current.entries.filter { it.source == MemorySource.USER }
        val updated = current.copy(entries = userOnly, updatedAt = Instant.now())
        save(updated)
        return updated
    }
}
