package com.jarves.mh.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import org.json.JSONObject

/** Metadata only. OAuth credentials and transcripts stay in CLI-owned runtime homes. */
data class CodexAccount(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val needsSignIn: Boolean = false,
    val scope: String = id,
    val reportedUsedPercent: Int? = null,
)

internal class CodexAccounts(rootfs: File) {
    // Android may expose private app storage through a system-owned /data/user/0 alias.
    private val rootfs = rootfs.canonicalFile
    private fun metadata(): File = contained("root/.codex-accounts.properties")
    private fun read() = Properties().apply { metadata().takeIf { it.isFile }?.inputStream()?.use(::load) }
    private fun write(p: Properties) {
        val target = metadata()
        target.parentFile!!.mkdirs()
        val tmp = contained("root/.codex-accounts.properties.tmp")
        tmp.outputStream().use { p.store(it, "Codex account metadata; no credentials") }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    private fun ids(p: Properties) = p.getProperty("ids", "").split(',').filter { it.isNotEmpty() }.onEach(::validate)
    private fun account(p: Properties, id: String) = CodexAccount(
        id, p.getProperty("$id.label", "ChatGPT account"), p.getProperty("$id.enabled", "true").toBoolean(),
        p.getProperty("$id.needsSignIn", "false").toBoolean(), p.getProperty("$id.scope", id),
        p.getProperty("$id.reportedUsedPercent")?.toIntOrNull(),
    )
    fun list(): List<CodexAccount> = synchronized(lock) {
        val p = read()
        ids(p).filter { p.getProperty("$it.ready") == "true" }.map { account(p, it) }
    }
    fun selectedId(): String? = synchronized(lock) { read().getProperty("selected")?.takeIf { it.isNotEmpty() } }
    var balancingEnabled: Boolean
        get() = synchronized(lock) { read().getProperty("balancing", "false").toBoolean() }
        set(value) = synchronized(lock) { val p = read(); p.setProperty("balancing", value.toString()); write(p) }

    /** Admission only. The returned ID is persisted on the task and never reselected on retry. */
    fun selectForTask(pinnedId: String? = null, nowMillis: Long = System.currentTimeMillis(), model: String = ""): CodexAccount = synchronized(lock) {
        if (pinnedId != null) return@synchronized requireUsable(pinnedId).also {
            require(credentialFile(it.id).isFile) { "Reconnect this task's ChatGPT account first." }
        }
        if (!balancingEnabled) return@synchronized selected()
        val p = read()
        val items = list()
        val last = items.indexOfFirst { it.id == p.getProperty("balanceCursor") }
        val ordered = if (last < 0) items else items.drop(last + 1) + items.take(last + 1)
        val item = ordered.firstOrNull { it.enabled && !it.needsSignIn && credentialFile(it.id).isFile && !quotaBlocked(p, it.id, nowMillis, model) }
            ?: error("No enabled ChatGPT account has available reported usage. Refresh usage, reconnect an account, or wait for its reset.")
        p.setProperty("balanceCursor", item.id)
        write(p)
        item
    }

    /** Sparse native events merge into their account; missing/error values never invent availability. */
    fun recordRateLimits(id: String, snapshot: JSONObject, nowMillis: Long = System.currentTimeMillis()) = synchronized(lock) {
        val p = read(); validate(id)
        if (id !in ids(p)) return@synchronized
        val model = snapshot.optString("normalModelSlug").takeUnless { snapshot.isNull("normalModelSlug") }.orEmpty()
        val limit = snapshot.optString("limitId").takeUnless { snapshot.isNull("limitId") }.orEmpty()
        // Unknown aliases are displayed but cannot establish exhaustion for another model.
        val group = if (limit.isBlank() || limit == "codex") "general" else if (model.isNotBlank()) quotaGroup(model) else null
        (snapshot.opt("spendControlReached") as? Boolean)?.let { p.setProperty("$id.spendBlocked", it.toString()) }
        for (name in listOf("primary", "secondary")) {
            val window = snapshot.optJSONObject(name) ?: continue
            if (window.isNull("usedPercent")) continue
            val percent = (window.opt("usedPercent") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: continue
            val prefix = "$id.quota.$group.$name"
            if (group != null) {
            p.setProperty("$prefix.percent", percent.toString())
            p.setProperty("$prefix.observedAt", nowMillis.toString())
            (window.opt("resetsAt") as? Number)?.toLong()?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.let {
                p.setProperty("$prefix.reset", (it * 1000).toString())
            } ?: run { if ((p.getProperty("$prefix.reset")?.toLongOrNull() ?: 0) <= nowMillis) p.remove("$prefix.reset") }
            }
            p.setProperty("$id.reportedUsedPercent", percent.toInt().toString())
        }
        write(p)
    }

    private fun quotaGroup(model: String) = java.security.MessageDigest.getInstance("SHA-256").digest(model.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun quotaBlocked(p: Properties, id: String, nowMillis: Long, model: String): Boolean =
        p.getProperty("$id.spendBlocked") == "true" || (listOf("general") + model.takeIf { it.isNotBlank() }?.let { listOf(quotaGroup(it)) }.orEmpty()).any { group -> listOf("primary", "secondary").any { name ->
        val used = p.getProperty("$id.quota.$group.$name.percent")?.toDoubleOrNull() ?: return@any false
        val reset = p.getProperty("$id.quota.$group.$name.reset")?.toLongOrNull()
        val observedAt = p.getProperty("$id.quota.$group.$name.observedAt")?.toLongOrNull() ?: 0L
        used >= 100 && if (reset != null) reset > nowMillis else nowMillis - observedAt in 0..15 * 60 * 1000L
    } }
    fun selected(): CodexAccount = requireUsable(selectedId() ?: error("Choose a ChatGPT account in Coding agent."))
    fun requireUsable(id: String): CodexAccount = synchronized(lock) {
        validate(id)
        val item = list().firstOrNull { it.id == id }
        require(item != null && item.enabled && !item.needsSignIn) { "This ChatGPT account is unavailable. Reconnect or enable it in Coding agent." }
        item
    }
    fun create(): CodexAccount = synchronized(lock) {
        val p = read()
        val id = UUID.randomUUID().toString()
        p.setProperty("ids", (ids(p) + id).joinToString(","))
        p.setProperty("$id.label", "ChatGPT account ${ids(p).size}")
        write(p)
        hostHome(id).mkdirs()
        account(p, id)
    }
    fun complete(id: String, label: String) = synchronized(lock) {
        val p = read()
        require(id in ids(p))
        p.setProperty("$id.label", label.take(160).ifBlank { "ChatGPT account" })
        p.setProperty("$id.ready", "true")
        p.setProperty("$id.enabled", "true")
        p.setProperty("$id.needsSignIn", "false")
        p.setProperty("$id.scope", UUID.randomUUID().toString())
        if (p.getProperty("selected").isNullOrEmpty()) p.setProperty("selected", id)
        write(p)
    }
    /** Called only after account/read confirms a ChatGPT login. Never moves existing files. */
    fun migrateLegacy(label: String) = synchronized(lock) {
        val p = read()
        if (p.getProperty("legacyRegistered") == "true") return@synchronized
        p.setProperty("legacyRegistered", "true")
        p.setProperty("ids", (ids(p) + LEGACY).distinct().joinToString(","))
        p.setProperty("$LEGACY.ready", "true")
        p.setProperty("$LEGACY.label", label.take(160))
        if (p.getProperty("selected").isNullOrEmpty()) p.setProperty("selected", LEGACY)
        write(p)
    }
    fun legacyRegistered(): Boolean = synchronized(lock) { read().getProperty("legacyRegistered") == "true" }
    fun select(id: String) = synchronized(lock) {
        requireUsable(id)
        val p = read(); p.setProperty("selected", id); write(p)
    }
    fun rename(id: String, label: String) = synchronized(lock) {
        require(label.isNotBlank()) { "Enter an account label." }
        val p = read()
        require(id in ids(p))
        p.setProperty("$id.label", label.trim().take(160))
        write(p)
    }
    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        val p = read(); require(id in ids(p))
        p.setProperty("$id.enabled", enabled.toString()); write(p)
    }
    fun markExpired(id: String) = synchronized(lock) {
        val p = read(); require(id in ids(p))
        p.setProperty("$id.needsSignIn", "true"); write(p)
    }
    fun remove(id: String) = synchronized(lock) {
        val p = read(); require(id in ids(p))
        p.setProperty("ids", ids(p).filterNot { it == id }.joinToString(","))
        p.stringPropertyNames().filter { it.startsWith("$id.") }.forEach(p::remove)
        if (p.getProperty("selected") == id) p.remove("selected")
        write(p) // Deliberately retain CLI transcripts; never recursively delete the legacy home.
    }
    fun home(id: String): String {
        validate(id)
        val path = if (id == LEGACY) "/root/.codex" else "/root/.codex-accounts/$id"
        contained(path.removePrefix("/"))
        return path
    }
    fun hostHome(id: String): File = contained(home(id).removePrefix("/"))
    fun credentialFile(id: String): File = contained(home(id).removePrefix("/") + "/auth.json")
    private fun contained(path: String): File {
        val file = File(rootfs, path)
        var component: File? = file
        while (component != null && component != rootfs) {
            require(!Files.isSymbolicLink(component.toPath())) { "Codex account paths must not contain symbolic links." }
            component = component.parentFile
        }
        require(file.canonicalFile == file.absoluteFile && file.canonicalFile.toPath().startsWith(rootfs.canonicalFile.toPath())) {
            "Codex account path is not a private runtime directory."
        }
        return file
    }
    companion object {
        const val LEGACY = "legacy"
        private val lock = Any()
        private fun validate(id: String) {
            require(id == LEGACY || id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "Invalid Codex account ID" }
        }
        fun taskProvider(id: String): String { validate(id); return "CHATGPT:$id" }
        fun taskAccount(provider: String): String? = when {
            provider == "CHATGPT" -> LEGACY
            provider.startsWith("CHATGPT:") -> provider.removePrefix("CHATGPT:").also(::validate)
            else -> null
        }
    }
}
