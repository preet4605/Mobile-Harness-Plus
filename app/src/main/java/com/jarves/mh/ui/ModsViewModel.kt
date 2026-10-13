package com.jarves.mh.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarves.mh.model.Project
import com.jarves.mh.mods.EngineCheck
import com.jarves.mh.mods.EngineReport
import com.jarves.mh.mods.InstalledMod
import com.jarves.mh.mods.ModAuthoringGuide
import com.jarves.mh.mods.ModAuditEntry
import com.jarves.mh.mods.ModCapability
import com.jarves.mh.mods.ModCodeCommandOutcome
import com.jarves.mh.mods.ModEngineProbe
import com.jarves.mh.mods.ModHost
import com.jarves.mh.mods.ModHostSnapshot
import com.jarves.mh.mods.ModLogEntry
import com.jarves.mh.mods.ModInstallException
import com.jarves.mh.mods.ModPlace
import com.jarves.mh.mods.ModRegistry
import com.jarves.mh.mods.ModSurfaces
import com.jarves.mh.mods.ModTrust
import com.jarves.mh.mods.ModsSnapshot
import com.jarves.mh.mods.StagedMod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject

/** What the review sheet shows: a checked package, or why it could not be read. */
data class ModReview(
    val staged: StagedMod?,
    val sourceLabel: String,
    val trust: ModTrust,
    val existing: InstalledMod? = null,
    val error: String? = null,
    val problems: List<String> = emptyList(),
)

/** A Mod Studio draft: a project whose folder holds `mod.json` and the Studio guide. */
data class ModDraft(
    val project: Project,
    val folder: File,
    val modName: String,
    val version: String,
    val modId: String,
)

/** The engine test page: lines so far while it runs, then the finished report. */
data class EngineTestState(
    val running: Boolean = false,
    val checks: List<EngineCheck> = emptyList(),
    val report: EngineReport? = null,
)

/**
 * The Mods screen's state. All registry work runs on IO; a failure becomes a banner message,
 * never a crash.
 */
class ModsViewModel(application: Application) : AndroidViewModel(application) {
    private val registry = viewModelScope.async(Dispatchers.IO) { ModRegistry.getInstance(application) }
    private val _snapshot = MutableStateFlow(ModsSnapshot())
    val snapshot: StateFlow<ModsSnapshot> = _snapshot.asStateFlow()
    private val _review = MutableStateFlow<ModReview?>(null)
    val review: StateFlow<ModReview?> = _review.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private val _drafts = MutableStateFlow<List<ModDraft>>(emptyList())
    val drafts: StateFlow<List<ModDraft>> = _drafts.asStateFlow()
    private val _engineTest = MutableStateFlow(EngineTestState())
    val engineTest: StateFlow<EngineTestState> = _engineTest.asStateFlow()
    private var engineTestJob: Job? = null
    private val host = viewModelScope.async(Dispatchers.IO) { runCatching { ModHost.getInstance(application) }.getOrNull() }
    private val _hostSnapshot = MutableStateFlow(ModHostSnapshot())
    /** How mod code is running: the engine and each mod with a hooks module. */
    val hostSnapshot: StateFlow<ModHostSnapshot> = _hostSnapshot.asStateFlow()
    @Volatile private var hostRef: ModHost? = null
    private val _logVersion = MutableStateFlow(0)
    /** Changes when a mod log gains a line; read the lines with [modLog]. */
    val logVersion: StateFlow<Int> = _logVersion.asStateFlow()
    private val _surfaces = MutableStateFlow<Map<String, ModSurfaces>>(emptyMap())
    /** Each logic mod's panes and what they show now. */
    val surfaces: StateFlow<Map<String, ModSurfaces>> = _surfaces.asStateFlow()

    init {
        viewModelScope.launch { registry.await().snapshot.collect { _snapshot.value = it } }
        viewModelScope.launch { host.await()?.also { hostRef = it }?.snapshot?.collect { _hostSnapshot.value = it } }
        viewModelScope.launch { host.await()?.logVersion?.collect { _logVersion.value = it } }
        viewModelScope.launch { host.await()?.surfaces?.collect { _surfaces.value = it } }
    }

    /** A mod page shows [surface]; the host draws it only while shown. */
    fun showSurface(modId: String, surface: String, visible: Boolean) {
        viewModelScope.launch { host.await()?.show(modId, surface, PAGE_HOLDER, visible) }
    }

    /** A tap or an edit inside a mod's pane. */
    fun press(modId: String, surface: String, handler: String, value: Any?) {
        hostRef?.press(modId, surface, handler, value)
    }

    /** A dashboard action: runs the mod's command and shows what it answered. */
    fun runAction(modId: String, name: String) {
        viewModelScope.launch {
            val outcome = host.await()?.runCommand(modId, name, "") ?: return@launch
            val text = when (outcome) {
                is ModCodeCommandOutcome.Reply -> outcome.text
                is ModCodeCommandOutcome.Failed -> outcome.message
                is ModCodeCommandOutcome.Prompt -> "/$name writes a message for the agent. Use it in a project's chat."
                ModCodeCommandOutcome.Nothing -> null
            }
            text?.let { _messages.emit(it.take(300)) }
        }
    }

    fun setShownIn(id: String, place: ModPlace, shown: Boolean) =
        work("Could not change where the mod shows") { registry.await().setShownIn(id, place, shown) }

    /** The Mods screen is showing: mod code runs while it is, so pages show live state (spec v2 §3.3 app scope). */
    fun setVisible(visible: Boolean) {
        viewModelScope.launch { host.await()?.setScreenVisible(SCREEN_KEY, visible) }
    }

    /** The newest lines of a mod's log, oldest first. */
    suspend fun modLog(id: String): List<ModLogEntry> = host.await()?.log(id).orEmpty()

    /** Starts a crashed mod again with its strikes cleared. */
    fun restartMod(id: String) {
        viewModelScope.launch { host.await()?.restart(id) }
    }

    /** Resumes mod code after the engine crashed repeatedly. */
    fun resumeModCode() {
        viewModelScope.launch { host.await()?.restart(null) }
    }

    override fun onCleared() {
        hostRef?.setScreenVisible(SCREEN_KEY, false)
        super.onCleared()
    }

    fun importArchive(uri: Uri) = work("Could not read that file") {
        val context = getApplication<Application>()
        val label = displayName(uri) ?: "Imported file"
        val staged = try {
            val input = context.contentResolver.openInputStream(uri) ?: throw ModInstallException("Could not open that file.")
            input.use { registry.await().stageArchive(it) }
        } catch (e: ModInstallException) {
            _review.value = ModReview(null, label, ModTrust.IMPORTED, error = e.message, problems = e.problems)
            return@work
        }
        _review.value = ModReview(staged, label, ModTrust.IMPORTED, existing = existing(staged.manifest.id))
    }

    /** Finds the Studio drafts among [projects], reading only each folder's mod.json. */
    fun refreshDrafts(projects: List<Project>) {
        viewModelScope.launch(Dispatchers.IO) {
            val workspaces = File(getApplication<Application>().filesDir, "workspaces")
            _drafts.value = projects.mapNotNull { project -> runCatching { readDraft(workspaces, project) }.getOrNull() }
        }
    }

    private fun readDraft(workspaces: File, project: Project): ModDraft? {
        val base = File(workspaces, project.id)
        val folder = if (project.rootPath.isBlank()) base else File(base, project.rootPath)
        if (!folder.canonicalFile.toPath().startsWith(base.canonicalFile.toPath())) return null
        val manifest = File(folder, "mod.json")
        if (!manifest.isFile || !File(folder, ModAuthoringGuide.GUIDE_FILE).isFile || manifest.length() > 64 * 1024) return null
        val json = runCatching { JSONObject(manifest.readText()) }.getOrNull()
        return ModDraft(
            project = project,
            folder = folder,
            modName = json?.optString("name")?.takeIf { it.isNotBlank() } ?: project.name,
            version = json?.optString("version").orEmpty(),
            modId = json?.optString("id").orEmpty(),
        )
    }

    /** A Mod Studio draft, built by an agent. Installed as T2 ([ModTrust.BUILT]). */
    fun reviewDraft(draft: ModDraft) = work("Could not read the draft") {
        val label = "Studio draft · ${draft.project.name}"
        val staged = try {
            registry.await().stageDraft(draft.folder)
        } catch (e: ModInstallException) {
            _review.value = ModReview(null, label, ModTrust.BUILT, error = e.message, problems = e.problems)
            return@work
        }
        _review.value = ModReview(staged, label, ModTrust.BUILT, existing = existing(staged.manifest.id))
    }

    fun confirmInstall(granted: Set<ModCapability>) = work("Could not install the mod") {
        val review = _review.value ?: return@work
        val staged = review.staged ?: return@work
        try {
            val mod = registry.await().install(staged, granted, review.trust, review.sourceLabel)
            _review.value = null
            _messages.emit("Installed ${mod.manifest.name} ${mod.manifest.version}.")
        } catch (e: ModInstallException) {
            _review.value = review.copy(staged = null, error = e.message, problems = e.problems)
            registry.await().discard(staged)
        }
    }

    fun dismissReview() {
        val staged = _review.value?.staged
        _review.value = null
        if (staged != null) work("Could not clean up") { registry.await().discard(staged) }
    }

    fun setEnabled(id: String, enabled: Boolean) = work("Could not change the mod") {
        registry.await().setEnabled(id, enabled)
        val mod = registry.await().snapshot.value.mods.firstOrNull { it.id == id }
        if (enabled && mod?.problem != null) _messages.emit("${mod.manifest.name} could not be turned on: ${mod.problem}")
    }

    fun setGranted(id: String, capability: ModCapability, granted: Boolean) =
        work("Could not change the permission") { registry.await().setGranted(id, capability, granted) }

    fun setTrusted(id: String, trusted: Boolean) = work("Could not change trust") { registry.await().setTrusted(id, trusted) }

    fun setRuleEnabled(id: String, ruleId: String, enabled: Boolean) =
        work("Could not change the rule") { registry.await().setRuleEnabled(id, ruleId, enabled) }

    fun setSkillEnabled(id: String, skillId: String, enabled: Boolean) =
        work("Could not change the skill") { registry.await().setSkillEnabled(id, skillId, enabled) }

    fun setHookEnabled(id: String, hookId: String, enabled: Boolean) =
        work("Could not change the hook") { registry.await().setHookEnabled(id, hookId, enabled) }

    fun setSetting(id: String, key: String, value: Any) = work("Could not save the setting") { registry.await().setSetting(id, key, value) }

    fun setModsEnabled(enabled: Boolean) = work("Could not change mods") {
        registry.await().setModsEnabled(enabled)
        _messages.emit(if (enabled) "Mods are on." else "All mods are off.")
    }

    fun leaveSafeMode() = work("Could not turn mods back on") { registry.await().leaveSafeMode() }

    fun remove(id: String, deleteData: Boolean) = work("Could not remove the mod") {
        val name = snapshot.value.mods.firstOrNull { it.id == id }?.manifest?.name ?: id
        registry.await().remove(id, deleteData)
        _messages.emit("Removed $name.")
    }

    fun export(id: String, uri: Uri) = work("Could not export the mod") {
        val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
            ?: throw ModInstallException("Could not write that file.")
        output.use { registry.await().export(id, it) }
        _messages.emit("Exported the mod.")
    }

    suspend fun audit(id: String?): List<ModAuditEntry> =
        runCatching { withContext(Dispatchers.IO) { registry.await().readAudit(id) } }.getOrDefault(emptyList())

    /** Rule or skill text for the preview sheet. */
    suspend fun readText(mod: InstalledMod, path: String): String = runCatching {
        withContext(Dispatchers.IO) { File(mod.dir, path).readText().take(64 * 1024) }
    }.getOrDefault("")

    /** Runs the engine test once; tapping Run again while it runs does nothing. */
    fun runEngineTest() {
        if (engineTestJob?.isActive == true) return
        _engineTest.value = EngineTestState(running = true)
        engineTestJob = viewModelScope.launch {
            val report = try {
                // The app has one sandbox; running mods pause while the test uses it.
                val probe = suspend {
                    ModEngineProbe(getApplication()).run { check -> _engineTest.update { it.copy(checks = it.checks + check) } }
                }
                host.await()?.withEngineStopped { probe() } ?: probe()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _engineTest.update { it.copy(running = false) }
                throw e
            } catch (t: Throwable) {
                _messages.tryEmit("The engine test stopped: ${t.message ?: t.javaClass.simpleName}")
                null
            }
            _engineTest.update { it.copy(running = false, report = report) }
        }
    }

    private companion object {
        const val SCREEN_KEY = "mods-screen"
        const val PAGE_HOLDER = "mods-page"
    }

    private suspend fun existing(id: String): InstalledMod? = registry.await().snapshot.value.mods.firstOrNull { it.id == id }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun work(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit("$failure: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _busy.value = false
            }
        }
    }
}
