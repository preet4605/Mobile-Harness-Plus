package com.jarves.mh.mods

import com.jarves.mh.model.AgentKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/** Where an event happened. */
data class ModEventContext(
    val projectId: String? = null,
    val projectName: String? = null,
    /** The project folder files.append writes in, read on the worker; null when no project is open. */
    val projectRoot: (() -> File)? = null,
    val chatId: String? = null,
    val harness: AgentKind? = null,
)

/** Where hook actions land. The app implements it; every call comes from one background worker. */
interface ModHookEffects {
    fun toast(mod: InstalledMod, text: String)

    /** False when the user has turned notifications off for the app. */
    fun notify(mod: InstalledMod, title: String, body: String): Boolean

    fun chatPost(mod: InstalledMod, text: String, context: ModEventContext)

    /** False when the composer already has text: a draft never replaces what the user typed. */
    fun promptDraft(mod: InstalledMod, text: String, context: ModEventContext): Boolean
}

/**
 * Runs declarative hooks (spec v2 §5.3) on one background worker, in event order. Each action
 * needs its capability granted now, is rate limited per mod, and is recorded in the audit log;
 * five failures in a row turn a hook off. Nothing here can throw into the caller, and an event
 * no active mod listens to costs one snapshot read.
 */
class ModHookRunner(
    private val registry: ModRegistry,
    private val effects: ModHookEffects,
    scope: CoroutineScope,
    dispatcher: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Pending(val event: ModEvent, val context: ModEventContext, val vars: Map<String, String>, val status: String?, val time: Long)

    private val queue = Channel<Pending>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val lastRun = HashMap<String, Long>()

    init {
        scope.launch(dispatcher) {
            for (pending in queue) runCatching { run(pending) }
        }
    }

    /**
     * Queues [event] for every active mod that listens to it. [vars] are the event's own
     * template variables; [status] is what a hook's `filter.status` matches.
     */
    fun emit(event: ModEvent, context: ModEventContext, vars: Map<String, String> = emptyMap(), status: String? = null) {
        if (!listens(event)) return
        queue.trySend(Pending(event, context, vars.filterKeys { it in event.vars }, status, clock()))
    }

    /** True when an active mod has a hook for [event]; lets callers skip building the event's variables. */
    fun listens(event: ModEvent): Boolean = registry.snapshot.value.active.any { mod -> mod.manifest.hooks.any { it.on == event } }

    private fun run(pending: Pending) {
        registry.snapshot.value.active.forEach { mod ->
            mod.manifest.hooks.filter { it.on == pending.event }.forEach { hook ->
                runCatching { runHook(mod, hook, pending) }
            }
        }
    }

    private fun runHook(mod: InstalledMod, hook: ModDeclarativeHook, pending: Pending) {
        if (hook.id in mod.disabledHooks) return
        if (hook.harness != null && hook.harness != pending.context.harness) return
        if (hook.status != null && hook.status != pending.status) return
        val action = hook.action
        val capability = action.capability
        if (capability !in mod.granted || !mod.canHold(capability)) {
            if (allow("denied:${mod.id}/${hook.id}", DENIED_LOG_INTERVAL_MS)) {
                registry.recordDenied(mod.id, "hook ${hook.id}", capability, "permission is off")
            }
            return
        }
        val readsChat = action.templates.any { CHAT_VARS.any { name -> name in ModManifestParser.templateVars(it) } }
        if (readsChat && ModCapability.CHAT_READ !in mod.granted) {
            if (allow("denied:${mod.id}/${hook.id}", DENIED_LOG_INTERVAL_MS)) {
                registry.recordDenied(mod.id, "hook ${hook.id}", ModCapability.CHAT_READ, "reading the chat is off")
            }
            return
        }
        if (!allow("${mod.id}/${capability.id}", intervalFor(action))) return
        val vars = variables(mod, pending)
        fun fill(template: String, max: Int) = render(template, vars).take(max)
        val result: Result<String?> = runCatching {
            when (action) {
                is ModHookAction.Toast -> {
                    val text = clean(fill(action.text, 200))
                    if (text.isBlank()) return@runCatching "empty"
                    effects.toast(mod, "${mod.manifest.name}: $text")
                    null
                }
                is ModHookAction.Notify -> {
                    val title = clean(fill(action.title, 80)).ifBlank { mod.manifest.name }
                    if (!effects.notify(mod, title, fill(action.body, 400))) throw SkipException("notifications are off")
                    null
                }
                is ModHookAction.ChatPost -> {
                    val text = fill(action.text, 4000).trim()
                    if (text.isEmpty()) return@runCatching "empty"
                    effects.chatPost(mod, text, pending.context)
                    null
                }
                is ModHookAction.PromptDraft -> {
                    val text = fill(action.text, 4000).trim()
                    if (text.isEmpty()) return@runCatching "empty"
                    if (!effects.promptDraft(mod, text, pending.context)) throw SkipException("the composer already has text")
                    null
                }
                is ModHookAction.FilesAppend -> {
                    append(mod, action, clean(fill(action.line, MAX_LINE)), pending.context)
                    action.path
                }
            }
        }
        result.fold(
            onSuccess = { detail -> registry.recordHookRun(mod.id, hook.id, capability, true, detail) },
            onFailure = { error ->
                if (error is SkipException) {
                    registry.recordDenied(mod.id, "hook ${hook.id}", capability, error.message)
                } else {
                    registry.recordHookRun(mod.id, hook.id, capability, false, error.message ?: error.javaClass.simpleName)
                }
            },
        )
    }

    private fun append(mod: InstalledMod, action: ModHookAction.FilesAppend, line: String, context: ModEventContext) {
        val root = context.projectRoot?.invoke() ?: throw SkipException("no project is open")
        val scope = mod.manifest.permission(ModCapability.FILES_WRITE)?.paths.orEmpty()
        require(scope.any { ModPaths.matchesGlob(it, action.path) }) { "${action.path} is outside the mod's files.write scope" }
        val file = ModPaths.resolveForWrite(root, action.path)
        require(!file.exists() || file.isFile) { "${action.path} is not a file" }
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        require(file.length() + bytes.size <= MAX_APPEND_FILE_BYTES) { "${action.path} would pass 1 MB" }
        file.parentFile?.mkdirs()
        Files.newOutputStream(
            file.toPath(),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
        ).use { it.write(bytes) }
    }

    private fun variables(mod: InstalledMod, pending: Pending): Map<String, String> {
        val date = Date(pending.time)
        val vars = HashMap<String, String>()
        vars["project.name"] = pending.context.projectName.orEmpty()
        vars["harness"] = pending.context.harness?.let(ModManifestParser::harnessId).orEmpty()
        vars["time"] = SimpleDateFormat("HH:mm", Locale.ROOT).format(date)
        vars["date"] = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(date)
        vars["mod.name"] = mod.manifest.name
        pending.vars.forEach { (key, value) -> vars[key] = value.take(MAX_VAR) }
        mod.manifest.settings.filter { it.type != ModSettingType.SECRET }.forEach {
            vars["settings.${it.key}"] = ModContributionSet.formatValue(mod.settingValue(it.key))
        }
        return vars
    }

    private fun allow(key: String, intervalMs: Long): Boolean {
        val now = clock()
        val last = lastRun[key]
        if (last != null && now - last < intervalMs) return false
        lastRun[key] = now
        return true
    }

    private class SkipException(message: String) : Exception(message)

    companion object {
        private const val MAX_VAR = 2000
        private const val MAX_LINE = 2000
        private const val MAX_APPEND_FILE_BYTES = 1024L * 1024
        private const val DENIED_LOG_INTERVAL_MS = 60_000L
        /** Template variables that carry chat text: a hook may use them only with chat.read granted. */
        private val CHAT_VARS = setOf("message.text")

        /** "1m 5s" for task.duration. */
        fun formatDuration(millis: Long): String {
            val seconds = (millis / 1000).coerceAtLeast(0)
            return when {
                seconds >= 3600 -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
                seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
                else -> "${seconds}s"
            }
        }

        fun intervalFor(action: ModHookAction): Long = when (action) {
            is ModHookAction.Toast -> 1_000L
            is ModHookAction.Notify -> 60_000L
            is ModHookAction.ChatPost -> 5_000L
            is ModHookAction.PromptDraft -> 5_000L
            is ModHookAction.FilesAppend -> 1_000L
        }

        private val TEMPLATE_VAR = Regex("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*\\}\\}")

        fun render(template: String, vars: Map<String, String>): String =
            TEMPLATE_VAR.replace(template) { vars[it.groupValues[1]].orEmpty() }

        /** One line of plain text: no line breaks or control characters. */
        fun clean(text: String): String = text.replace(Regex("[\\r\\n\\t]+"), " ").filter { it >= ' ' }.trim()
    }
}
