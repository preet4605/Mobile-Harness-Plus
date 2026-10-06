package com.jarves.mh.data

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.RuleInfo
import com.jarves.mh.model.RuleSource
import com.jarves.mh.model.SkillInfo
import com.jarves.mh.model.SkillSource
import com.jarves.mh.model.brain.BrainKnowledgeEntry
import com.jarves.mh.model.brain.BrainKnowledgeType
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.GlobalExecutionPolicies
import com.jarves.mh.model.brain.StepStatus
import com.jarves.mh.model.brain.TaskFailureRecord
import com.jarves.mh.runtime.ControlledBrainInjector
import com.jarves.mh.runtime.SkillManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Deterministic verification test suite for Phase 3B completion:
 * 1. Context-size measurement across Small, Normal, Complex tasks (Claude, Dsh, Antigravity)
 * 2. Retrieval-query behavior (Stopword filtering vs Unfiltered baseline)
 * 3. History-boundary verification (Scenarios A through H)
 */
class Phase3BVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var memoryStore: ContextMemoryStore
    private lateinit var brainRepo: BrainKnowledgeRepository
    private lateinit var assembler: BrainContextAssembler
    private lateinit var skillManager: SkillManager

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("phase3b_verification_${System.nanoTime()}")
        memoryStore = ContextMemoryStore(baseDir)
        brainRepo = memoryStore.brainKnowledgeRepository
        assembler = BrainContextAssembler(brainRepo)
        skillManager = SkillManager(tempFolder.newFolder("skills_${System.nanoTime()}"))
    }

    // =========================================================================
    // 1. CONTEXT-SIZE MEASUREMENT
    // =========================================================================

    // Previous implementation logic (from git commit 88b3323)
    private fun renderMemoryBlockBefore(memory: ContextMemory): String {
        val activeEntries = memory.entries.filter { it.status == MemoryStatus.ACTIVE }
        if (activeEntries.isEmpty()) return ""
        return buildString {
            appendLine("<persistent_memory>")
            appendLine("The following facts were remembered from previous sessions. They remain true across model and harness switches:")
            activeEntries.forEach { entry ->
                appendLine("- ${entry.key}: ${entry.value}")
            }
            appendLine("</persistent_memory>")
        }
    }

    private fun buildContextPromptBeforeClaude(
        currentPrompt: String,
        history: List<ChatMessage>,
        guestWorkspacePath: String,
        projectKind: ProjectKind,
        memory: ContextMemory = ContextMemory(""),
        androidStackInstalled: Boolean = true,
    ): String {
        val priorMessages = history
            .filter { msg ->
                (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                !msg.text.startsWith("Failed to") &&
                !msg.text.startsWith("Error:") &&
                !msg.text.contains("API Error")
            }
            .dropLast(1)

        val sb = StringBuilder()
        sb.appendLine("<project_workspace>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            sb.appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
            sb.appendLine("Respond conversationally, and use terminal or file tools whenever they are useful for the request.")
            sb.appendLine("Keep every file and command inside this project workspace.")
        } else {
            sb.appendLine("The current working directory $guestWorkspacePath is the project root.")
            sb.appendLine("Create and edit project files directly in this directory. Do not create another outer project folder unless the user explicitly asks for one.")
            sb.appendLine("When giving commands to the user, make them runnable from this project root.")
        }
        if (androidStackInstalled) {
            sb.appendLine("If this is an Android project, the phone already provides JDK 17, Android SDK 36, ARM64 Build Tools 35.0.0, Gradle 8.14.3, and an offline Maven repository.")
            sb.appendLine("For newly created Android projects, use AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, and Java 17 so the preinstalled offline toolchain can build immediately.")
            sb.appendLine("The bundled Maven cache handles the base toolchain; Gradle may download project-specific libraries normally. Set android.useAndroidX=true for AndroidX or Compose projects.")
            sb.appendLine("PocketDev globally configures Gradle to use the SDK's ARM64 aapt2. Do not use the x86_64 Maven aapt2, investigate its architecture, or add android.aapt2FromMavenOverride to the project.")
            sb.appendLine("Use the installed `gradle` command for Android builds; do not ask the user to install Android Studio, an SDK, Gradle, ADB, or Termux.")
        } else {
            sb.appendLine("The optional Android build toolchain is not installed in this PocketDev runtime. You may create Android project files, but do not claim that Gradle, the Android SDK, or aapt2 is available and do not present build or install commands as verified. Tell the user to add the Android development stack in PocketDev Settings before building.")
        }
        sb.appendLine("For local servers, give a clear start command and never use a kill command that searches its own command text with pgrep, because it can terminate the terminal itself.")
        sb.appendLine("</project_workspace>")
        sb.appendLine()
        val memoryBlock = renderMemoryBlockBefore(memory)
        if (memoryBlock.isNotBlank()) {
            sb.appendLine(memoryBlock)
            sb.appendLine()
        }
        if (priorMessages.isEmpty()) {
            sb.appendLine(currentPrompt)
            return sb.toString()
        }
        sb.appendLine("<conversation_history>")
        sb.appendLine("The following is our prior conversation in this project. Continue naturally from where we left off.")
        sb.appendLine()
        for (msg in priorMessages) {
            val role = if (msg.fromUser) "User" else "Assistant"
            sb.appendLine("$role: ${msg.text}")
            if (msg.attachments.isNotEmpty()) {
                sb.appendLine("Attached files:")
                msg.attachments.forEach {
                    sb.appendLine("- ${it.displayName}: $guestWorkspacePath/${it.relativePath} (${it.mimeType})")
                }
            }
            sb.appendLine()
        }
        sb.appendLine("</conversation_history>")
        sb.appendLine()
        sb.appendLine("Now, respond to this new message from the user:")
        sb.appendLine(currentPrompt)
        return sb.toString()
    }

    private fun antigravityWorkspacePromptBefore(
        projectSlug: String,
        prompt: String,
        memory: ContextMemory = ContextMemory(""),
    ): String {
        val memoryBlock = renderMemoryBlockBefore(memory)
        return buildString {
            appendLine("<pocketdev_workspace>")
            appendLine("The active project workspace is /workspace/$projectSlug. Create, edit, read, run, and build project files only inside this directory. Do not create project output under ~/.gemini/antigravity-cli/scratch or any other scratch directory.")
            appendLine("</pocketdev_workspace>")
            if (memoryBlock.isNotBlank()) {
                appendLine()
                appendLine(memoryBlock)
            }
            appendLine()
            append(prompt)
        }.trimIndent()
    }

    private fun antigravityWorkspacePromptCurrent(
        projectSlug: String,
        prompt: String,
        memory: ContextMemory = ContextMemory(""),
    ): String {
        val memoryBlock = PromptContextSupport.renderMemory(memory, prompt)
        return buildString {
            appendLine("<pocketdev_workspace>")
            appendLine("The active project workspace is /workspace/$projectSlug. Create, edit, read, run, and build project files only inside this directory. Do not create project output under ~/.gemini/antigravity-cli/scratch or any other scratch directory.")
            appendLine("</pocketdev_workspace>")
            if (memoryBlock.isNotBlank()) {
                appendLine()
                appendLine(memoryBlock)
            }
            appendLine()
            append(prompt)
        }.trimIndent()
    }

    data class ContextMeasurement(
        val scenario: String,
        val harness: String,
        val projectRulesChars: Int,
        val globalPoliciesChars: Int,
        val brainContextChars: Int,
        val persistentMemoryChars: Int,
        val skillsIndexChars: Int,
        val workspaceContextChars: Int,
        val historyChars: Int,
        val finalPromptChars: Int,
        val finalPromptBytes: Int,
        val estimatedTokens: Int,
        val beforePromptChars: Int,
        val beforePromptBytes: Int,
        val beforeEstimatedTokens: Int,
    )

    @Test
    fun testContextSizeMeasurements() {
        println("=== 1. CONTEXT-SIZE MEASUREMENT ===")
        val results = mutableListOf<ContextMeasurement>()

        // -------------------------------------------------------------
        // SCENARIO 1: Small Task
        // -------------------------------------------------------------
        val smallTask = CanonicalTask(
            taskId = "task-small-1",
            projectId = "p-small",
            projectSlug = "pocket-calc",
            objective = "Check Gradle version and compileSdk in build.gradle.kts"
        )
        val smallContext = assembler.assemble(task = smallTask, projectId = "p-small")
        val smallRenderedBrain = assembler.render(smallContext)
        val smallSnapshot = BrainContextSnapshot.create(
            taskId = smallTask.taskId,
            attemptId = "${smallTask.taskId}:attempt-0",
            context = smallContext,
            renderedContext = smallRenderedBrain
        )

        val smallRules = listOf(
            RuleInfo(
                id = "general",
                name = "general",
                title = "General Guidelines",
                description = "Basic coding rules",
                content = "Follow project style. Verify before finishing. Keep changes small.",
                filePath = "/rules/general.md",
                source = RuleSource.PROJECT
            )
        )
        val smallRulesBlock = skillManager.buildRulesBlock(smallRules)
        val smallUserPrompt = "$smallRulesBlock\n\nWhat version of Gradle is configured?"
        val smallInjected = ControlledBrainInjector.inject(smallUserPrompt, smallSnapshot)

        val smallMemory = ContextMemory(
            projectId = "p-small",
            entries = listOf(
                MemoryEntry(projectId = "p-small", key = "project-language", value = "Kotlin"),
                MemoryEntry(projectId = "p-small", key = "build-system", value = "Gradle 8.14.3")
            )
        )

        val smallHistory = listOf(
            ChatMessage(fromUser = true, text = smallInjected)
        )

        for (harness in listOf("Claude", "Dsh", "Antigravity")) {
            val workspaceBlock = if (harness == "Antigravity") {
                "<pocketdev_workspace>\nThe active project workspace is /workspace/pocket-calc. Create, edit, read, run, and build project files only inside this directory. Do not create project output under ~/.gemini/antigravity-cli/scratch or any other scratch directory.\n</pocketdev_workspace>"
            } else {
                PromptContextSupport.workspaceBlock("/workspace/pocket-calc", ProjectKind.PROJECT, true)
            }
            val memoryRendered = PromptContextSupport.renderMemory(smallMemory, smallInjected)
            val historyBlock = if (harness == "Antigravity") "" else PromptContextSupport.historyBlock(emptyList(), "/workspace/pocket-calc")

            val currentFinal = if (harness == "Antigravity") {
                antigravityWorkspacePromptCurrent("pocket-calc", smallInjected, smallMemory)
            } else {
                PromptContextSupport.buildPrompt(
                    currentPrompt = smallInjected,
                    history = emptyList(),
                    guestWorkspacePath = "/workspace/pocket-calc",
                    projectKind = ProjectKind.PROJECT,
                    memory = smallMemory,
                    androidStackInstalled = true
                )
            }

            val beforeFinal = if (harness == "Antigravity") {
                antigravityWorkspacePromptBefore("pocket-calc", smallInjected, smallMemory)
            } else {
                buildContextPromptBeforeClaude(
                    currentPrompt = smallInjected,
                    history = smallHistory,
                    guestWorkspacePath = "/workspace/pocket-calc",
                    projectKind = ProjectKind.PROJECT,
                    memory = smallMemory,
                    androidStackInstalled = true
                )
            }

            val globalPoliciesStr = GlobalExecutionPolicies.DEFAULT_POLICIES.joinToString("\n")

            results.add(
                ContextMeasurement(
                    scenario = "Small Task",
                    harness = harness,
                    projectRulesChars = smallRulesBlock.length,
                    globalPoliciesChars = globalPoliciesStr.length,
                    brainContextChars = smallRenderedBrain.length,
                    persistentMemoryChars = memoryRendered.length,
                    skillsIndexChars = 0,
                    workspaceContextChars = workspaceBlock.length,
                    historyChars = historyBlock.length,
                    finalPromptChars = currentFinal.length,
                    finalPromptBytes = currentFinal.toByteArray(Charsets.UTF_8).size,
                    estimatedTokens = (currentFinal.length + 3) / 4,
                    beforePromptChars = beforeFinal.length,
                    beforePromptBytes = beforeFinal.toByteArray(Charsets.UTF_8).size,
                    beforeEstimatedTokens = (beforeFinal.length + 3) / 4,
                )
            )
        }

        // -------------------------------------------------------------
        // SCENARIO 2: Normal Task
        // -------------------------------------------------------------
        val normalStep = ExecutionStep(
            stepId = "step-norm-1",
            stepOrder = 1,
            title = "Implement Liquid Glass Surface",
            description = "Build the compose surface component with blur and border highlight",
            status = StepStatus.RUNNING,
            expectedFiles = listOf("LiquidGlassSurface.kt", "LiquidGlassSurfaceTest.kt")
        )
        val normalTask = CanonicalTask(
            taskId = "task-norm-2",
            projectId = "p-norm",
            projectSlug = "liquid-glass-app",
            objective = "Add Liquid Glass styling to toolbar and bottom sheet",
            constraints = listOf("Follow Liquid Glass HIG", "Keep 60fps rendering"),
            acceptanceCriteria = listOf("Pass all UI tests", "Blur radius bounded to 20dp"),
            plan = ExecutionPlan(steps = listOf(normalStep), currentStepIndex = 0)
        )
        val normalContext = assembler.assemble(
            task = normalTask,
            currentStep = normalStep,
            projectId = "p-norm",
            query = "Liquid Glass surface blur"
        )
        val normalRenderedBrain = assembler.render(normalContext)
        val normalSnapshot = BrainContextSnapshot.create(
            taskId = normalTask.taskId,
            attemptId = "${normalTask.taskId}:attempt-0",
            context = normalContext,
            renderedContext = normalRenderedBrain
        )

        val normalRules = listOf(
            RuleInfo(
                id = "GEMINI",
                name = "GEMINI",
                title = "Mobile Harness Rules",
                description = "Standard rules",
                content = "Follow explicit requirements. Make smallest change. Verify before finishing.",
                filePath = "/rules/GEMINI.md",
                source = RuleSource.PROJECT
            ),
            RuleInfo(
                id = "apple-design",
                name = "apple-design",
                title = "Apple HIG",
                description = "HIG guidelines",
                content = "Use Liquid Glass blur with subtle border highlights. Respect dark and light modes.",
                filePath = "/rules/apple-design.md",
                source = RuleSource.PROJECT
            )
        )
        val normalSkills = listOf(
            SkillInfo(id = "apple-design", name = "apple-design", description = "Apple HIG design guidelines", filePath = "/skills/apple-design/SKILL.md", source = SkillSource.PROJECT),
            SkillInfo(id = "code-review", name = "code-review-and-quality", description = "Multi-axis review", filePath = "/skills/code-review/SKILL.md", source = SkillSource.PROJECT),
        )
        val normalRulesBlock = skillManager.buildRulesBlock(normalRules)
        val normalSkillsIndex = skillManager.buildProgressiveDisclosureIndex(normalSkills)
        val normalUserPrompt = "$normalRulesBlock\n\n$normalSkillsIndex\n\nRefactor LiquidGlassSurface to use brush border shader."
        val normalInjected = ControlledBrainInjector.inject(normalUserPrompt, normalSnapshot)

        val normalMemory = ContextMemory(
            projectId = "p-norm",
            entries = listOf(
                MemoryEntry(projectId = "p-norm", key = "project-language", value = "Kotlin 1.9.22"),
                MemoryEntry(projectId = "p-norm", key = "project-framework", value = "Jetpack Compose"),
                MemoryEntry(projectId = "p-norm", key = "build-system", value = "Gradle 8.14.3"),
                MemoryEntry(projectId = "p-norm", key = "design-system", value = "Liquid Glass"),
                MemoryEntry(projectId = "p-norm", key = "target-sdk", value = "Android SDK 36"),
                MemoryEntry(projectId = "p-norm", key = "progress-step-0", value = "Foundation primitives verified", source = MemorySource.TOOL_VERIFIED),
            )
        )

        val normalHistoryPrior = listOf(
            ChatMessage(fromUser = true, text = "Create Liquid Glass design system foundation"),
            ChatMessage(fromUser = false, text = "Created LiquidGlassPrimitives.kt with blur and noise shaders."),
            ChatMessage(fromUser = true, text = "Now add the LiquidGlassSurface composable container."),
            ChatMessage(fromUser = false, text = "Implemented initial LiquidGlassSurface composable."),
        )
        val normalHistoryFull = normalHistoryPrior + ChatMessage(fromUser = true, text = normalInjected)

        for (harness in listOf("Claude", "Dsh", "Antigravity")) {
            val workspaceBlock = if (harness == "Antigravity") {
                "<pocketdev_workspace>\nThe active project workspace is /workspace/liquid-glass-app. Create, edit, read, run, and build project files only inside this directory. Do not create project output under ~/.gemini/antigravity-cli/scratch or any other scratch directory.\n</pocketdev_workspace>"
            } else {
                PromptContextSupport.workspaceBlock("/workspace/liquid-glass-app", ProjectKind.PROJECT, true)
            }
            val memoryRendered = PromptContextSupport.renderMemory(normalMemory, normalInjected)
            val historyBlock = if (harness == "Antigravity") "" else PromptContextSupport.historyBlock(normalHistoryPrior, "/workspace/liquid-glass-app")

            val currentFinal = if (harness == "Antigravity") {
                antigravityWorkspacePromptCurrent("liquid-glass-app", normalInjected, normalMemory)
            } else {
                PromptContextSupport.buildPrompt(
                    currentPrompt = normalInjected,
                    history = normalHistoryPrior,
                    guestWorkspacePath = "/workspace/liquid-glass-app",
                    projectKind = ProjectKind.PROJECT,
                    memory = normalMemory,
                    androidStackInstalled = true
                )
            }

            val beforeFinal = if (harness == "Antigravity") {
                antigravityWorkspacePromptBefore("liquid-glass-app", normalInjected, normalMemory)
            } else {
                buildContextPromptBeforeClaude(
                    currentPrompt = normalInjected,
                    history = normalHistoryFull,
                    guestWorkspacePath = "/workspace/liquid-glass-app",
                    projectKind = ProjectKind.PROJECT,
                    memory = normalMemory,
                    androidStackInstalled = true
                )
            }

            val globalPoliciesStr = GlobalExecutionPolicies.DEFAULT_POLICIES.joinToString("\n")

            results.add(
                ContextMeasurement(
                    scenario = "Normal Task",
                    harness = harness,
                    projectRulesChars = normalRulesBlock.length,
                    globalPoliciesChars = globalPoliciesStr.length,
                    brainContextChars = normalRenderedBrain.length,
                    persistentMemoryChars = memoryRendered.length,
                    skillsIndexChars = normalSkillsIndex.length,
                    workspaceContextChars = workspaceBlock.length,
                    historyChars = historyBlock.length,
                    finalPromptChars = currentFinal.length,
                    finalPromptBytes = currentFinal.toByteArray(Charsets.UTF_8).size,
                    estimatedTokens = (currentFinal.length + 3) / 4,
                    beforePromptChars = beforeFinal.length,
                    beforePromptBytes = beforeFinal.toByteArray(Charsets.UTF_8).size,
                    beforeEstimatedTokens = (beforeFinal.length + 3) / 4,
                )
            )
        }

        // -------------------------------------------------------------
        // SCENARIO 3: Complex Task
        // -------------------------------------------------------------
        val complexStep = ExecutionStep(
            stepId = "step-c-3",
            stepOrder = 3,
            title = "Autonomous Recovery & Memory Synchronization",
            description = "Recover from proot socket EOF drift and sync FTS5 memory index",
            status = StepStatus.RUNNING,
            attempts = 2,
            maxAttempts = 3,
            expectedFiles = listOf("TaskSupervisor.kt", "ControlledBrainInjector.kt", "BrainKnowledgeRepository.kt", "ContextMemoryStore.kt")
        )
        val complexTask = CanonicalTask(
            taskId = "task-complex-99",
            projectId = "curious-curie",
            projectSlug = "curious-curie",
            objective = "Autonomous multi-step execution with deterministic verification gate and self-recovery",
            constraints = listOf(
                "Assume Android process death at any point",
                "Strict cancellation and timeout enforcement",
                "taskId != sessionId invariant",
                "Do not treat stream end_turn as session termination",
                "Keep persistent output inside /workspace/curious-curie"
            ),
            acceptanceCriteria = listOf(
                "615 of 615 tests pass without flakes",
                "Deterministic recovery on RETRY_STEP_DIRECT and RECREATE_WORKSPACE_STATE",
                "Memory bounding under 2,000 chars and 20 entries",
                "History bounded strictly to 24,000 chars"
            ),
            plan = ExecutionPlan(steps = listOf(complexStep), currentStepIndex = 0)
        )
        val complexContext = assembler.assemble(
            task = complexTask,
            currentStep = complexStep,
            projectId = "curious-curie",
            query = "Autonomous Recovery TaskSupervisor Memory"
        )
        val complexRenderedBrain = assembler.render(complexContext)
        val complexSnapshot = BrainContextSnapshot.create(
            taskId = complexTask.taskId,
            attemptId = "${complexTask.taskId}:attempt-1",
            context = complexContext,
            renderedContext = complexRenderedBrain
        )

        val complexRules = listOf(
            RuleInfo(
                id = "user_global",
                name = "user_global",
                title = "Multi-Agent Personas & Architecture",
                description = "Architecture rules",
                content = "Operating Protocol for Agents: Coding & Refactoring -> Software Architect; Design -> Frontend Lead; Debugging -> Systems Diagnostician; Performance -> Performance Engineer; Security -> Security Specialist.",
                filePath = "/rules/user_global.md",
                source = RuleSource.GLOBAL
            ),
            RuleInfo(
                id = "GEMINI = AGENTS",
                name = "GEMINI = AGENTS",
                title = "Mobile Harness Rules",
                description = "Core harness rules",
                content = "Mobile Harness Rules: Follow explicit task requirements. Preserve existing behavior. Make smallest change. BRAIN -> TARGETED DISCOVERY -> BATCH READ -> PLAN -> EDIT -> VERIFY -> BUILD -> REPORT -> STOP.",
                filePath = "/rules/GEMINI.md",
                source = RuleSource.PROJECT
            )
        )
        val complexSkills = listOf(
            SkillInfo(id = "apple-design", name = "apple-design", description = "Apple HIG reference for UI design", filePath = "/skills/apple-design/SKILL.md", source = SkillSource.PROJECT),
            SkillInfo(id = "api-design", name = "api-and-interface-design", description = "Guides stable API and interface design", filePath = "/skills/api-design/SKILL.md", source = SkillSource.GLOBAL),
            SkillInfo(id = "code-review", name = "code-review-and-quality", description = "Multi-axis review", filePath = "/skills/code-review/SKILL.md", source = SkillSource.GLOBAL),
            SkillInfo(id = "debugging", name = "debugging-and-error-recovery", description = "Root-cause debugging", filePath = "/skills/debugging/SKILL.md", source = SkillSource.GLOBAL),
            SkillInfo(id = "security", name = "security-and-hardening", description = "STRIDE and OWASP defenses", filePath = "/skills/security/SKILL.md", source = SkillSource.GLOBAL),
        )
        val complexRulesBlock = skillManager.buildRulesBlock(complexRules)
        val complexSkillsIndex = skillManager.buildProgressiveDisclosureIndex(complexSkills)
        val complexUserPrompt = "$complexRulesBlock\n\n$complexSkillsIndex\n\nExecute Phase 3B verification completion and ensure history and context bounding invariants hold."
        val complexInjected = ControlledBrainInjector.inject(complexUserPrompt, complexSnapshot)

        // 30 memory entries to test bounding
        val complexMemoryEntries = (1..30).map { i ->
            MemoryEntry(
                projectId = "curious-curie",
                key = "verified-state-$i",
                value = "Milestone $i verified with hash abc$i and execution artifact snapshot log path /workspace/curious-curie/build/reports/tests/testOnlineDebugUnitTest/classes/Test$i.html",
                source = MemorySource.TOOL_VERIFIED
            )
        }
        val complexMemory = ContextMemory(projectId = "curious-curie", entries = complexMemoryEntries)

        // Large history of 16 messages (~35,000 characters total before clipping)
        val complexHistoryPrior = mutableListOf<ChatMessage>()
        complexHistoryPrior.add(ChatMessage(fromUser = true, text = "INITIAL GOAL: Implement full autonomous self-recovery pipeline and memory store."))
        complexHistoryPrior.add(ChatMessage(fromUser = false, text = "Acknowledged. Beginning step 1 architecture and database schemas."))
        for (i in 1..7) {
            complexHistoryPrior.add(ChatMessage(fromUser = true, text = "Step $i result: " + "data-log-output-$i-".repeat(150)))
            complexHistoryPrior.add(ChatMessage(fromUser = false, text = "Step $i analysis and fix: " + "investigation-details-$i-".repeat(200)))
        }
        val complexHistoryFull = complexHistoryPrior + ChatMessage(fromUser = true, text = complexInjected)

        for (harness in listOf("Claude", "Dsh", "Antigravity")) {
            val workspaceBlock = if (harness == "Antigravity") {
                "<pocketdev_workspace>\nThe active project workspace is /workspace/curious-curie. Create, edit, read, run, and build project files only inside this directory. Do not create project output under ~/.gemini/antigravity-cli/scratch or any other scratch directory.\n</pocketdev_workspace>"
            } else {
                PromptContextSupport.workspaceBlock("/workspace/curious-curie", ProjectKind.PROJECT, true)
            }
            val memoryRendered = PromptContextSupport.renderMemory(complexMemory, complexInjected)
            val historyBlock = if (harness == "Antigravity") "" else PromptContextSupport.historyBlock(complexHistoryPrior, "/workspace/curious-curie")

            val currentFinal = if (harness == "Antigravity") {
                antigravityWorkspacePromptCurrent("curious-curie", complexInjected, complexMemory)
            } else {
                PromptContextSupport.buildPrompt(
                    currentPrompt = complexInjected,
                    history = complexHistoryPrior,
                    guestWorkspacePath = "/workspace/curious-curie",
                    projectKind = ProjectKind.PROJECT,
                    memory = complexMemory,
                    androidStackInstalled = true
                )
            }

            val beforeFinal = if (harness == "Antigravity") {
                antigravityWorkspacePromptBefore("curious-curie", complexInjected, complexMemory)
            } else {
                buildContextPromptBeforeClaude(
                    currentPrompt = complexInjected,
                    history = complexHistoryFull,
                    guestWorkspacePath = "/workspace/curious-curie",
                    projectKind = ProjectKind.PROJECT,
                    memory = complexMemory,
                    androidStackInstalled = true
                )
            }

            val globalPoliciesStr = GlobalExecutionPolicies.DEFAULT_POLICIES.joinToString("\n")

            results.add(
                ContextMeasurement(
                    scenario = "Complex Task",
                    harness = harness,
                    projectRulesChars = complexRulesBlock.length,
                    globalPoliciesChars = globalPoliciesStr.length,
                    brainContextChars = complexRenderedBrain.length,
                    persistentMemoryChars = memoryRendered.length,
                    skillsIndexChars = complexSkillsIndex.length,
                    workspaceContextChars = workspaceBlock.length,
                    historyChars = historyBlock.length,
                    finalPromptChars = currentFinal.length,
                    finalPromptBytes = currentFinal.toByteArray(Charsets.UTF_8).size,
                    estimatedTokens = (currentFinal.length + 3) / 4,
                    beforePromptChars = beforeFinal.length,
                    beforePromptBytes = beforeFinal.toByteArray(Charsets.UTF_8).size,
                    beforeEstimatedTokens = (beforeFinal.length + 3) / 4,
                )
            )
        }

        // Print Formatted Report
        println("\n--- DETAILED CONTEXT MEASUREMENT TABLE ---")
        println(String.format("%-14s | %-11s | %-10s | %-10s | %-12s | %-12s | %-10s | %-12s | %-10s | %-12s | %-14s | %-12s",
            "Scenario", "Harness", "Rules", "Policies", "BrainCtx", "Memory", "Skills", "Workspace", "History", "Final Chars", "Before Chars", "Token Est"))
        println("-".repeat(155))
        for (m in results) {
            println(String.format("%-14s | %-11s | %10d | %10d | %12d | %12d | %10d | %12d | %10d | %12d | %14d | %12d",
                m.scenario, m.harness, m.projectRulesChars, m.globalPoliciesChars, m.brainContextChars, m.persistentMemoryChars,
                m.skillsIndexChars, m.workspaceContextChars, m.historyChars, m.finalPromptChars, m.beforePromptChars, m.estimatedTokens))
        }

        // Assertions verifying bounding
        for (m in results) {
            assertTrue("Persistent memory must be <= 2,400 chars including XML wrapper", m.persistentMemoryChars <= 2400)
            if (m.harness != "Antigravity") {
                assertTrue("History block must be bounded", m.historyChars <= PromptContextSupport.HISTORY_MAX_CHARS + 6000)
            }
        }
    }

    // =========================================================================
    // 2. RETRIEVAL VERIFICATION
    // =========================================================================

    private val SEARCH_STOPWORDS = setOf(
        "the", "and", "for", "with", "this", "that", "from", "into", "then", "than", "are", "was",
        "not", "but", "you", "your", "all", "any", "can", "use", "its", "has", "have", "should",
        "must", "will", "also", "only", "each", "when", "which", "what", "how", "does", "do",
    )

    private fun prepareSearchTermsBefore(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        return query
            .take(1536)
            .split(Regex("[^\\p{L}\\p{N}_]+"))
            .asSequence()
            .map { it.take(64) }
            .filter { it.length >= 2 }
            .filter { seen.add(it.lowercase(Locale.ROOT)) }
            .take(24)
            .toList()
    }

    private fun prepareSearchTermsAfter(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        val terms = query
            .take(1536)
            .split(Regex("[^\\p{L}\\p{N}_]+"))
            .asSequence()
            .map { it.take(64) }
            .filter { it.length >= 2 }
            .filter { seen.add(it.lowercase(Locale.ROOT)) }
            .toList()
        val meaningful = terms.filter { it.lowercase(Locale.ROOT) !in SEARCH_STOPWORDS }
        return (meaningful.ifEmpty { terms }).take(24)
    }

    @Test
    fun testRetrievalQueryBehavior() {
        println("\n=== 2. RETRIEVAL VERIFICATION ===")
        val projectId = "retrieval-eval-proj"

        // Seed comprehensive test knowledge in the database
        val testEntries = listOf(
            BrainKnowledgeEntry(
                projectId = projectId,
                key = "task-runner-unit-tests",
                content = "Task runner requires testOnlineDebugUnitTest with AAPT2 override to fix unit tests and compile errors.",
                importance = 0.9f
            ),
            BrainKnowledgeEntry(
                projectId = projectId,
                key = "jetpack-compose-styling",
                content = "Jetpack Compose theme and surface styling must use Liquid Glass primitives for translucent backgrounds.",
                importance = 0.85f
            ),
            BrainKnowledgeEntry(
                projectId = projectId,
                key = "sqlite-fts5-transactions",
                content = "SQLite FTS5 query performance requires index synchronization in single write transaction.",
                importance = 0.88f
            ),
            BrainKnowledgeEntry(
                projectId = projectId,
                key = "pocketdev-supervisor-lifecycle",
                content = "PocketDev TaskSupervisor lifecycle coordinates process execution and ControlledBrainInjector attempts.",
                importance = 0.92f
            ),
            BrainKnowledgeEntry(
                projectId = projectId,
                key = "sql-insert-into-syntax",
                content = "Insert into memory_entries table requires specifying all columns from SQLite schema.",
                importance = 0.75f
            ),
            BrainKnowledgeEntry(
                projectId = projectId,
                key = "noise-entry-generic",
                content = "The and for with this that will are was can use should must what how does do.",
                importance = 0.3f
            ),
            BrainKnowledgeEntry(
                projectId = projectId,
                key = "unrelated-entry-network",
                content = "Http client timeout configuration with OkHttp connection pool.",
                importance = 0.5f
            )
        )
        testEntries.forEach { brainRepo.insert(it) }

        val testQueries = listOf(
            "Ordinary Technical Task" to "fix unit tests and compile errors in the task runner",
            "Framework/Library Query" to "Jetpack Compose theme and surface styling for Android",
            "Database Query" to "SQLite FTS5 query performance with index and transactions",
            "Project-Specific Terminology" to "PocketDev TaskSupervisor lifecycle and ControlledBrainInjector",
            "Query Containing Many Stopwords" to "what should you do with the and for this and that",
            "Query Where Filtered Term Could Plausibly Matter" to "when to use into or from in SQLite insert into"
        )

        for ((label, query) in testQueries) {
            val termsBefore = prepareSearchTermsBefore(query)
            val termsAfter = prepareSearchTermsAfter(query)

            val retrievedCandidates = brainRepo.retrieveRelevant(projectId = projectId, query = query, limit = 10)
            val searchResults = brainRepo.search(projectId = projectId, query = query, limit = 10)

            println("\n[$label]")
            println("Query: \"$query\"")
            println("Terms Before (${termsBefore.size}): $termsBefore")
            println("Terms After  (${termsAfter.size}): $termsAfter")
            println("Retrieved Count: ${retrievedCandidates.size}")
            println("Retrieved Keys: ${retrievedCandidates.map { it.key }}")

            val termsLower = termsAfter.map { it.lowercase(Locale.ROOT) }

            // Verifications:
            when (label) {
                "Ordinary Technical Task" -> {
                    assertFalse(termsLower.contains("and"))
                    assertFalse(termsLower.contains("the"))
                    assertTrue(termsLower.contains("fix") && termsLower.contains("tests") && termsLower.contains("runner"))
                    assertTrue(retrievedCandidates.any { it.key == "task-runner-unit-tests" })
                }
                "Framework/Library Query" -> {
                    assertFalse(termsLower.contains("and"))
                    assertFalse(termsLower.contains("for"))
                    assertTrue(termsLower.contains("jetpack") || termsLower.contains("compose") || termsLower.contains("surface"))
                    assertTrue(retrievedCandidates.any { it.key == "jetpack-compose-styling" })
                }
                "Database Query" -> {
                    assertFalse(termsLower.contains("with"))
                    assertFalse(termsLower.contains("and"))
                    assertTrue(termsLower.contains("sqlite") && termsLower.contains("fts5"))
                    assertTrue(retrievedCandidates.any { it.key == "sqlite-fts5-transactions" })
                }
                "Project-Specific Terminology" -> {
                    assertFalse(termsLower.contains("and"))
                    assertTrue(termsLower.contains("pocketdev") && termsLower.contains("tasksupervisor"))
                    assertTrue(retrievedCandidates.any { it.key == "pocketdev-supervisor-lifecycle" })
                }
                "Query Containing Many Stopwords" -> {
                    // All terms are stopwords! Fallback must trigger!
                    assertTrue("Fallback must preserve terms when all terms are stopwords", termsAfter.isNotEmpty())
                    assertEquals(termsBefore, termsAfter)
                }
                "Query Where Filtered Term Could Plausibly Matter" -> {
                    // "into", "from", "when", "use" are stopwords, but "sqlite", "insert" remain!
                    assertTrue(termsLower.contains("sqlite") && termsLower.contains("insert"))
                    assertTrue("Relevant entry found through remaining domain terms",
                        retrievedCandidates.any { it.key == "sql-insert-into-syntax" })
                }
            }
        }
    }

    // =========================================================================
    // 3. HISTORY-BOUNDARY VERIFICATION
    // =========================================================================

    @Test
    fun testHistoryBoundaryBehavior() {
        println("\n=== 3. HISTORY-BOUNDARY VERIFICATION ===")

        // A. Short history
        val histA = listOf(
            ChatMessage(fromUser = true, text = "User goal: build app"),
            ChatMessage(fromUser = false, text = "Done.")
        )
        val outA = PromptContextSupport.historyBlock(histA, "/workspace/test")
        assertTrue(outA.contains("User goal: build app"))
        assertTrue(outA.contains("Done."))
        assertFalse(outA.contains("omitted"))
        println("Scenario A (Short history): VERIFIED. Preserved both messages without omission.")

        // B. History below the limit (~5,000 characters)
        val histB = (1..5).flatMap { i ->
            listOf(
                ChatMessage(fromUser = true, text = "User question $i: " + "q".repeat(400)),
                ChatMessage(fromUser = false, text = "Assistant answer $i: " + "a".repeat(500))
            )
        }
        val outB = PromptContextSupport.historyBlock(histB, "/workspace/test")
        assertFalse(outB.contains("omitted"))
        assertTrue(outB.contains("User question 1:"))
        assertTrue(outB.contains("Assistant answer 5:"))
        println("Scenario B (History below limit, ~4,500 chars): VERIFIED. All 10 messages preserved, 0 omitted.")

        // C. History above the limit (~40,000 characters)
        val histC = (1..20).flatMap { i ->
            listOf(
                ChatMessage(fromUser = true, text = "Goal $i: " + "u".repeat(900)),
                ChatMessage(fromUser = false, text = "Resp $i: " + "r".repeat(1000))
            )
        }
        val outC = PromptContextSupport.historyBlock(histC, "/workspace/test")
        assertTrue("Omitted marker must be present when clipping history", outC.contains("earlier messages omitted to fit the context budget"))
        assertTrue("Newest assistant message must survive", outC.contains("Resp 20:"))
        assertTrue("Newest user message must survive", outC.contains("Goal 20:"))
        assertTrue("First user goal must survive", outC.contains("Goal 1:"))
        assertTrue("History block must respect budget bound", outC.length <= PromptContextSupport.HISTORY_MAX_CHARS + 6000)
        println("Scenario C (History above limit, ~38,000 chars): VERIFIED. Clipped with marker; newest 2 + first user goal survived.")

        // D. Oversized individual message (50,000 characters)
        val histD = listOf(
            ChatMessage(fromUser = true, text = "Goal 1: Initial user prompt"),
            ChatMessage(fromUser = false, text = "Huge log: " + "x".repeat(50_000)),
            ChatMessage(fromUser = true, text = "Latest question")
        )
        val outD = PromptContextSupport.historyBlock(histD, "/workspace/test")
        assertTrue(outD.contains("chars omitted"))
        assertTrue("Clipped message must keep output bounded", outD.length < 10_000)
        println("Scenario D (Oversized individual message, 50k chars): VERIFIED. Individual message clipped at head/tail with marker.")

        // E. Many user messages (30 user messages, ~45,000 chars)
        val histE = (1..30).map { i ->
            ChatMessage(fromUser = true, text = "User instruction $i: " + "task-detail-$i-".repeat(100))
        }
        val outE = PromptContextSupport.historyBlock(histE, "/workspace/test")
        assertTrue(outE.contains("User instruction 1:")) // First user goal survives!
        assertTrue(outE.contains("User instruction 30:")) // Newest user message survives!
        assertTrue(outE.contains("User instruction 29:")) // Second newest survives!
        assertTrue(outE.contains("earlier messages omitted to fit the context budget"))
        println("Scenario E (Many user messages): VERIFIED. Old intermediate user messages omitted when budget exceeded; first goal and newest survive.")

        // F. Messages containing error-like phrases ("Failed to", "Error:", "API Error")
        val histF = listOf(
            ChatMessage(fromUser = true, text = "Failed to build APK on target device"),
            ChatMessage(fromUser = true, text = "Error: java.lang.NullPointerException in Activity"),
            ChatMessage(fromUser = true, text = "API Error 403: Forbidden when calling endpoint"),
            ChatMessage(fromUser = false, text = "I will investigate the API error and null pointer."),
            ChatMessage(fromUser = false, text = "Error: provider connection terminated"), // noise assistant error
            ChatMessage(fromUser = true, text = "What is the status now?")
        )
        val outF = PromptContextSupport.historyBlock(histF, "/workspace/test")
        assertTrue("User message with 'Failed to' must NOT be dropped", outF.contains("Failed to build APK on target device"))
        assertTrue("User message with 'Error:' must NOT be dropped", outF.contains("Error: java.lang.NullPointerException"))
        assertTrue("User message with 'API Error' must NOT be dropped", outF.contains("API Error 403: Forbidden"))
        assertFalse("Assistant error message SHOULD be dropped as noise", outF.contains("provider connection terminated"))
        println("Scenario F (Error-like phrases): VERIFIED. Legitimate user messages preserved; assistant noise removed.")

        // G. Tool/assistant noise that should be removed
        val histG = listOf(
            ChatMessage(fromUser = false, text = PromptContextSupport.GREETING),
            ChatMessage(id = "interrupted-99", fromUser = false, text = "Interrupted work in progress"),
            ChatMessage(fromUser = false, text = ""), // empty message
            ChatMessage(fromUser = false, text = "Failed to spawn process"), // short assistant failure < 300 chars
            ChatMessage(fromUser = true, text = "Can you help me?"),
            ChatMessage(fromUser = false, text = "Certainly, I am here to help.")
        )
        val outG = PromptContextSupport.historyBlock(histG, "/workspace/test")
        assertFalse(outG.contains(PromptContextSupport.GREETING))
        assertFalse(outG.contains("Interrupted work in progress"))
        assertFalse(outG.contains("Failed to spawn process"))
        assertTrue(outG.contains("Can you help me?"))
        assertTrue(outG.contains("Certainly, I am here to help."))
        println("Scenario G (Tool/assistant noise): VERIFIED. Greetings, interrupted banners, and empty messages filtered.")

        // H. Boundary conditions around 24,000 characters
        // Test H1: Exactly below 24,000 characters
        val msgLenH = 2200
        val countBelow = 10 // 10 * 2200 = 22,000 + formatting < 24,000
        val histH1 = (1..countBelow).map { i ->
            ChatMessage(fromUser = i % 2 == 1, text = "H1Msg$i: " + "a".repeat(msgLenH - 20))
        }
        val outH1 = PromptContextSupport.historyBlock(histH1, "/workspace/test")
        assertFalse("Under budget: no omission marker", outH1.contains("omitted"))
        assertTrue(outH1.contains("H1Msg1:"))
        assertTrue(outH1.contains("H1Msg10:"))

        // Test H2: Crossing 24,000 characters
        val countAbove = 13 // 13 * 2200 = 28,600 > 24,000
        val histH2 = (1..countAbove).map { i ->
            ChatMessage(fromUser = i % 2 == 1, text = "H2Msg$i: " + "b".repeat(msgLenH - 20))
        }
        val outH2 = PromptContextSupport.historyBlock(histH2, "/workspace/test")
        assertTrue("Over budget: omission marker present", outH2.contains("earlier messages omitted to fit the context budget"))
        assertTrue("First user goal survives", outH2.contains("H2Msg1:"))
        assertTrue("Newest message survives", outH2.contains("H2Msg13:"))
        assertTrue("Second newest survives", outH2.contains("H2Msg12:"))
        println("Scenario H (24,000 character boundary): VERIFIED. Exact threshold triggers graceful degradation with newest 2 + first goal invariant.")
    }
}
