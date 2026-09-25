package com.jarves.mh.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

class ContextMemoryRepository(private val db: BrainDatabase) {

    private fun mapRow(row: SqlRow): MemoryEntry {
        val tagsStr = row.getString("tags") ?: ""
        val tags = if (tagsStr.isNotBlank()) tagsStr.split(",").map { it.trim() } else emptyList()

        return MemoryEntry(
            id = row.getString("id") ?: "",
            projectId = row.getString("project_id") ?: "",
            sessionId = row.getString("session_id"),
            scope = runCatching { MemoryScope.valueOf(row.getString("scope") ?: "") }.getOrDefault(MemoryScope.PROJECT),
            type = runCatching { MemoryType.valueOf(row.getString("type") ?: "") }.getOrDefault(MemoryType.PROJECT),
            key = row.getString("key") ?: "",
            value = row.getString("value") ?: "",
            summary = row.getString("summary"),
            importance = row.getDouble("importance")?.toFloat() ?: 0.5f,
            confidence = row.getDouble("confidence")?.toFloat() ?: 0.8f,
            source = runCatching { MemorySource.valueOf(row.getString("source") ?: "") }.getOrDefault(MemorySource.AUTO),
            sourceReference = row.getString("source_reference"),
            status = runCatching { MemoryStatus.valueOf(row.getString("status") ?: "") }.getOrDefault(MemoryStatus.ACTIVE),
            version = row.getInt("version") ?: 1,
            supersededBy = row.getString("superseded_by"),
            tags = tags,
            createdAt = Instant.ofEpochMilli(row.getLong("created_at") ?: System.currentTimeMillis()),
            updatedAt = Instant.ofEpochMilli(row.getLong("updated_at") ?: System.currentTimeMillis()),
            lastAccessedAt = Instant.ofEpochMilli(row.getLong("last_accessed_at") ?: System.currentTimeMillis()),
        )
    }

    fun getById(id: String): MemoryEntry? {
        val results = db.driver.query(
            "SELECT * FROM memory_entries WHERE id = ?",
            listOf(id),
            ::mapRow
        )
        return results.firstOrNull()
    }

    fun getByProject(
        projectId: String,
        status: MemoryStatus? = MemoryStatus.ACTIVE,
        limit: Int = 100
    ): List<MemoryEntry> {
        val sql = if (status != null) {
            "SELECT * FROM memory_entries WHERE project_id = ? AND status = ? ORDER BY importance DESC, updated_at DESC LIMIT ?"
        } else {
            "SELECT * FROM memory_entries WHERE project_id = ? ORDER BY updated_at DESC LIMIT ?"
        }
        val args = if (status != null) listOf(projectId, status.name, limit) else listOf(projectId, limit)
        return db.driver.query(sql, args, ::mapRow)
    }

    fun findExact(
        projectId: String,
        key: String,
        status: MemoryStatus? = MemoryStatus.ACTIVE
    ): MemoryEntry? {
        val normKey = MemoryConflictResolver.normalizeKey(key)
        val candidates = getByProject(projectId, status, limit = 200)
        return candidates.firstOrNull { MemoryConflictResolver.normalizeKey(it.key) == normKey }
    }

    fun filter(
        projectId: String,
        scope: MemoryScope? = null,
        type: MemoryType? = null,
        status: MemoryStatus? = MemoryStatus.ACTIVE,
        minImportance: Float = 0f,
        minConfidence: Float = 0f,
        limit: Int = 50
    ): List<MemoryEntry> {
        val conditions = mutableListOf("project_id = ?")
        val args = mutableListOf<Any?>(projectId)

        if (scope != null) {
            conditions.add("scope = ?")
            args.add(scope.name)
        }
        if (type != null) {
            conditions.add("type = ?")
            args.add(type.name)
        }
        if (status != null) {
            conditions.add("status = ?")
            args.add(status.name)
        }
        if (minImportance > 0f) {
            conditions.add("importance >= ?")
            args.add(minImportance)
        }
        if (minConfidence > 0f) {
            conditions.add("confidence >= ?")
            args.add(minConfidence)
        }

        val sql = "SELECT * FROM memory_entries WHERE ${conditions.joinToString(" AND ")} ORDER BY importance DESC, updated_at DESC LIMIT ?"
        args.add(limit)
        return db.driver.query(sql, args, ::mapRow)
    }

    fun search(projectId: String, query: String, limit: Int = 20): List<MemoryEntry> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return getByProject(projectId, MemoryStatus.ACTIVE, limit)
        }

        // Try FTS5 first if supported
        if (db.isFts5Supported) {
            try {
                // Escape query for FTS5
                val sanitizedFts = cleanQuery.replace(Regex("[^a-zA-Z0-9_]"), " ").trim()
                if (sanitizedFts.isNotBlank()) {
                    val ftsTerms = sanitizedFts.split(Regex("\\s+")).joinToString(" OR ") { "$it*" }
                    val sql = """
                        SELECT m.* FROM memory_entries m
                        INNER JOIN memory_fts f ON m.id = f.id
                        WHERE m.project_id = ? AND m.status = 'ACTIVE' AND memory_fts MATCH ?
                        ORDER BY m.importance DESC, m.updated_at DESC LIMIT ?
                    """.trimIndent()
                    val ftsResults = db.driver.query(sql, listOf(projectId, ftsTerms, limit), ::mapRow)
                    if (ftsResults.isNotEmpty()) return ftsResults
                }
            } catch (_: Throwable) {
                // Fallback to LIKE query below
            }
        }

        // Fallback: LIKE matching across key, value, summary
        val likePattern = "%$cleanQuery%"
        val fallbackSql = """
            SELECT * FROM memory_entries
            WHERE project_id = ? AND status = 'ACTIVE'
              AND (key LIKE ? OR value LIKE ? OR summary LIKE ? OR tags LIKE ?)
            ORDER BY importance DESC, updated_at DESC LIMIT ?
        """.trimIndent()
        return db.driver.query(
            fallbackSql,
            listOf(projectId, likePattern, likePattern, likePattern, likePattern, limit),
            ::mapRow
        )
    }

    fun insert(entry: MemoryEntry): MemoryEntry {
        val sql = """
            INSERT INTO memory_entries (
                id, project_id, session_id, scope, type, key, value, summary,
                importance, confidence, source, source_reference, status,
                version, superseded_by, tags, created_at, updated_at, last_accessed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        db.driver.execute(
            sql,
            listOf(
                entry.id,
                entry.projectId,
                entry.sessionId,
                entry.scope.name,
                entry.type.name,
                entry.key,
                entry.value,
                entry.summary,
                entry.importance,
                entry.confidence,
                entry.source.name,
                entry.sourceReference,
                entry.status.name,
                entry.version,
                entry.supersededBy,
                entry.tags.joinToString(","),
                entry.createdAt.toEpochMilli(),
                entry.updatedAt.toEpochMilli(),
                entry.lastAccessedAt.toEpochMilli()
            )
        )
        db.syncFtsInsert(entry)
        return entry
    }

    fun update(entry: MemoryEntry): MemoryEntry {
        val sql = """
            UPDATE memory_entries SET
                session_id = ?, scope = ?, type = ?, key = ?, value = ?, summary = ?,
                importance = ?, confidence = ?, source = ?, source_reference = ?,
                status = ?, version = ?, superseded_by = ?, tags = ?,
                updated_at = ?, last_accessed_at = ?
            WHERE id = ?
        """.trimIndent()

        db.driver.execute(
            sql,
            listOf(
                entry.sessionId,
                entry.scope.name,
                entry.type.name,
                entry.key,
                entry.value,
                entry.summary,
                entry.importance,
                entry.confidence,
                entry.source.name,
                entry.sourceReference,
                entry.status.name,
                entry.version,
                entry.supersededBy,
                entry.tags.joinToString(","),
                entry.updatedAt.toEpochMilli(),
                entry.lastAccessedAt.toEpochMilli(),
                entry.id
            )
        )
        db.syncFtsUpdate(entry)
        return entry
    }

    fun supersede(oldId: String, newEntry: MemoryEntry): MemoryEntry {
        db.driver.transaction {
            val now = Instant.now()
            db.driver.execute(
                "UPDATE memory_entries SET status = ?, superseded_by = ?, updated_at = ? WHERE id = ?",
                listOf(MemoryStatus.SUPERSEDED.name, newEntry.id, now.toEpochMilli(), oldId)
            )
            insert(newEntry)
        }
        return newEntry
    }

    fun save(incoming: MemoryEntry): MemoryEntry {
        val existing = getByProject(incoming.projectId, status = null, limit = 500)
        return when (val resolution = MemoryConflictResolver.resolve(incoming, existing)) {
            is MemoryConflictResolver.Resolution.InsertNew -> insert(resolution.newEntry)
            is MemoryConflictResolver.Resolution.Deduplicate -> update(resolution.updated)
            is MemoryConflictResolver.Resolution.Supersede -> supersede(resolution.oldEntryId, resolution.newEntry)
            is MemoryConflictResolver.Resolution.RejectedLowerTrust -> resolution.existing
            is MemoryConflictResolver.Resolution.NoChange -> resolution.existing
        }
    }

    fun delete(id: String) {
        db.driver.execute("DELETE FROM memory_entries WHERE id = ?", listOf(id))
        db.syncFtsDelete(id)
    }

    fun clearProject(projectId: String) {
        val entries = getByProject(projectId, status = null, limit = 1000)
        db.driver.transaction {
            db.driver.execute("DELETE FROM memory_entries WHERE project_id = ?", listOf(projectId))
            entries.forEach { db.syncFtsDelete(it.id) }
        }
    }

    fun clearAuto(projectId: String) {
        val autoEntries = getByProject(projectId, status = null, limit = 1000)
            .filter { it.source.isAuto }
        db.driver.transaction {
            autoEntries.forEach { delete(it.id) }
        }
    }

    fun migrateFromJson(jsonFile: File, projectId: String) {
        if (!jsonFile.exists() || jsonFile.length() == 0L) return
        val currentCount = getByProject(projectId, status = null, limit = 1).size
        if (currentCount > 0) return // Already migrated or has records

        runCatching {
            val json = JSONObject(jsonFile.readText())
            val entriesArr = json.optJSONArray("entries") ?: JSONArray()
            db.driver.transaction {
                for (i in 0 until entriesArr.length()) {
                    val obj = entriesArr.getJSONObject(i)
                    val id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
                    val key = obj.optString("key")
                    val value = obj.optString("value")
                    val sourceStr = obj.optString("source", MemorySource.AUTO.name)
                    val source = runCatching { MemorySource.valueOf(sourceStr) }.getOrDefault(MemorySource.AUTO)
                    val createdAt = runCatching { Instant.parse(obj.optString("createdAt")) }.getOrElse { Instant.now() }
                    val updatedAt = runCatching { Instant.parse(obj.optString("updatedAt")) }.getOrElse { Instant.now() }

                    if (key.isNotBlank() && value.isNotBlank()) {
                        insert(
                            MemoryEntry(
                                id = id,
                                projectId = projectId,
                                key = key,
                                value = value,
                                source = source,
                                createdAt = createdAt,
                                updatedAt = updatedAt,
                                lastAccessedAt = updatedAt
                            )
                        )
                    }
                }
            }
        }
    }
}
