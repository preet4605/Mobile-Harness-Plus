package com.jarves.mh.data

import com.jarves.mh.model.brain.BrainKnowledgeEntry
import com.jarves.mh.model.brain.BrainKnowledgeType
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * In-memory LRU cache bounded to a maximum number of entries per project.
 * Enforces project isolation and prevents unbounded memory growth.
 */
class BoundedProjectKnowledgeCache(val maxEntriesPerProject: Int = 100) {
    private val projectCaches = mutableMapOf<String, LinkedHashMap<String, BrainKnowledgeEntry>>()
    private val lock = Any()

    fun get(projectId: String, id: String): BrainKnowledgeEntry? = synchronized(lock) {
        projectCaches[projectId]?.get(id)
    }

    fun getById(id: String): BrainKnowledgeEntry? = synchronized(lock) {
        for (map in projectCaches.values) {
            val entry = map[id]
            if (entry != null) return entry
        }
        null
    }

    fun put(entry: BrainKnowledgeEntry) = synchronized(lock) {
        val map = projectCaches.getOrPut(entry.projectId) {
            object : LinkedHashMap<String, BrainKnowledgeEntry>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BrainKnowledgeEntry>?): Boolean {
                    return size > maxEntriesPerProject
                }
            }
        }
        map[entry.id] = entry
    }

    fun remove(projectId: String, id: String) = synchronized(lock) {
        projectCaches[projectId]?.remove(id)
    }

    fun clearProject(projectId: String) = synchronized(lock) {
        projectCaches.remove(projectId)
    }

    fun clear() = synchronized(lock) {
        projectCaches.clear()
    }

    fun size(projectId: String): Int = synchronized(lock) {
        projectCaches[projectId]?.size ?: 0
    }
}

/**
 * Strongly typed repository for Project Brain knowledge entries.
 * Backed by BrainDatabase (SQLite), with FTS5 lexical indexing, deterministic ranking,
 * LRU in-memory caching, and bounded retrieval pipelines.
 */
open class BrainKnowledgeRepository(
    private val db: BrainDatabase,
    val cache: BoundedProjectKnowledgeCache = BoundedProjectKnowledgeCache(100)
) {

    private val accessTrackingExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "brain-knowledge-access-tracker").apply { isDaemon = true }
    }

    private companion object {
        // Keep both FTS and LIKE query expressions comfortably below SQLite expression-depth limits.
        const val MAX_SEARCH_TERMS = 24
        private val SEARCH_STOPWORDS = setOf(
            "the", "and", "for", "with", "this", "that", "from", "into", "then", "than", "are", "was",
            "not", "but", "you", "your", "all", "any", "can", "use", "its", "has", "have", "should",
            "must", "will", "also", "only", "each", "when", "which", "what", "how", "does", "do",
        )
        const val MAX_SEARCH_TERM_LENGTH = 64
        const val MAX_SEARCH_QUERY_CHARS = 1536
        val SEARCH_TOKEN_SPLIT_REGEX = Regex("[^\\p{L}\\p{N}_]+")
    }

    /**
     * Produces one deterministic, bounded term list for all lexical retrieval paths.
     * Bounding happens before FTS/SQL expression construction.
     */
    private fun prepareSearchTerms(query: String): List<String> {
        if (query.isBlank()) return emptyList()

        val seen = LinkedHashSet<String>()
        val terms = query
            .take(MAX_SEARCH_QUERY_CHARS)
            .split(SEARCH_TOKEN_SPLIT_REGEX)
            .asSequence()
            .map { it.take(MAX_SEARCH_TERM_LENGTH) }
            .filter { it.length >= 2 }
            .filter { seen.add(it.lowercase(Locale.ROOT)) }
            .toList()
        // Drop filler words so an OR-query is not dominated by terms that match everything;
        // fall back to the unfiltered terms when nothing meaningful remains.
        val meaningful = terms.filter { it.lowercase(Locale.ROOT) !in SEARCH_STOPWORDS }
        return (meaningful.ifEmpty { terms }).take(MAX_SEARCH_TERMS)
    }

    private fun mapRow(row: SqlRow): BrainKnowledgeEntry {
        val tagsStr = row.getString("tags") ?: ""
        val tags = if (tagsStr.isNotBlank()) tagsStr.split(",").map { it.trim() } else emptyList()

        val scope = runCatching { MemoryScope.valueOf(row.getString("scope") ?: "") }.getOrDefault(MemoryScope.PROJECT)
        val legacyType = runCatching {
            val typeStr = row.getString("type") ?: ""
            if (typeStr.isNotBlank()) MemoryType.valueOf(typeStr) else null
        }.getOrNull()

        val knowledgeType = runCatching {
            BrainKnowledgeType.valueOf(row.getString("knowledge_type") ?: "")
        }.getOrElse {
            when (legacyType) {
                MemoryType.DECISION -> BrainKnowledgeType.DECISION
                MemoryType.TASK -> BrainKnowledgeType.TASK
                else -> BrainKnowledgeType.FACT
            }
        }

        return BrainKnowledgeEntry(
            id = row.getString("id") ?: "",
            projectId = row.getString("project_id") ?: "",
            sessionId = row.getString("session_id"),
            taskId = row.getString("task_id"),
            scope = scope,
            knowledgeType = knowledgeType,
            key = row.getString("key") ?: "",
            content = row.getString("value") ?: "",
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
            legacyType = legacyType,
        )
    }

    private fun resolveLegacyType(entry: BrainKnowledgeEntry): MemoryType {
        return entry.legacyType ?: when (entry.knowledgeType) {
            BrainKnowledgeType.FACT -> MemoryType.PROJECT
            BrainKnowledgeType.DECISION -> MemoryType.DECISION
            BrainKnowledgeType.PREFERENCE -> MemoryType.PROJECT
            BrainKnowledgeType.CONSTRAINT -> MemoryType.PROJECT
            BrainKnowledgeType.TASK -> MemoryType.TASK
            BrainKnowledgeType.PROGRESS -> MemoryType.EPISODIC
            BrainKnowledgeType.DISCOVERY -> MemoryType.PROJECT
            BrainKnowledgeType.FAILURE -> MemoryType.EPISODIC
            BrainKnowledgeType.SOLUTION -> MemoryType.PROJECT
            BrainKnowledgeType.WORKSPACE_STATE -> MemoryType.PROJECT
        }
    }

    fun getById(id: String): BrainKnowledgeEntry? {
        val cached = cache.getById(id)
        if (cached != null) return cached

        val results = db.driver.query(
            "SELECT * FROM memory_entries WHERE id = ?",
            listOf(id),
            ::mapRow
        )
        val entry = results.firstOrNull()
        if (entry != null) {
            cache.put(entry)
        }
        return entry
    }

    fun findByProject(
        projectId: String,
        status: MemoryStatus? = MemoryStatus.ACTIVE,
        limit: Int = 100
    ): List<BrainKnowledgeEntry> {
        val sql = if (status != null) {
            "SELECT * FROM memory_entries WHERE project_id = ? AND status = ? ORDER BY importance DESC, updated_at DESC LIMIT ?"
        } else {
            "SELECT * FROM memory_entries WHERE project_id = ? ORDER BY updated_at DESC LIMIT ?"
        }
        val args = if (status != null) listOf(projectId, status.name, limit) else listOf(projectId, limit)
        return db.driver.query(sql, args, ::mapRow)
    }

    fun findByType(
        projectId: String,
        type: BrainKnowledgeType,
        status: MemoryStatus? = MemoryStatus.ACTIVE,
        limit: Int = 50
    ): List<BrainKnowledgeEntry> {
        val sql = if (status != null) {
            "SELECT * FROM memory_entries WHERE project_id = ? AND knowledge_type = ? AND status = ? ORDER BY importance DESC, updated_at DESC LIMIT ?"
        } else {
            "SELECT * FROM memory_entries WHERE project_id = ? AND knowledge_type = ? ORDER BY importance DESC, updated_at DESC LIMIT ?"
        }
        val args = if (status != null) listOf(projectId, type.name, status.name, limit) else listOf(projectId, type.name, limit)
        return db.driver.query(sql, args, ::mapRow)
    }

    fun findByTask(
        taskId: String,
        status: MemoryStatus? = MemoryStatus.ACTIVE,
        limit: Int = 50
    ): List<BrainKnowledgeEntry> {
        val sql = if (status != null) {
            "SELECT * FROM memory_entries WHERE task_id = ? AND status = ? ORDER BY importance DESC, updated_at DESC LIMIT ?"
        } else {
            "SELECT * FROM memory_entries WHERE task_id = ? ORDER BY importance DESC, updated_at DESC LIMIT ?"
        }
        val args = if (status != null) listOf(taskId, status.name, limit) else listOf(taskId, limit)
        return db.driver.query(sql, args, ::mapRow)
    }

    fun findByKey(
        projectId: String,
        key: String,
        status: MemoryStatus? = MemoryStatus.ACTIVE
    ): BrainKnowledgeEntry? {
        val normKey = MemoryConflictResolver.normalizeKey(key)
        val candidates = findByProject(projectId, status, limit = 200)
        return candidates.firstOrNull { MemoryConflictResolver.normalizeKey(it.key) == normKey }
    }

    fun insert(entry: BrainKnowledgeEntry): BrainKnowledgeEntry {
        val legacyType = resolveLegacyType(entry)
        val sql = """
            INSERT INTO memory_entries (
                id, project_id, session_id, scope, type, key, value, summary,
                importance, confidence, source, source_reference, status,
                version, superseded_by, tags, created_at, updated_at, last_accessed_at,
                knowledge_type, task_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        db.driver.execute(
            sql,
            listOf(
                entry.id,
                entry.projectId,
                entry.sessionId,
                entry.scope.name,
                legacyType.name,
                entry.key,
                entry.content,
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
                entry.lastAccessedAt.toEpochMilli(),
                entry.knowledgeType.name,
                entry.taskId
            )
        )
        db.syncFtsInsert(entry)
        cache.put(entry)
        return entry
    }

    fun update(entry: BrainKnowledgeEntry): BrainKnowledgeEntry {
        val legacyType = resolveLegacyType(entry)
        val sql = """
            UPDATE memory_entries SET
                session_id = ?, scope = ?, type = ?, key = ?, value = ?, summary = ?,
                importance = ?, confidence = ?, source = ?, source_reference = ?,
                status = ?, version = ?, superseded_by = ?, tags = ?,
                updated_at = ?, last_accessed_at = ?,
                knowledge_type = ?, task_id = ?
            WHERE id = ?
        """.trimIndent()

        db.driver.execute(
            sql,
            listOf(
                entry.sessionId,
                entry.scope.name,
                legacyType.name,
                entry.key,
                entry.content,
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
                entry.knowledgeType.name,
                entry.taskId,
                entry.id
            )
        )
        db.syncFtsUpdate(entry)
        cache.put(entry)
        return entry
    }

    fun supersede(oldId: String, newEntry: BrainKnowledgeEntry): BrainKnowledgeEntry {
        db.driver.transaction {
            val now = Instant.now()
            db.driver.execute(
                "UPDATE memory_entries SET status = ?, superseded_by = ?, updated_at = ? WHERE id = ?",
                listOf(MemoryStatus.SUPERSEDED.name, newEntry.id, now.toEpochMilli(), oldId)
            )
            val oldCached = cache.get(newEntry.projectId, oldId)
            if (oldCached != null) {
                cache.put(oldCached.copy(status = MemoryStatus.SUPERSEDED, supersededBy = newEntry.id, updatedAt = now))
            }
            insert(newEntry)
        }
        return newEntry
    }

    open fun save(incoming: BrainKnowledgeEntry): BrainKnowledgeEntry {
        return saveWithResolution(incoming).first
    }

    open fun saveWithResolution(incoming: BrainKnowledgeEntry): Pair<BrainKnowledgeEntry, MemoryConflictResolver.KnowledgeResolution> {
        val existing = findByProject(incoming.projectId, status = null, limit = 500)
        val resolution = MemoryConflictResolver.resolveKnowledge(incoming, existing)
        val entry = when (resolution) {
            is MemoryConflictResolver.KnowledgeResolution.InsertNew -> insert(resolution.newEntry)
            is MemoryConflictResolver.KnowledgeResolution.Coexisting -> insert(resolution.newEntry)
            is MemoryConflictResolver.KnowledgeResolution.Deduplicate -> update(resolution.updated)
            is MemoryConflictResolver.KnowledgeResolution.Supersede -> supersede(resolution.oldEntryId, resolution.newEntry)
            is MemoryConflictResolver.KnowledgeResolution.RejectedIncompatible -> resolution.existing
            is MemoryConflictResolver.KnowledgeResolution.RejectedLowerTrust -> resolution.existing
            is MemoryConflictResolver.KnowledgeResolution.NoChange -> resolution.existing
        }
        return Pair(entry, resolution)
    }

    fun delete(id: String) {
        val existing = getById(id)
        db.driver.execute("DELETE FROM memory_entries WHERE id = ?", listOf(id))
        db.syncFtsDelete(id)
        if (existing != null) {
            cache.remove(existing.projectId, id)
        }
    }

    fun search(projectId: String, query: String, limit: Int = 20): List<BrainKnowledgeEntry> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return findByProject(projectId, MemoryStatus.ACTIVE, limit)
        }

        // 1. Try FTS5 first
        if (db.isFts5Supported) {
            try {
                val preparedTerms = prepareSearchTerms(cleanQuery)
                if (preparedTerms.isNotEmpty()) {
                    val ftsTerms = preparedTerms.joinToString(" OR ") { "$it*" }
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

        // 2. LIKE fallback across key, value, summary, tags
        val tokens = prepareSearchTerms(cleanQuery)
        if (tokens.isEmpty()) {
            return findByProject(projectId, MemoryStatus.ACTIVE, limit)
        }
        val likeClauses = tokens.map {
            "(key LIKE ? OR value LIKE ? OR summary LIKE ? OR tags LIKE ?)"
        }

        val sql = """
            SELECT * FROM memory_entries
            WHERE project_id = ? AND status = 'ACTIVE'
              AND (${likeClauses.joinToString(" OR ")})
            ORDER BY importance DESC, updated_at DESC LIMIT ?
        """.trimIndent()

        val args = ArrayList<Any?>()
        args.add(projectId)
        for (token in tokens) {
            val pattern = "%$token%"
            args.add(pattern)
            args.add(pattern)
            args.add(pattern)
            args.add(pattern)
        }
        args.add(limit)

        return db.driver.query(sql, args, ::mapRow)
    }

    /**
     * Bounded retrieval operation.
     * Enforces hard result count limit and aggregate character budget.
     * Performs deterministic ranking, access tracking without blocking retrieval,
     * and guarantees project isolation.
     */
    fun retrieveRelevant(
        projectId: String,
        query: String?,
        taskId: String? = null,
        knowledgeTypes: Set<BrainKnowledgeType>? = null,
        limit: Int = 20,
        maxCharacters: Int = 8000
    ): List<BrainKnowledgeEntry> {
        val boundedLimit = limit.coerceIn(1, 100)
        val boundedMaxChars = maxCharacters.coerceAtLeast(1)
        val candidateLimit = maxOf(boundedLimit * 3, 50).coerceAtMost(100)

        val cleanQuery = query?.trim()
        val candidates: List<BrainKnowledgeEntry> = if (cleanQuery.isNullOrBlank()) {
            fetchCandidatesBlankQuery(projectId, taskId, knowledgeTypes, candidateLimit)
        } else {
            fetchCandidatesWithQuery(projectId, cleanQuery, taskId, knowledgeTypes, candidateLimit)
        }

        // Deterministic multi-axis ranking
        val ranked = MemoryRanker.rankKnowledge(
            entries = candidates,
            query = cleanQuery,
            taskId = taskId,
            targetKnowledgeTypes = knowledgeTypes,
            limit = boundedLimit * 2
        )

        // Enforce hard result count and aggregate character limits
        val results = ArrayList<BrainKnowledgeEntry>(boundedLimit)
        var currentChars = 0

        for (entry in ranked) {
            if (results.size >= boundedLimit) break
            val entryChars = entry.key.length + entry.content.length
            // An entry that does not fit is skipped, not treated as end-of-list, so
            // smaller lower-ranked candidates can still use the remaining budget.
            if (currentChars + entryChars > boundedMaxChars) continue
            results.add(entry)
            currentChars += entryChars
        }

        // Asynchronously update lastAccessedAt in batch without blocking retrieval
        if (results.isNotEmpty()) {
            trackAccess(results.map { it.id })
        }

        return results
    }

    private fun fetchCandidatesBlankQuery(
        projectId: String,
        taskId: String?,
        knowledgeTypes: Set<BrainKnowledgeType>?,
        candidateLimit: Int
    ): List<BrainKnowledgeEntry> {
        val whereClauses = mutableListOf("project_id = ?", "status = 'ACTIVE'")
        val args = mutableListOf<Any?>(projectId)

        if (taskId != null) {
            whereClauses.add("(task_id = ? OR task_id IS NULL)")
            args.add(taskId)
        }
        if (!knowledgeTypes.isNullOrEmpty()) {
            val placeholders = knowledgeTypes.joinToString(",") { "?" }
            whereClauses.add("knowledge_type IN ($placeholders)")
            knowledgeTypes.forEach { args.add(it.name) }
        }

        val sql = """
            SELECT * FROM memory_entries
            WHERE ${whereClauses.joinToString(" AND ")}
            ORDER BY importance DESC, updated_at DESC
            LIMIT ?
        """.trimIndent()
        args.add(candidateLimit)

        return db.driver.query(sql, args, ::mapRow)
    }

    private fun fetchCandidatesWithQuery(
        projectId: String,
        cleanQuery: String,
        taskId: String?,
        knowledgeTypes: Set<BrainKnowledgeType>?,
        candidateLimit: Int
    ): List<BrainKnowledgeEntry> {
        // 1. Try FTS5 if supported
        if (db.isFts5Supported) {
            try {
                val preparedTerms = prepareSearchTerms(cleanQuery)
                if (preparedTerms.isNotEmpty()) {
                    val ftsTerms = preparedTerms.joinToString(" OR ") { "$it*" }
                    val whereClauses = mutableListOf(
                        "m.project_id = ?",
                        "m.status = 'ACTIVE'",
                        "memory_fts MATCH ?"
                    )
                    val args = mutableListOf<Any?>(projectId, ftsTerms)

                    if (taskId != null) {
                        whereClauses.add("(m.task_id = ? OR m.task_id IS NULL)")
                        args.add(taskId)
                    }
                    if (!knowledgeTypes.isNullOrEmpty()) {
                        val placeholders = knowledgeTypes.joinToString(",") { "?" }
                        whereClauses.add("m.knowledge_type IN ($placeholders)")
                        knowledgeTypes.forEach { args.add(it.name) }
                    }

                    val sql = """
                        SELECT m.* FROM memory_entries m
                        INNER JOIN memory_fts f ON m.id = f.id
                        WHERE ${whereClauses.joinToString(" AND ")}
                        ORDER BY m.importance DESC, m.updated_at DESC
                        LIMIT ?
                    """.trimIndent()
                    args.add(candidateLimit)

                    val ftsResults = db.driver.query(sql, args, ::mapRow)
                    if (ftsResults.isNotEmpty()) return ftsResults
                }
            } catch (_: Throwable) {
                // Graceful fallback to LIKE query below
            }
        }

        // 2. LIKE fallback query
        val tokens = prepareSearchTerms(cleanQuery)
        if (tokens.isEmpty()) {
            return fetchCandidatesBlankQuery(projectId, taskId, knowledgeTypes, candidateLimit)
        }
        val tokenOrClauses = tokens.map {
            "(key LIKE ? OR value LIKE ? OR summary LIKE ? OR tags LIKE ?)"
        }

        val whereClauses = mutableListOf(
            "project_id = ?",
            "status = 'ACTIVE'",
            "(${tokenOrClauses.joinToString(" OR ")})"
        )
        val args = ArrayList<Any?>()
        args.add(projectId)

        for (token in tokens) {
            val pattern = "%$token%"
            args.add(pattern)
            args.add(pattern)
            args.add(pattern)
            args.add(pattern)
        }

        if (taskId != null) {
            whereClauses.add("(task_id = ? OR task_id IS NULL)")
            args.add(taskId)
        }
        if (!knowledgeTypes.isNullOrEmpty()) {
            val placeholders = knowledgeTypes.joinToString(",") { "?" }
            whereClauses.add("knowledge_type IN ($placeholders)")
            knowledgeTypes.forEach { args.add(it.name) }
        }

        val fallbackSql = """
            SELECT * FROM memory_entries
            WHERE ${whereClauses.joinToString(" AND ")}
            ORDER BY importance DESC, updated_at DESC
            LIMIT ?
        """.trimIndent()
        args.add(candidateLimit)

        return db.driver.query(fallbackSql, args, ::mapRow)
    }

    internal fun trackAccess(ids: List<String>) {
        if (ids.isEmpty()) return
        accessTrackingExecutor.submit {
            runCatching {
                val nowMs = System.currentTimeMillis()
                val placeholders = ids.joinToString(",") { "?" }
                val args = ArrayList<Any>(ids.size + 1).apply {
                    add(nowMs)
                    addAll(ids)
                }
                db.driver.execute(
                    "UPDATE memory_entries SET last_accessed_at = ? WHERE id IN ($placeholders)",
                    args
                )
            }
        }
    }

    internal fun flushAccessTracking(timeoutMs: Long = 2000) {
        accessTrackingExecutor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
