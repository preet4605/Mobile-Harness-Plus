package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.data.ContextMemory
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

/**
 * [RuntimeBridge] driving the pinned Codex stdio app-server with native controls.
 *
 * One process per supervised task (native goals can span turns). The process is confined by the PRoot guest, so Codex's own sandbox is
 * off and approvals are `never`. Successful native threads resume within the owning chat and route;
 * acknowledged threads persist before work starts. Parsing, deadlines and the outcome
 * decision live in [CodexAppServerRunner]; this class owns the Android side: preflight, process
 * launch, event forwarding, workspace checkpoints and cleanup.
 *
 * Stability contract: every failure becomes exactly one [RuntimeEvent.SessionFailed]; the child
 * process and its temporary files are always cleaned up; nothing here can block on a live process.
 */
class CodexRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    override val isRunning: Boolean get() = activeProcess?.isAlive == true
    @Volatile private var activeSessionId: String? = null
    @Volatile private var activeTaskId: String? = null
    private val pendingGoalControl = java.util.concurrent.atomic.AtomicReference<CodexNativeControl?>()
    private val stopState = BridgeStopState()
    private var userStopRequested by stopState::userStopRequested
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
        memory: ContextMemory,
        taskId: String?,
        brainSnapshot: com.jarves.mh.data.BrainContextSnapshot?,
        attemptId: String?,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        if (taskId != null) {
            runCatching {
                com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).bindSession(taskId, sessionId)
            }
        }
        val snapshot = brainSnapshot ?: taskId?.let {
            runCatching { com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).getBrainSnapshot(it) }.getOrNull()
        }
        val effectiveAttemptId = attemptId ?: snapshot?.attemptId
        val injectedPrompt = ControlledBrainInjector.inject(prompt, snapshot, taskId, effectiveAttemptId)
        pendingGoalControl.set(null)
        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        activeTaskId = taskId
        val cancellationActive = taskId != null && runCatching {
            com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).isCancellationActive(taskId)
        }.getOrDefault(false)
        if (stopState.beginSession(taskId, cancellationActive)) {
            activeSessionId = null
            activeTaskId = null
            emitFailureOnce(sessionId, "Stopped by user")
            cancelForegroundRuntime()
            throw CodexSessionException("Stopped by user")
        }
        val isTaskTerminal = taskId != null && runCatching {
            val supervisor = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context)
            supervisor.stateStore.get(taskId)?.status?.isTerminal == true
        }.getOrDefault(false)
        if (isTaskTerminal) {
            activeSessionId = null
            activeTaskId = null
            emitFailureOnce(sessionId, "Task $taskId is already terminal")
            throw IllegalStateException("Task $taskId is already terminal")
        }
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress("Starting Codex…")

        var outputFile: File? = null
        var contextClaim: Pair<String, String>? = null
        try {
            runCatching {
                RuntimeTaskController.stopAction = {
                    userStopRequested = true
                    // The owned app-server runner pauses a goal and interrupts its turn before closing.
                }
                taskId?.let { id -> com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).registerStopHandler(id) { force ->
                    stopState.requestStop(id)
                    stopProcess(force)
                } }
                val owner = taskId?.let { com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).stateStore.get(it)
                    ?: error("This task no longer has a durable account binding.") }
                val accounts = CodexAccounts(installer.rootfs)
                val account = if (provider.kind == com.jarves.mh.model.ProviderKind.CHATGPT) {
                    val id = if (owner != null) CodexAccounts.taskAccount(owner.providerJson)
                        ?: error("This task has no ChatGPT account binding.") else accounts.selected().id
                    accounts.requireUsable(id)
                } else null
                val route = if (account != null) CodexRoute.ChatGptLogin(provider.model.trim(), accounts.home(account.id))
                    else CodexRouteMapper.forProfile(provider)
                val secret = if (route is CodexRoute.ApiKey) secretFor(provider).orEmpty() else null
                if (route is CodexRoute.ApiKey && secret.isNullOrBlank()) {
                    throw CodexSessionException("No API key is saved for ${provider.kind.title}.")
                }
                startForegroundRuntime(projectSlug, taskId)
                check(installer.isCodexInstalled()) {
                    "Codex is not installed or needs repair. Open Settings → Coding agent and tap Install."
                }
                val installed = installer.installedRuntime()
                if (route is CodexRoute.ChatGptLogin && !accounts.credentialFile(account!!.id).isFile) {
                    throw CodexSessionException("Codex is not signed in. Sign in from Settings → Coding agent.")
                }
                if (userStopRequested) throw CodexSessionException("Stopped by user")

                val workspace = checkpoints.ensureWorkspace(projectId)
                checkpoints.createCheckpoint(projectId, workspace)
                val before = checkpoints.snapshot(workspace)

                val guestWorkspacePath = "/workspace/$projectSlug"
                val prefs = AppPreferences(context)
                val identity = if (account != null) "codex-account:${account.id}:${account.scope}"
                    else com.jarves.mh.data.ApiKeyVault(context).credentials(provider.secretId).firstOrNull { it.isActive }?.id.orEmpty()
                val conversationScope = owner?.chatId?.let { NativeConversationScope.key(it, workspace, provider, identity) }
                val nativeControl = owner?.nativeControlJson?.let(CodexNativeControl::decode)
                val handoff = CodexConversationHandoff(installer.rootfs)
                val handoffKey = owner?.chatId?.takeIf { account != null }?.let { NativeConversationScope.key(it, workspace, provider, "codex-account-pool") }
                val managedContext = handoffKey != null
                val supervisor = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context)
                val previous = owner?.let { supervisor.stateStore.getLatestTaskForChatAnyAgent(projectId, it.chatId, it.taskId) }
                if (managedContext) {
                    val key = handoffKey!!
                    handoff.recoverInterrupted(key) { pending ->
                        val task = supervisor.stateStore.get(pending)
                        task?.status?.isTerminal == true && !supervisor.processSupervisor.isAlive(pending) &&
                            (task.pid == null || !supervisor.processSupervisor.isProcessAlive(task.pid))
                    }
                    if (handoff.owner(key) != null && previous?.taskId != handoff.completedTask(key)) {
                        handoff.continueFromHistory(key)
                    }
                    if (handoff.owner(key) == null && handoff.goalOwner(key) == null && !handoff.wasReset(key) && !handoff.needsHistory(key)) {
                        val adopted = runCatching {
                            val previousId = previous?.takeIf { it.agentKind == AgentKind.CODEX.name }?.providerJson?.let(CodexAccounts::taskAccount)
                                ?: return@runCatching false
                            check(previous.status in setOf(com.jarves.mh.runtime.task.TaskExecutionStatus.COMPLETED,
                                com.jarves.mh.runtime.task.TaskExecutionStatus.UNVERIFIED))
                            check(!supervisor.processSupervisor.isAlive(previous.taskId) &&
                                (previous.pid == null || !supervisor.processSupervisor.isProcessAlive(previous.pid)))
                            val previousAccount = accounts.requireUsable(previousId)
                            val previousScope = NativeConversationScope.key(owner!!.chatId, workspace, provider, "codex-account:${previousAccount.id}:${previousAccount.scope}")
                            val previousThread = prefs.loadAgentConversation(AgentKind.CODEX, projectId, previousScope)
                                ?: return@runCatching false
                            handoff.adopt(key, "$projectId:${owner.chatId}", previousAccount, previousThread,
                                handoff.findRollout(previousAccount, previousThread).path, guestWorkspacePath,
                                goal = prefs.loadCodexGoalThread(projectId, previousScope) != null, completedTask = previous.taskId)
                            if (!handoff.hasPlanMode(key)) handoff.savePlanMode(key, "$projectId:${owner.chatId}", prefs.codexPlanMode(projectId, previousScope))
                            true
                        }.getOrDefault(false)
                        if (!adopted && conversationHistory.count { it.fromUser } > 1) handoff.continueFromHistory(key)
                    }
                    val pinned = handoff.goalOwner(key) ?: handoff.childThreadOwner(key)
                    if (pinned != null && (pinned != account!!.id || handoff.owner(key)?.accountScope?.let { it != account.scope } == true)) {
                        handoff.continueFromHistory(key)
                    }
                }
                val savedId = if (managedContext) {
                    val key = handoffKey!!
                    require(nativeControl?.kind !in setOf("goalStatus", "goalPause", "goalClear", "goalResume") ||
                        !handoff.needsHistory(key) && handoff.savedGoalOwner(key) != null) {
                        "The saved native goal is unavailable in this context. Continue this chat normally or set a new goal; your history and workspace are retained."
                    }
                    handoff.claim(key, owner!!.taskId, account!!, guestWorkspacePath, "$projectId:${owner.chatId}",
                        goal = nativeControl?.kind == "goalSet", goalControl = nativeControl?.goalControl == true || nativeControl?.kind == "goalResume")
                    contextClaim = key to owner.taskId
                    if (nativeControl?.kind == "goalResume" && handoff.goalOwner(key) == null) {
                        val imported = handoff.importForTask(key, owner.taskId, account, guestWorkspacePath)
                        require(imported != null && imported == conversationScope?.let { prefs.loadCodexGoalThread(projectId, it) }) {
                            "This saved goal belongs to older conversation context. Set a new goal to preserve this chat's current context."
                        }
                        handoff.pinSavedGoal(key, owner.taskId, account)
                    }
                    if (nativeControl?.kind in setOf("goalStatus", "goalPause", "goalClear", "goalResume") || handoff.goalOwner(key) != null)
                        conversationScope?.let { prefs.loadCodexGoalThread(projectId, it) } ?: handoff.owner(key)?.threadId
                    else try {
                        handoff.importForTask(key, owner.taskId, account, guestWorkspacePath)
                    } catch (_: IllegalArgumentException) {
                        handoff.continueFromHistory(key, owner.taskId)
                        null
                    } catch (_: IllegalStateException) {
                        handoff.continueFromHistory(key, owner.taskId)
                        null
                    } catch (_: java.io.IOException) {
                        handoff.continueFromHistory(key, owner.taskId)
                        null
                    }
                } else conversationScope?.let { scope ->
                    if (nativeControl?.kind in setOf("goalStatus", "goalPause", "goalClear", "goalResume"))
                        prefs.loadCodexGoalThread(projectId, scope) ?: prefs.loadAgentConversation(AgentKind.CODEX, projectId, scope)
                    else prefs.consumeAgentConversation(AgentKind.CODEX, projectId, scope)
                }
                val latestContext = if (managedContext) !handoff.needsHistory(handoffKey!!)
                    else NativeConversationScope.mayResumeFrom(previous, owner, AgentKind.CODEX)
                require(latestContext || nativeControl?.kind !in setOf("goalStatus", "goalPause", "goalClear", "goalResume")) {
                    "The saved native goal is unavailable after switching context. Continue this chat normally or set a new goal."
                }
                val resumeId = if (latestContext && (nativeControl != null || owner?.retryCount == 0))
                    NativeConversationScope.validId(savedId) else null
                if (resumeId == null && conversationHistory.count { it.fromUser } > 1) {
                    eventBus.emit(RuntimeEvent.RuntimeLog(sessionId, "Continuing saved chat",
                        "Continuing in a new native thread using saved chat history and the current workspace. Older messages may be bounded; native tool, goal and subagent state may be unavailable."))
                }
                val planMode = if (managedContext && handoff.hasPlanMode(handoffKey!!)) handoff.planMode(handoffKey)
                    else conversationScope?.let { prefs.codexPlanMode(projectId, it) } == true
                if (nativeControl?.kind == "planTurn" && conversationScope != null) {
                    prefs.saveCodexPlanMode(projectId, conversationScope, true)
                    if (managedContext) handoff.savePlanMode(handoffKey!!, "$projectId:${owner!!.chatId}", true)
                }
                val contextPrompt = buildContextPrompt(injectedPrompt, if (resumeId == null) conversationHistory else emptyList(), guestWorkspacePath, projectKind, memory)
                val capture = File(context.cacheDir, "codex-output-$sessionId.log").also { outputFile = it }
                val reasoningEffort = com.jarves.mh.model.codexEffortToLaunch(
                    prefs.codexReasoningEffort,
                    route.model,
                    prefs.loadModelList(AgentKind.CODEX, provider.kind, provider.baseUrl, accountId = account?.id),
                )
                val skillConfig = if (nativeControl?.goalControl == true) null else {
                val projects = prefs.loadProjects()
                val project = projects.firstOrNull { it.id == projectId }
                    ?: error("Cannot resolve this project's skill settings.")
                val skillSettings = prefs.loadProjectCustomizationConfig(projectId)
                val skillManager = SkillManager(context)
                val skills = GuestSkillAccess(context.filesDir).prepare(
                    skillManager.compileActiveProjectSkills(project, skillSettings, projects,
                        File(context.filesDir, "workspaces"), installed.rootfs),
                    workspace, installed.rootfs, guestWorkspacePath,
                )
                pushForegroundProgress("Checking Codex skills…")
                var surveyPid: Int? = null
                val nativeSkills = CodexModelCatalog(context).readSkills(account?.id ?: CodexAccounts.LEGACY,
                    workspace, guestWorkspacePath, cancelled = { userStopRequested }, onProcess = { survey ->
                        activeProcess = survey
                        if (survey != null) {
                            surveyPid = survey.processPid
                            val bound = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).bindProcess(
                                taskId ?: sessionId, sessionId, survey, survey.processPid)
                            check(bound) { "Stopped before Codex skill discovery." }
                        } else {
                            surveyPid?.let { pid -> com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context)
                                .healthMonitor.onTaskProcessClosed(pid) }
                            surveyPid = null
                        }
                    }) ?: error(if (userStopRequested) "Stopped by user" else "Codex could not validate its skill settings. Check skill files and try again.")
                if (userStopRequested) throw CodexSessionException("Stopped by user")
                CodexSkillSelection.config(nativeSkills, skills, skillSettings.scopeMode)
                }
                val imagePaths = CodexLaunchBuilder.imagePaths(prompt, guestWorkspacePath)
                // Mods' guest tools and MCP servers (mod plan row 11): only mods allowed guest.tools.
                val modServers = com.jarves.mh.mods.ModGuestTools.prepare(installed.rootfs, com.jarves.mh.mods.ModRegistry.getInstance(context).snapshot.value)
                val command = CodexLaunchBuilder.appServerCommand(route, reasoningEffort,
                    inspectionTools = installer.ensureInspectionTools(installed.rootfs), skillConfig = skillConfig,
                    extraOverrides = com.jarves.mh.mods.ModGuestTools.codexOverrides(modServers, CodexLaunchBuilder::tomlString))
                val environment = CodexLaunchBuilder.environment(route, secret)
                Log.d(TAG, "Route: ${route::class.simpleName}, Model: ${route.model.ifBlank { "default" }}")
                if (account != null) check(accounts.requireUsable(account.id).scope == account.scope) {
                    "This task's ChatGPT account changed before launch. Send your message again to continue."
                }
                val process = installer.process(
                    installed.proot,
                    installed.rootfs,
                    workspace,
                    environment,
                    command,
                    guestWorkspacePath = guestWorkspacePath,
                    inspectionAllowed = { activeSessionId == sessionId && activeTaskId == taskId && !userStopRequested },
                    credentialHome = (route as? CodexRoute.ChatGptLogin)?.home ?: CodexLaunchBuilder.CODEX_HOME_GUEST_PATH,
                    // Same reason as the other one-shot harnesses: PRoot's hard-link emulation can turn
                    // an atomic temp-file rename into a dangling `.l2s` symlink.
                    emulateHardLinks = false,
                    outputFile = capture,
                )
                activeProcess = process
                val nativeProcess = process as? NativeSpawnProcess ?: error("Unsupported Android runtime process")
                val bound = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).bindProcess(
                    taskId = taskId ?: sessionId,
                    sessionId = sessionId,
                    process = process,
                    pid = nativeProcess.processPid,
                )
                check(bound) { "Stopped before native Codex task admission." }

                val mapper = CodexEventMapper(sessionId, guestWorkspacePath)
                val runner = CodexAppServerRunner(
                    io = NativeCodexProcessIo(nativeProcess),
                    workspace = guestWorkspacePath,
                    prompt = contextPrompt,
                    model = route.model,
                    resumeId = resumeId,
                    planMode = planMode,
                    control = nativeControl,
                    imagePaths = imagePaths,
                    emit = { event -> forward(mapper, event) },
                    onThread = { id -> if (nativeControl?.kind in setOf("goalSet", "goalResume")) conversationScope?.let { prefs.saveCodexGoalThread(projectId, it, id) } },
                    onRateLimits = { snapshot -> account?.let { accounts.recordRateLimits(it.id, snapshot) } },
                    takeGoalControl = { pendingGoalControl.getAndSet(null) },
                    isStopRequested = { userStopRequested },
                    imageData = { guestPath -> nativeImageData(workspace, guestWorkspacePath, guestPath) },
                    reasoningEffort = reasoningEffort,
                    // Off unless the user turned on mods' Codex gate for this project (Developer mode).
                    toolGate = com.jarves.mh.mods.ModHost.current.value?.takeIf { it.codexGateOn(projectId) }?.let { host ->
                        { tool, input -> host.gateCodexTool(tool, input) }
                    },
                    onActivity = { active -> taskId?.let { id ->
                        val supervisor = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context)
                        if (active) supervisor.wakeLockManager.resume(id) else supervisor.wakeLockManager.pause(id)
                        supervisor.healthMonitor.onWakeLockChanged(supervisor.wakeLockManager.isHeld, supervisor.wakeLockManager.activeTaskCount)
                    } },
                )
                val result = runner.run()
                (process as? NativeSpawnProcess)?.checkCapture()
                Log.d(TAG, "Codex exited with code ${result.exitCode}, completed=${result.completed}")

                val changed = checkpoints.changedFiles(workspace, before)
                if (changed.isNotEmpty()) {
                    Log.d(TAG, "Changed files: ${changed.size}")
                    checkpoints.saveChangedPaths(projectId, changed)
                    val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId))
                    eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
                } else if (!File(checkpoints.checkpointDir(projectId), "changes.json").isFile) {
                    acceptLastChanges(projectId)
                }
                if (userStopRequested) throw CodexSessionException("Stopped by user")
                if (!result.completed) throw CodexSessionException(result.failure.ifBlank { "Codex stopped unexpectedly." })
                if (managedContext) {
                    check(!nativeProcess.isAlive) { "Codex must close before its context can be handed to another account." }
                    val releasesActiveGoal = handoff.goalOwner(handoffKey!!) != null && result.goalStatus in setOf("complete", "cleared")
                    if (nativeControl?.goalControl == true && !releasesActiveGoal) handoff.completeControl(handoffKey, owner!!.taskId, result.goalStatus)
                    else {
                        val id = result.threadId ?: error("Codex did not return its completed context thread.")
                        try {
                            handoff.complete(handoffKey, owner!!.taskId, account!!, id, handoff.findRollout(account, id).path, guestWorkspacePath, result.goalStatus)
                        } catch (_: IllegalArgumentException) {
                            handoff.releaseInterrupted(handoffKey, owner!!.taskId)
                            eventBus.emit(RuntimeEvent.RuntimeLog(sessionId, "Chat history retained",
                                "Codex finished, but its native context could not be saved for transfer. The next message will continue from saved chat history and the workspace."))
                        } catch (_: java.io.IOException) {
                            handoff.releaseInterrupted(handoffKey, owner!!.taskId)
                            eventBus.emit(RuntimeEvent.RuntimeLog(sessionId, "Chat history retained",
                                "Codex finished, but its native context could not be saved for transfer. The next message will continue from saved chat history and the workspace."))
                        }
                    }
                }
                if (result.goalStatus == "cleared") conversationScope?.let { prefs.clearCodexGoalThread(projectId, it) }
                if (nativeControl?.goalControl != true) result.threadId?.let { id -> conversationScope?.let { scope ->
                    prefs.saveAgentConversation(AgentKind.CODEX, projectId, scope, id)
                } }
                emitCompletedOnce(sessionId)
                finishForegroundRuntime(
                    completed = true,
                    projectName = projectSlug,
                    detail = "Codex finished the task in $projectSlug.",
                )
            }.onFailure { error ->
                Log.e(TAG, "Session failed", error)
                activeProcess?.let { if (it.isAlive) it.destroyForcibly() }
                val message = friendlyError(error)
                emitFailureOnce(sessionId, message)
                if (userStopRequested) {
                    cancelForegroundRuntime()
                } else {
                    finishForegroundRuntime(completed = false, projectName = projectSlug, detail = message)
                }
                if (!userStopRequested) throw error
            }
        } finally {
            // Runs on every outcome, including a throwing failure path, so no process or log leaks.
            cleanupSession(sessionId, outputFile)
            if (activeSessionId != sessionId) contextClaim?.let { (key, claimTask) ->
                CodexConversationHandoff(installer.rootfs).releaseInterrupted(key, claimTask)
            }
        }
        sessionId
    }

    /** Idempotent. Runs on every outcome so a failed turn never leaves a process or log behind. */
    private fun cleanupSession(sessionId: String, outputFile: File?) {
        val process = activeProcess
        val closed = process == null || closeAuthProcess(process)
        if (closed) {
            activeTaskId?.let { com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).unregisterStopHandler(it) }
            (process as? NativeSpawnProcess)?.processPid?.let { pid ->
                com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).healthMonitor.onTaskProcessClosed(pid)
            }
            runCatching { outputFile?.delete() }
            if (activeSessionId == sessionId) {
                activeProcess = null
                activeSessionId = null
                activeTaskId = null
                pendingGoalControl.set(null)
                RuntimeTaskController.stopAction = null
            }
        }
    }

    /** Controls the running owner only; no new task/thread is admitted. */
    internal fun requestGoalControl(action: CodexNativeControl, projectId: String, chatId: String): Boolean {
        if (!action.goalControl || activeProcess?.isAlive != true) return false
        val task = activeTaskId?.let { com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).stateStore.get(it) } ?: return false
        val operation = task.nativeControlJson?.let { runCatching { CodexNativeControl.decode(it) }.getOrNull() }
        if (operation?.kind !in setOf("goalSet", "goalResume")) return false
        return task.projectId == projectId && task.chatId == chatId && pendingGoalControl.compareAndSet(null, action)
    }

    internal suspend fun configurePlanMode(projectId: String, chatId: String, provider: ProviderProfile, accountId: String?, action: CodexNativeControl): String = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val accounts = CodexAccounts(installer.rootfs)
        val identity = if (provider.kind == com.jarves.mh.model.ProviderKind.CHATGPT) {
            val account = accountId?.let(accounts::requireUsable) ?: accounts.selected()
            "codex-account:${account.id}:${account.scope}"
        } else com.jarves.mh.data.ApiKeyVault(context).credentials(provider.secretId).firstOrNull { it.isActive }?.id.orEmpty()
        val scope = NativeConversationScope.key(chatId, workspace, provider, identity)
        val prefs = AppPreferences(context)
        val handoff = CodexConversationHandoff(installer.rootfs)
        val key = NativeConversationScope.key(chatId, workspace, provider, "codex-account-pool")
        val managed = provider.kind == com.jarves.mh.model.ProviderKind.CHATGPT && (accounts.balancingEnabled || handoff.hasState(key))
        when (action.kind) {
            "planOn" -> { prefs.saveCodexPlanMode(projectId, scope, true); if (managed) handoff.savePlanMode(key, "$projectId:$chatId", true) }
            "planOff" -> { prefs.saveCodexPlanMode(projectId, scope, false); if (managed) handoff.savePlanMode(key, "$projectId:$chatId", false) }
        }
        val enabled = if (managed && handoff.hasPlanMode(key)) handoff.planMode(key) else prefs.codexPlanMode(projectId, scope)
        "Native Codex Plan mode: ${if (enabled) "on" else "off"}."
    }

    private fun nativeImageData(workspace: File, guestWorkspace: String, guestPath: String): String {
        require(guestPath.startsWith("$guestWorkspace/${com.jarves.mh.model.ATTACHMENTS_DIRECTORY}/")) { "Image is outside this workspace." }
        val image = File(workspace, guestPath.removePrefix("$guestWorkspace/")).canonicalFile
        require(image.path.startsWith(workspace.canonicalPath + File.separator) && image.isFile && image.length() <= com.jarves.mh.model.MAX_ATTACHMENT_BYTES) { "Image is unavailable or exceeds the attachment limit." }
        val mime = when (image.extension.lowercase()) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> error("Unsupported native image type") }
        return "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(image.readBytes())
    }

    private suspend fun forward(mapper: CodexEventMapper, event: CodexEvent) {
        when (event) {
            is CodexEvent.Reasoning -> pushForegroundProgress("Thinking…")
            is CodexEvent.CommandStarted ->
                pushForegroundProgress("Running ${CodexEventMapper.displayCommand(event.command)}")
            is CodexEvent.AgentMessage -> pushForegroundProgress("Writing the answer…")
            else -> Unit
        }
        mapper.map(event).forEach { eventBus.emit(it) }
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // Native execution retains approval_policy=never. Unsupported server requests fail explicitly.
    }

    override suspend fun stopSession(sessionId: String, force: Boolean) = withContext(Dispatchers.IO) {
        if (activeSessionId != sessionId) return@withContext
        userStopRequested = true
        val resolvedTaskId: String? = runCatching {
            com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).getTaskIdForSession(sessionId)
        }.getOrNull() ?: activeTaskId
        if (resolvedTaskId != null) {
            stopState.requestStop(resolvedTaskId)
        }
        stopProcess(force)
        emitFailureOnce(sessionId, "Stopped by user")
    }

    override suspend fun stopActiveSession(force: Boolean) {
        userStopRequested = true
        val currentSessionId = activeSessionId
        if (currentSessionId != null) {
            stopSession(currentSessionId, force)
        } else {
            stopProcess(force)
        }
    }

    private suspend fun stopProcess(force: Boolean) {
        val proc = activeProcess ?: return
        if (force) {
            (proc as? NativeSpawnProcess)?.destroyForcibly() ?: proc.destroyForcibly()
        } else {
            // Give the runner a bounded chance to persist pause/interrupt via native RPC.
            delay(STOP_GRACE_MS)
            if (proc.isAlive) proc.destroyForcibly()
        }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        checkpoints.undoLastChanges(projectId)
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.checkpointDir(projectId).deleteRecursively()
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        checkpoints.undoFileChange(projectId, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        checkpoints.acceptFileChange(projectId, path)
    }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: "your project",
                detail = "Codex finished the task.",
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: "your project",
                    detail = reason,
                )
            }
        }
    }

    private fun friendlyError(error: Throwable): String = when (error) {
        is CodexSessionException, is CodexUnsupportedProviderException ->
            error.message.orEmpty().ifBlank { "Codex could not start." }
        else -> CodexFailureMessages.friendly(error.message.orEmpty())
    }

    private fun buildContextPrompt(
        currentPrompt: String,
        history: List<ChatMessage>,
        guestWorkspacePath: String,
        projectKind: ProjectKind,
        memory: ContextMemory = ContextMemory(""),
    ): String = CodexPromptNotes.withPhoneEnvironment(
        com.jarves.mh.data.PromptContextSupport.buildPrompt(
            currentPrompt = currentPrompt,
            history = history.dropLast(1), // the current prompt was just appended to history
            guestWorkspacePath = guestWorkspacePath,
            projectKind = projectKind,
            memory = memory,
            androidStackInstalled = installer.isStackInstalled(com.jarves.mh.model.DevStack.ANDROID),
        ),
    )

    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = taskStartedAtElapsedRealtime.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, activeProjectSlug)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, text),
            )
        }
    }

    private fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    private fun startForegroundRuntime(projectName: String, taskId: String? = null) {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                .apply { if (taskId != null) putExtra(RuntimeExecutionService.EXTRA_TASK_ID, taskId) },
        )
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        // Supervised tasks retain the service until verification/recovery reaches a final outcome.
        if (activeTaskId != null) return
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            RuntimeExecutionService.finish(
                context = context,
                title = if (completed) "Task completed" else "Task needs attention",
                detail = detail,
                failed = !completed,
                projectName = projectName,
            )
        }
    }

    private fun cancelForegroundRuntime() {
        if (activeTaskId != null) return
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            RuntimeExecutionService.cancel(context)
        }
    }

    private class CodexSessionException(message: String) : IllegalStateException(message)

    companion object {
        private const val TAG = "CodexBridge"
        private const val LAST_MESSAGE_PREFIX = "mh-codex-last-"
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val STOP_GRACE_MS = 7_000L

        /** `codex login` stores ChatGPT credentials in `$CODEX_HOME/auth.json` inside the guest. */
        internal fun hasChatGptCredentials(rootfs: File): Boolean {
            val auth = File(rootfs, CodexLaunchBuilder.CODEX_HOME_GUEST_PATH.removePrefix("/") + "/auth.json")
            return runCatching { auth.isFile && auth.length() > 2L }.getOrDefault(false)
        }
    }
}
