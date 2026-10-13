package com.jarves.mh.mods

import android.content.Context
import com.jarves.mh.BuildConfig
import com.jarves.mh.model.RiskLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** How the app came to have a mod. A manifest can never claim a trust level (spec v1 §8.1). */
enum class ModTrust(val id: String, val label: String) {
    IMPORTED("imported", "Imported"),
    BUILT("built", "Built in Mod Studio"),
}

enum class ModStatus(val label: String) {
    ON("On"),
    OFF("Off"),
    NEEDS_REVIEW("Needs review"),
    PAUSED("Paused"),
}

data class InstalledMod(
    val manifest: ModManifest,
    val dir: File,
    val enabled: Boolean,
    val trust: ModTrust,
    /** Studio-built mods may hold HIGH capabilities only after "Trust this mod" (spec v2 §7, D12). */
    val trusted: Boolean,
    val sourceLabel: String,
    val granted: Set<ModCapability>,
    val settings: Map<String, Any>,
    val enabledRules: Set<String>,
    val enabledSkills: Set<String>,
    val disabledHooks: Set<String>,
    val installedAt: Long,
    /** Why the mod cannot run until it is reviewed again, for example files changed on disk. */
    val problem: String? = null,
) {
    val id: String get() = manifest.id

    fun settingValue(key: String): Any? = settings[key] ?: manifest.settings.firstOrNull { it.key == key }?.default

    fun canHold(capability: ModCapability): Boolean =
        capability.usable && capability in manifest.requested &&
            (capability.risk != RiskLevel.HIGH || trust == ModTrust.IMPORTED || trusted)
}

data class ModsSnapshot(
    val mods: List<InstalledMod> = emptyList(),
    /** The kill switch (spec v1 §8.9). */
    val modsEnabled: Boolean = true,
    /** Set for one app run after repeated starts that never became stable. */
    val safeMode: Boolean = false,
) {
    /** Mods whose contributions take effect now. */
    val active: List<InstalledMod>
        get() = if (!modsEnabled || safeMode) emptyList() else mods.filter { it.enabled && it.problem == null }

    fun status(mod: InstalledMod): ModStatus = when {
        mod.problem != null -> ModStatus.NEEDS_REVIEW
        !mod.enabled -> ModStatus.OFF
        !modsEnabled || safeMode -> ModStatus.PAUSED
        else -> ModStatus.ON
    }
}

data class ModAuditEntry(
    val time: Long,
    val modId: String,
    val version: String,
    val op: String,
    val capability: String?,
    val decision: String,
    val detail: String?,
)

/**
 * Installed mods and their state, under `filesDir/mods` (spec v1 §7.1): `<id>/<version>/` holds a
 * mod's files, `_state.json` its grants, switches and settings, `_audit.jsonl` the audit log,
 * `_staging/` packages waiting for review. Nothing here is bound into the Linux guest.
 *
 * Every change is written to `_state.json` (temp file, sync, rename) before it is published.
 */
class ModRegistry(
    private val root: File,
    private val appVersionCode: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val stateFile = File(root, "_state.json")
    private val auditFile = File(root, "_audit.jsonl")
    private val stagingRoot = File(root, "_staging")
    private val lock = Any()
    private var state = PersistedState()
    private val hookFailures = mutableMapOf<String, Int>()
    private val _snapshot = MutableStateFlow(ModsSnapshot())
    val snapshot: StateFlow<ModsSnapshot> = _snapshot.asStateFlow()

    /** Mods whose files no longer match their hashes (spec v1 §6: checked at load and at every enable). */
    private val problems = mutableMapOf<String, String>()
    /** Parsed manifests by mod id, for the installed version. */
    private val manifests = mutableMapOf<String, ModManifest>()
    private var safeMode = false

    init {
        synchronized(lock) {
            state = readState()
            // Staged packages never survive a restart; the review sheet that owned them is gone.
            stagingRoot.deleteRecursively()
            publishLocked()
            _snapshot.value.mods.filter { it.enabled }.forEach { mod -> verify(mod)?.let { problems[mod.id] = it } }
            if (problems.isNotEmpty()) publishLocked()
        }
    }

    // ---- install -------------------------------------------------------------------------

    /** Unpacks and checks a `.mhmod` (or ZIP) for the review sheet. Throws [ModInstallException]. */
    fun stageArchive(input: InputStream): StagedMod =
        ModPackage.stageArchive(input, File(root, "_work"), newStagingDir(), appVersionCode)

    /** Copies and checks a mod folder, such as a Mod Studio draft. Throws [ModInstallException]. */
    fun stageDirectory(source: File): StagedMod = ModPackage.stageDirectory(source, newStagingDir(), appVersionCode)

    fun discard(staged: StagedMod) {
        if (staged.dir.canonicalFile.toPath().startsWith(stagingRoot.canonicalFile.toPath())) staged.dir.deleteRecursively()
    }

    /**
     * Installs [staged] after the user saw its review sheet, and turns it on. [granted] is what the
     * user allowed; only capabilities the mod asked for and this app can act on are kept.
     */
    fun install(staged: StagedMod, granted: Set<ModCapability>, trust: ModTrust, sourceLabel: String): InstalledMod {
        val manifest = staged.manifest
        require(staged.dir.canonicalFile.toPath().startsWith(stagingRoot.canonicalFile.toPath())) { "Not a staged mod" }
        // Re-check: the staging folder is private, but a review can sit open while the app runs.
        val fresh = ModPackage.check(staged.dir, appVersionCode)
        if (fresh.files != staged.files || fresh.manifest != manifest) throw ModInstallException("The mod changed during review. Import it again.")
        synchronized(lock) {
            val modRoot = File(root, manifest.id).apply { mkdirs() }
            val target = File(modRoot, manifest.version)
            val previous = state.mods[manifest.id]
            val parked = if (target.exists()) File(modRoot, ".old-${UUID.randomUUID()}").also { move(target, it) } else null
            try {
                move(staged.dir, target)
            } catch (e: Exception) {
                parked?.let { move(it, target) }
                throw ModInstallException("Could not install the mod (${e.message}).")
            }
            parked?.deleteRecursively()
            modRoot.listFiles().orEmpty()
                .filter { it.isDirectory && it.name != manifest.version && it.name != DATA_DIR }
                .forEach { it.deleteRecursively() }

            val keptSettings = previous?.settings.orEmpty().filter { (key, value) ->
                manifest.settings.firstOrNull { it.key == key }?.let { settingAccepts(it, value) } == true
            }
            val record = PersistedMod(
                version = manifest.version,
                enabled = true,
                trust = trust,
                trusted = previous?.trusted == true && trust == ModTrust.BUILT && previous.trust == ModTrust.BUILT,
                sourceLabel = sourceLabel.take(120),
                granted = emptySet(),
                settings = keptSettings,
                enabledRules = previous?.enabledRules.orEmpty().filter { id -> manifest.rules.any { it.id == id } }.toSet(),
                enabledSkills = previous?.enabledSkills.orEmpty().filter { id -> manifest.skills.any { it.id == id } }.toSet(),
                disabledHooks = emptySet(),
                installedAt = clock(),
                manifestSha = ModPackage.sha256(File(target, ModPackage.MANIFEST)),
            )
            problems.remove(manifest.id)
            manifests.remove(manifest.id)
            val probe = toInstalled(manifest, target, record)
            state = state.copy(mods = state.mods + (manifest.id to record.copy(granted = granted.filter(probe::canHold).toSet())))
            writeStateLocked()
            auditLocked(manifest.id, manifest.version, if (previous == null) "install" else "update", null, "allowed",
                "${trust.id}; granted ${state.mods.getValue(manifest.id).granted.joinToString { it.id }.ifEmpty { "nothing" }}")
            publishLocked()
            return _snapshot.value.mods.first { it.id == manifest.id }
        }
    }

    fun remove(id: String, deleteData: Boolean) = synchronized(lock) {
        val record = state.mods[id] ?: return@synchronized
        val modRoot = File(root, id)
        if (deleteData) modRoot.deleteRecursively()
        else modRoot.listFiles().orEmpty().filter { it.name != DATA_DIR }.forEach { it.deleteRecursively() }
        state = state.copy(mods = state.mods - id)
        problems.remove(id)
        manifests.remove(id)
        writeStateLocked()
        auditLocked(id, record.version, "remove", null, "allowed", if (deleteData) "with saved data" else null)
        publishLocked()
    }

    fun export(id: String, out: OutputStream) {
        val mod = snapshot.value.mods.firstOrNull { it.id == id } ?: throw ModInstallException("That mod is not installed.")
        ModPackage.export(mod.dir, out)
        synchronized(lock) { auditLocked(id, mod.manifest.version, "export", null, "allowed", null) }
    }

    // ---- switches ------------------------------------------------------------------------

    /** Turning a mod on checks its files again (spec v1 §6: integrity at every enable). */
    fun setEnabled(id: String, enabled: Boolean) = update(id, if (enabled) "enable" else "disable") { record, mod ->
        if (enabled) {
            val problem = verify(mod)
            if (problem != null) {
                problems[id] = problem
                return@update record.copy(enabled = false)
            }
            problems.remove(id)
        }
        record.copy(enabled = enabled)
    }

    fun setGranted(id: String, capability: ModCapability, granted: Boolean) =
        update(id, if (granted) "grant" else "revoke", capability) { record, mod ->
            if (granted && !mod.canHold(capability)) return@update record
            record.copy(granted = if (granted) record.granted + capability else record.granted - capability)
        }

    /** "Trust this mod": lets a Studio-built mod hold HIGH capabilities, still granted one at a time. */
    fun setTrusted(id: String, trusted: Boolean) = update(id, if (trusted) "trust" else "untrust") { record, mod ->
        if (record.trust != ModTrust.BUILT) return@update record
        val granted = if (trusted) record.granted else record.granted.filter { it.risk != RiskLevel.HIGH }.toSet()
        record.copy(trusted = trusted, granted = granted)
    }

    fun setRuleEnabled(id: String, ruleId: String, enabled: Boolean) = update(id, if (enabled) "rule.on" else "rule.off") { record, mod ->
        if (mod.manifest.rules.none { it.id == ruleId }) return@update record
        record.copy(enabledRules = if (enabled) record.enabledRules + ruleId else record.enabledRules - ruleId)
    }

    fun setSkillEnabled(id: String, skillId: String, enabled: Boolean) = update(id, if (enabled) "skill.on" else "skill.off") { record, mod ->
        if (mod.manifest.skills.none { it.id == skillId }) return@update record
        record.copy(enabledSkills = if (enabled) record.enabledSkills + skillId else record.enabledSkills - skillId)
    }

    /** Stores a setting value; secrets are not stored here (only mod code could read them). */
    fun setSetting(id: String, key: String, value: Any) = update(id, "setting", detail = key) { record, mod ->
        val setting = mod.manifest.settings.firstOrNull { it.key == key } ?: return@update record
        if (!settingAccepts(setting, value)) return@update record
        record.copy(settings = record.settings + (key to value))
    }

    fun setHookEnabled(id: String, hookId: String, enabled: Boolean) = update(id, if (enabled) "hook.on" else "hook.off", detail = hookId) { record, _ ->
        synchronized(lock) { hookFailures.remove("$id/$hookId") }
        record.copy(disabledHooks = if (enabled) record.disabledHooks - hookId else record.disabledHooks + hookId)
    }

    /** The kill switch. Off: no mod contributes anything; files and settings stay. */
    fun setModsEnabled(enabled: Boolean) = synchronized(lock) {
        state = state.copy(modsEnabled = enabled)
        writeStateLocked()
        auditLocked("*", "", if (enabled) "mods.on" else "mods.off", null, "allowed", null)
        publishLocked()
    }

    // ---- hooks and audit -----------------------------------------------------------------

    /**
     * Records one run of a declarative hook. Five failures in a row turn the hook off until the
     * user turns it back on (spec v1 §9.4).
     */
    fun recordHookRun(id: String, hookId: String, capability: ModCapability?, ok: Boolean, detail: String?) = synchronized(lock) {
        val record = state.mods[id] ?: return@synchronized
        val key = "$id/$hookId"
        auditLocked(id, record.version, "hook $hookId", capability?.id, if (ok) "allowed" else "failed", detail)
        if (ok) {
            hookFailures.remove(key)
            return@synchronized
        }
        val failures = (hookFailures[key] ?: 0) + 1
        hookFailures[key] = failures
        if (failures >= MAX_HOOK_FAILURES) {
            hookFailures.remove(key)
            state = state.copy(mods = state.mods + (id to record.copy(disabledHooks = record.disabledHooks + hookId)))
            writeStateLocked()
            auditLocked(id, record.version, "hook $hookId", null, "turned off", "$MAX_HOOK_FAILURES failures in a row")
            publishLocked()
        }
    }

    /** Records a refused action, such as a hook whose capability was revoked. */
    fun recordDenied(id: String, op: String, capability: ModCapability?, detail: String?) = synchronized(lock) {
        val record = state.mods[id] ?: return@synchronized
        auditLocked(id, record.version, op, capability?.id, "denied", detail)
    }

    /** The newest audit entries, newest first; [modId] null for every mod. */
    fun readAudit(modId: String?, limit: Int = 100): List<ModAuditEntry> = synchronized(lock) {
        listOf(File(root, "_audit.1.jsonl"), auditFile)
            .filter { it.isFile }
            .flatMap { file -> runCatching { file.readLines() }.getOrDefault(emptyList()) }
            .mapNotNull { line -> runCatching { parseAudit(JSONObject(line)) }.getOrNull() }
            .filter { modId == null || it.modId == modId }
            .takeLast(limit)
            .reversed()
    }

    // ---- safe mode -----------------------------------------------------------------------

    /**
     * Counts this app start. The third start in a row that never became stable (see [markStable])
     * runs in safe mode: no mod contributes anything for this run (spec v1 §8.9).
     */
    fun onProcessStart() = synchronized(lock) {
        if (state.mods.isEmpty()) return@synchronized
        val starts = state.unstableStarts + 1
        state = state.copy(unstableStarts = starts)
        writeStateLocked()
        if (starts >= SAFE_MODE_STARTS) {
            safeMode = true
            auditLocked("*", "", "safe-mode", null, "on", "$starts starts without a stable run")
            publishLocked()
        }
    }

    /** The app ran long enough, or left the screen cleanly: the next start counts from zero. */
    fun markStable() = synchronized(lock) {
        if (state.unstableStarts == 0) return@synchronized
        state = state.copy(unstableStarts = 0)
        writeStateLocked()
    }

    /** "Turn mods back on" after safe mode. */
    fun leaveSafeMode() = synchronized(lock) {
        safeMode = false
        state = state.copy(unstableStarts = 0)
        writeStateLocked()
        auditLocked("*", "", "safe-mode", null, "off", "by the user")
        publishLocked()
    }

    // ---- internals -----------------------------------------------------------------------

    private fun update(
        id: String,
        op: String,
        capability: ModCapability? = null,
        detail: String? = null,
        change: (PersistedMod, InstalledMod) -> PersistedMod,
    ) = synchronized(lock) {
        val record = state.mods[id] ?: return@synchronized
        val mod = _snapshot.value.mods.firstOrNull { it.id == id } ?: return@synchronized
        val changed = change(record, mod)
        if (changed == record && op != "enable") {
            auditLocked(id, record.version, op, capability?.id, "refused", detail)
            return@synchronized
        }
        state = state.copy(mods = state.mods + (id to changed))
        writeStateLocked()
        val decision = if (changed == record) "refused" else "allowed"
        auditLocked(id, record.version, op, capability?.id, decision, detail)
        publishLocked()
    }

    /** Null when mod.json and every file still match what was installed. */
    private fun verify(mod: InstalledMod): String? = runCatching {
        val recorded = state.mods[mod.id]?.manifestSha.orEmpty()
        check(recorded.isEmpty() || recorded == ModPackage.sha256(File(mod.dir, ModPackage.MANIFEST)))
        ModPackage.check(mod.dir, appVersionCode)
        null
    }.getOrElse { "Its files changed since it was installed. Remove it and import it again." }

    private fun newStagingDir(): File = File(stagingRoot, UUID.randomUUID().toString()).apply { mkdirs() }

    private fun publishLocked() {
        val mods = state.mods.mapNotNull { (id, record) ->
            val dir = File(File(root, id), record.version)
            val manifest = manifests[id]?.takeIf { it.version == record.version }
                ?: runCatching { ModManifestParser.parse(File(dir, ModPackage.MANIFEST).readText(), appVersionCode) }.getOrNull()
                    ?.also { manifests[id] = it }
                ?: return@mapNotNull null
            toInstalled(manifest, dir, record)
        }.sortedBy { it.manifest.name.lowercase() }
        _snapshot.value = ModsSnapshot(mods = mods, modsEnabled = state.modsEnabled, safeMode = safeMode)
    }

    private fun toInstalled(manifest: ModManifest, dir: File, record: PersistedMod) = InstalledMod(
        manifest = manifest,
        dir = dir,
        enabled = record.enabled,
        trust = record.trust,
        trusted = record.trusted,
        sourceLabel = record.sourceLabel,
        granted = record.granted,
        settings = record.settings,
        enabledRules = record.enabledRules,
        enabledSkills = record.enabledSkills,
        disabledHooks = record.disabledHooks,
        installedAt = record.installedAt,
        problem = problems[manifest.id],
    )

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun readState(): PersistedState {
        if (!stateFile.isFile) return PersistedState()
        return runCatching { PersistedState.fromJson(JSONObject(stateFile.readText())) }.getOrElse {
            // A damaged state file must not lose the mods: keep a copy and start with every mod off.
            runCatching { stateFile.copyTo(File(root, "_state.damaged.json"), overwrite = true) }
            PersistedState()
        }
    }

    private fun writeStateLocked() {
        root.mkdirs()
        val temp = File(root, "_state.json.tmp")
        FileOutputStream(temp).use { out ->
            out.write(state.toJson().toString(2).toByteArray())
            out.fd.sync()
        }
        Files.move(temp.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun auditLocked(modId: String, version: String, op: String, capability: String?, decision: String, detail: String?) {
        runCatching {
            root.mkdirs()
            if (auditFile.length() > MAX_AUDIT_BYTES) {
                Files.move(auditFile.toPath(), File(root, "_audit.1.jsonl").toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val line = JSONObject()
                .put("t", clock())
                .put("mod", modId)
                .put("v", version)
                .put("op", op)
                .put("cap", capability ?: JSONObject.NULL)
                .put("decision", decision)
                .put("detail", detail?.take(300) ?: JSONObject.NULL)
            auditFile.appendText(line.toString() + "\n")
        }
    }

    private fun parseAudit(o: JSONObject) = ModAuditEntry(
        time = o.optLong("t"),
        modId = o.optString("mod"),
        version = o.optString("v"),
        op = o.optString("op"),
        capability = o.optString("cap").takeIf { it.isNotBlank() && !o.isNull("cap") },
        decision = o.optString("decision"),
        detail = o.optString("detail").takeIf { it.isNotBlank() && !o.isNull("detail") },
    )

    companion object {
        const val DATA_DIR = "data"
        const val MAX_HOOK_FAILURES = 5
        const val SAFE_MODE_STARTS = 3
        const val MAX_AUDIT_BYTES = 1024L * 1024

        @Volatile private var instance: ModRegistry? = null

        /** Application-wide registry. The first call reads `_state.json`, so call it off Main. */
        fun getInstance(context: Context): ModRegistry = instance ?: synchronized(this) {
            instance ?: ModRegistry(File(context.applicationContext.filesDir, "mods"), BuildConfig.VERSION_CODE).also {
                it.onProcessStart()
                instance = it
            }
        }

        fun settingAccepts(setting: ModSetting, value: Any): Boolean = when (setting.type) {
            ModSettingType.BOOL -> value is Boolean
            ModSettingType.NUMBER -> value is Double && value.isFinite()
            ModSettingType.STRING -> value is String && value.length <= (setting.maxLength ?: 1024)
            ModSettingType.ENUM -> value is String && value in setting.options
            ModSettingType.SECRET -> false
        }
    }
}

private data class PersistedMod(
    val version: String,
    val enabled: Boolean,
    val trust: ModTrust,
    val trusted: Boolean,
    val sourceLabel: String,
    val granted: Set<ModCapability>,
    val settings: Map<String, Any>,
    val enabledRules: Set<String>,
    val enabledSkills: Set<String>,
    val disabledHooks: Set<String>,
    val installedAt: Long,
    /** SHA-256 of mod.json at install; `integrity` cannot cover the manifest itself. */
    val manifestSha: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", version)
        .put("enabled", enabled)
        .put("trust", trust.id)
        .put("trusted", trusted)
        .put("source", sourceLabel)
        .put("granted", JSONArray(granted.map { it.id }.sorted()))
        .put("settings", JSONObject().apply { settings.forEach { (k, v) -> put(k, v) } })
        .put("rules", JSONArray(enabledRules.sorted()))
        .put("skills", JSONArray(enabledSkills.sorted()))
        .put("disabledHooks", JSONArray(disabledHooks.sorted()))
        .put("installedAt", installedAt)
        .put("manifestSha", manifestSha)

    companion object {
        fun fromJson(o: JSONObject): PersistedMod = PersistedMod(
            version = o.getString("version"),
            enabled = o.optBoolean("enabled", false),
            trust = ModTrust.entries.firstOrNull { it.id == o.optString("trust") } ?: ModTrust.IMPORTED,
            trusted = o.optBoolean("trusted", false),
            sourceLabel = o.optString("source"),
            granted = o.optJSONArray("granted").strings().mapNotNull(ModCapability::fromId).toSet(),
            settings = o.optJSONObject("settings")?.let { s ->
                s.keys().asSequence().associateWith { key ->
                    when (val v = s.get(key)) {
                        is Number -> v.toDouble()
                        else -> v
                    }
                }
            }.orEmpty(),
            enabledRules = o.optJSONArray("rules").strings().toSet(),
            enabledSkills = o.optJSONArray("skills").strings().toSet(),
            disabledHooks = o.optJSONArray("disabledHooks").strings().toSet(),
            installedAt = o.optLong("installedAt"),
            manifestSha = o.optString("manifestSha"),
        )
    }
}

private data class PersistedState(
    val modsEnabled: Boolean = true,
    val unstableStarts: Int = 0,
    val mods: Map<String, PersistedMod> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("modsEnabled", modsEnabled)
        .put("unstableStarts", unstableStarts)
        .put("mods", JSONObject().apply { mods.forEach { (id, record) -> put(id, record.toJson()) } })

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(o: JSONObject): PersistedState {
            val mods = o.optJSONObject("mods")
            return PersistedState(
                modsEnabled = o.optBoolean("modsEnabled", true),
                unstableStarts = o.optInt("unstableStarts", 0),
                mods = mods?.keys()?.asSequence()?.mapNotNull { id ->
                    runCatching { id to PersistedMod.fromJson(mods.getJSONObject(id)) }.getOrNull()
                }?.toMap().orEmpty(),
            )
        }
    }
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
