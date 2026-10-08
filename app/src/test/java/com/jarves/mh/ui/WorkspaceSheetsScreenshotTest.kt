package com.jarves.mh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.jarves.mh.data.ContextMemory
import com.jarves.mh.data.MemoryEntry
import com.jarves.mh.data.MemorySource
import com.jarves.mh.model.ArtifactInfo
import com.jarves.mh.model.BackgroundTaskInfo
import com.jarves.mh.model.BackgroundTaskStatus
import com.jarves.mh.model.CustomizationScopeMode
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectCustomizationConfig
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.RuleInfo
import com.jarves.mh.model.RuleSource
import com.jarves.mh.model.ScheduledTimerInfo
import com.jarves.mh.model.SessionTokenMetrics
import com.jarves.mh.model.SkillInfo
import com.jarves.mh.model.SkillSource
import com.jarves.mh.model.SubagentInfo
import com.jarves.mh.model.SubagentState
import com.jarves.mh.model.WorkspaceEntry
import com.jarves.mh.runtime.SlashCommandEngine
import com.jarves.mh.ui.kit.BannerHost
import com.jarves.mh.ui.kit.BannerState
import com.jarves.mh.ui.kit.OverlayHost
import com.jarves.mh.ui.theme.AppThemeMode
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketTheme
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.glass.LiquidGlassConfig
import com.jarves.mh.ui.theme.glass.LiquidGlassHost
import com.jarves.mh.ui.theme.glass.LiquidGlassLayers
import com.jarves.mh.ui.theme.glass.LiquidGlassSurface
import com.jarves.mh.ui.theme.glass.hostBackdropSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Opt-in renders of the workspace sheets and composer suggestions:
 * ./gradlew :app:testOnlineDebugUnitTest -Pscreenshots --tests 'com.jarves.mh.ui.WorkspaceSheetsScreenshotTest'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class WorkspaceSheetsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            PocketTheme(if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                LiquidGlassHost(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    config = LiquidGlassConfig(),
                ) {
                    val banner = remember { BannerState() }
                    BannerHost(banner, Modifier.fillMaxSize()) {
                        OverlayHost(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize()) {
                                ChatBackdrop()
                                content()
                            }
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun capture(name: String, dark: Boolean) {
        captureScreenRoboImage("${System.getProperty("roborazzi.output.dir")}/workspace-$name-${if (dark) "dark" else "light"}.png")
    }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        render(dark, content)
        capture(name, dark)
    }

    /** Drags a sheet up by its title so it rests at the large detent. */
    private fun expandSheet(title: String) {
        compose.onNodeWithText(title).performTouchInput {
            swipe(start = center, end = Offset(center.x, center.y - 1_600f), durationMillis = 200)
        }
        settle()
    }

    // ---- Sample data ------------------------------------------------------------------------

    private val subagents = listOf(
        SubagentInfo(
            conversationId = "a41f9c2e7d",
            role = "Explorer",
            typeName = "general-purpose",
            state = SubagentState.RUNNING,
            currentActivity = "Reading ChatTab and the floating composer stack",
        ),
        SubagentInfo(
            conversationId = "b7720d11ce",
            role = "Test writer",
            typeName = "code",
            state = SubagentState.WAITING_FOR_INPUT,
            currentActivity = "Which test runner should the new tests use?",
        ),
        SubagentInfo(
            conversationId = "c93e04aa51",
            role = "Reviewer",
            typeName = "review",
            state = SubagentState.DONE,
            currentActivity = "Found 2 issues in MemoryViewerSheet.kt",
        ),
        SubagentInfo(
            conversationId = "d0f3b6e812",
            role = "Docs",
            typeName = "general-purpose",
            state = SubagentState.ERRORED,
            error = "Provider rate limit reached",
        ),
    )

    private val tasks = listOf(
        BackgroundTaskInfo(
            taskId = "task-3",
            commandLine = "./gradlew assembleOnlineDebug",
            cwd = "/workspace/app",
            status = BackgroundTaskStatus.RUNNING,
            liveOutputTail = "> Task :app:mergeOnlineDebugResources\n> Task :app:compileOnlineDebugKotlin\n> Task :app:dexBuilderOnlineDebug",
        ),
        BackgroundTaskInfo(
            taskId = "task-2",
            commandLine = "npm test -- --watch=false",
            cwd = "/workspace/web",
            status = BackgroundTaskStatus.COMPLETED,
            exitCode = 0,
        ),
    )

    private val artifacts = listOf(
        ArtifactInfo(
            id = "plan",
            title = "Implementation plan",
            filePath = "/workspace/app/docs/activity-sheet-plan.md",
            summary = "Steps to move token telemetry into the Activity sheet.",
        ),
    )

    private val timers = listOf(ScheduledTimerInfo("timer-1", "Check the build again", 600, 252))

    private val metrics = SessionTokenMetrics(
        promptTokens = 48_210,
        completionTokens = 6_120,
        cachedTokens = 31_004,
        contextWindowLimit = 200_000,
        estimatedCostUsd = 0.42,
    )

    private val activeSkills = listOf(
        SkillInfo("s1", "test-runner", "Runs the unit tests and summarizes failures by module.", "/w/.agents/skills/test-runner/SKILL.md", SkillSource.PROJECT, markdownContent = "# Test runner\n\nRun ./gradlew test and group failures by module."),
        SkillInfo("s2", "api-client", "Generates typed clients from the OpenAPI spec.", "/w/api/.agents/skills/api-client/SKILL.md", SkillSource.LINKED, sourceProjectName = "api-server"),
        SkillInfo("s3", "frontend-design", "Distinctive, production-grade interfaces.", "/skills/frontend-design/SKILL.md", SkillSource.BUNDLED, isEnabled = false),
    )

    private val globalSkills = listOf(
        SkillInfo("g1", "frontend-design", "Distinctive, production-grade interfaces.", "/skills/frontend-design/SKILL.md", SkillSource.BUNDLED),
        SkillInfo("g2", "release-notes", "Drafts release notes from merged changes.", "/skills/release-notes/SKILL.md", SkillSource.GLOBAL),
        SkillInfo("g3", "pdf", "Reads, fills and merges PDF files.", "/skills/pdf/SKILL.md", SkillSource.BUNDLED),
    )

    private val otherProject = Project(id = "p2", name = "api-server", description = "", language = "Go")

    private val otherSkills = mapOf(
        otherProject to listOf(
            SkillInfo("o1", "migrations", "Writes and checks SQL migrations.", "/w/api/.agents/skills/migrations/SKILL.md", SkillSource.OTHER_PROJECT),
            SkillInfo("o2", "load-test", "Runs k6 load tests against staging.", "/w/api/.agents/skills/load-test/SKILL.md", SkillSource.OTHER_PROJECT),
        ),
    )

    private val projectRules = listOf(
        RuleInfo("r1", "AGENTS.md", "Project guide", "", "/w/AGENTS.md", RuleSource.PROJECT, content = "# Mobile Harness\n\n- Follow explicit task requirements.\n- Make the smallest complete change."),
        RuleInfo("r2", "coding.md", "Coding style", "", "/w/.agents/rules/coding.md", RuleSource.PROJECT),
    )

    private val globalRules = listOf(
        RuleInfo("g-coding", "coding.md", "Senior engineer", "Clean, tested changes with small diffs.", "/rules/coding.md", RuleSource.GLOBAL),
        RuleInfo("g-design", "design.md", "Product designer", "Calm, consistent interfaces and clear copy.", "/rules/design.md", RuleSource.GLOBAL),
        RuleInfo("g-sec", "security.md", "Security reviewer", "Threat-models every change that touches input or storage.", "/rules/security.md", RuleSource.BUNDLED),
    )

    private val memory = ContextMemory(
        projectId = "p1",
        entries = listOf(
            MemoryEntry(key = "project-framework", value = "Kotlin with Jetpack Compose, minSdk 28.", source = MemorySource.AUTO),
            MemoryEntry(key = "build-command", value = "./gradlew assembleOnlineDebug, then copy the APK to the project root.", source = MemorySource.USER),
            MemoryEntry(key = "test-runner", value = "Robolectric for UI tests; screenshot tests are opt-in with -Pscreenshots.", source = MemorySource.AUTO),
        ),
    )

    // ---- Screens ---------------------------------------------------------------------------

    @Composable
    private fun ChatBackdrop() {
        val colors = PocketColors.current
        Column(
            Modifier
                .fillMaxSize()
                .hostBackdropSource()
                .padding(horizontal = 16.dp)
                .padding(top = 72.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Move the token counter into the Activity sheet",
                style = PocketType.body,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(PocketShape.lg)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Text(
                "I’ll read ChatTab and the inspector first, then move the telemetry into a Session section of the Activity sheet.",
                style = PocketType.body,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "fun TokenTelemetryBar(\n    metrics: SessionTokenMetrics,\n    modifier: Modifier = Modifier,\n)",
                style = PocketType.code,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().clip(PocketShape.md).background(colors.codeSurface).padding(12.dp),
            )
            Text(
                "The bar is still kept for other callers. Next I’ll restyle the slash command panel so it floats on glass above the composer.",
                style = PocketType.body,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Run the screenshot tests",
                style = PocketType.body,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(PocketShape.lg)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }

    @Composable
    private fun ComposerStack(draft: String, panel: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                panel()
                LiquidGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = PocketShape.capsule,
                    layerSource = LiquidGlassLayers.Background,
                ) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(draft, style = PocketType.body, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(1.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun Activity(empty: Boolean = false) {
        AuxiliaryInspectorSheet(
            subagents = if (empty) emptyList() else subagents,
            tasks = if (empty) emptyList() else tasks,
            artifacts = if (empty) emptyList() else artifacts,
            timers = if (empty) emptyList() else timers,
            onDismiss = {},
            onOpenMemoryViewer = {},
            tokenMetrics = metrics,
        )
    }

    @Composable
    private fun Skills() {
        SkillsManagerDialog(
            config = ProjectCustomizationConfig("p1", scopeMode = CustomizationScopeMode.INHERIT_AND_MERGE),
            activeSkills = activeSkills,
            otherProjectsSkills = otherSkills,
            globalSkills = globalSkills,
            activeRules = listOf(globalRules[0].copy(content = "Prefer small, tested diffs."), projectRules[0]),
            projectRules = projectRules,
            globalRules = globalRules,
            onDismiss = {},
        )
    }

    @Composable
    private fun TaskLog() {
        TaskLogViewerDialog(
            task = BackgroundTaskInfo(
                taskId = "task-3",
                commandLine = "./gradlew assembleOnlineDebug",
                cwd = "/workspace/app",
                status = BackgroundTaskStatus.RUNNING,
                liveOutputTail = (1..40).joinToString("\n") { i ->
                    when {
                        i % 7 == 0 -> "w: Parameter 'modifier' is never used"
                        else -> "> Task :app:step$i UP-TO-DATE"
                    }
                },
            ),
            onDismiss = {},
        )
    }

    private val transcriptFile: File by lazy {
        File.createTempFile("transcript", ".jsonl").apply {
            writeText(
                (1..24).joinToString("\n") { i ->
                    """{"type":"${if (i % 2 == 0) "assistant" else "tool_result"}","index":$i,"text":"Read app/src/main/java/com/jarves/mh/ui/CliParityComponents.kt lines ${i * 40}-${i * 40 + 40}"}"""
                },
            )
            deleteOnExit()
        }
    }

    @Composable
    private fun Transcript() {
        SubagentTranscriptViewerDialog(
            subagent = subagents[0].copy(transcriptPath = transcriptFile.absolutePath),
            onDismiss = {},
        )
    }

    // ---- Tests -----------------------------------------------------------------------------

    @Test fun activityLight() = shot("activity", false) { Activity() }
    @Test fun activityDark() = shot("activity", true) { Activity() }

    @Test fun activityLargeLight() {
        render(false) { Activity() }
        expandSheet("Activity")
        capture("activity-large", false)
    }

    @Test fun activityLargeDark() {
        render(true) { Activity() }
        expandSheet("Activity")
        capture("activity-large", true)
    }

    @Test fun activityScrolledLight() = activityScrolled(false)
    @Test fun activityScrolledDark() = activityScrolled(true)

    private fun activityScrolled(dark: Boolean) {
        render(dark) { Activity() }
        expandSheet("Activity")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Timers"))
        settle()
        capture("activity-scrolled", dark)
    }

    @Test fun activityEmptyLight() = shot("activity-empty", false) { Activity(empty = true) }

    @Test fun activityStopLight() {
        render(false) { Activity() }
        compose.onNodeWithContentDescription("Stop Explorer").performClick()
        settle()
        capture("activity-stop", false)
    }

    @Test fun skillsLight() = shot("skills-rules", false) { Skills() }
    @Test fun skillsDark() = shot("skills-rules", true) { Skills() }

    @Test fun skillsTabLight() {
        render(false) { Skills() }
        compose.onNodeWithText("Skills").performClick()
        settle()
        capture("skills-skills", false)
    }

    @Test fun skillsTabDark() {
        render(true) { Skills() }
        compose.onNodeWithText("Skills").performClick()
        settle()
        capture("skills-skills", true)
    }

    @Test fun personasLight() {
        render(false) { Skills() }
        compose.onNodeWithText("Personas").performClick()
        settle()
        compose.onNodeWithText("Product designer").performClick()
        settle()
        capture("skills-personas", false)
    }

    @Test fun promptDark() {
        render(true) { Skills() }
        compose.onNodeWithText("Prompt").performClick()
        settle()
        capture("skills-prompt", true)
    }

    @Test fun modelPickerLight() = shot("model-picker", false) {
        ModelPickerDialog(currentModel = "sonnet", availableModels = emptyList(), onSelectModel = {}, onDismiss = {}, provider = ProviderKind.CLAUDE)
    }

    @Test fun modelPickerDark() = shot("model-picker", true) {
        ModelPickerDialog(currentModel = "sonnet", availableModels = emptyList(), onSelectModel = {}, onDismiss = {}, provider = ProviderKind.CLAUDE)
    }

    @Test fun thinkingPickerLight() = shot("thinking-picker", false) {
        ClaudeThinkingPickerDialog(currentLevel = "high", onSelectLevel = {}, onDismiss = {})
    }

    @Test fun thinkingPickerDark() = shot("thinking-picker", true) {
        ClaudeThinkingPickerDialog(currentLevel = "high", onSelectLevel = {}, onDismiss = {})
    }

    @Test fun taskLogLight() = shot("task-log", false) { TaskLog() }
    @Test fun taskLogDark() = shot("task-log", true) { TaskLog() }

    @Test fun transcriptLight() = shot("subagent-transcript", false) { Transcript() }
    @Test fun transcriptDark() = shot("subagent-transcript", true) { Transcript() }

    @Test fun memoryLight() = shot("memory", false) {
        MemoryViewerSheet(memory, onDismiss = {}, onAddEntry = { _, _ -> }, onDeleteEntry = {}, onClearAuto = {}, onClearAll = {})
    }

    @Test fun memoryDark() = shot("memory", true) {
        MemoryViewerSheet(memory, onDismiss = {}, onAddEntry = { _, _ -> }, onDeleteEntry = {}, onClearAuto = {}, onClearAll = {})
    }

    @Test fun memoryAddLight() {
        render(false) {
            MemoryViewerSheet(memory, onDismiss = {}, onAddEntry = { _, _ -> }, onDeleteEntry = {}, onClearAuto = {}, onClearAll = {})
        }
        compose.onNodeWithContentDescription("Add fact").performClick()
        settle()
        capture("memory-add", false)
    }

    @Test fun slashMenuLight() = shot("slash-menu", false) {
        ComposerStack("/") { SlashCommandMenu(SlashCommandEngine.ALL_SLASH_COMMANDS, activeSkills, onSelect = {}, onSelectSkill = {}, modifier = Modifier.padding(bottom = 6.dp)) }
    }

    @Test fun slashMenuDark() = shot("slash-menu", true) {
        ComposerStack("/") { SlashCommandMenu(SlashCommandEngine.ALL_SLASH_COMMANDS, activeSkills, onSelect = {}, onSelectSkill = {}, modifier = Modifier.padding(bottom = 6.dp)) }
    }

    private val files = listOf(
        WorkspaceEntry("app", "app", isDirectory = true, depth = 0),
        WorkspaceEntry("app/src/main/AndroidManifest.xml", "AndroidManifest.xml", isDirectory = false, depth = 3),
        WorkspaceEntry("app/src/main/java/com/jarves/mh/ui/CliParityComponents.kt", "CliParityComponents.kt", isDirectory = false, depth = 7),
        WorkspaceEntry("docs", "docs", isDirectory = true, depth = 0),
        WorkspaceEntry("README.md", "README.md", isDirectory = false, depth = 0),
    )

    @Test fun mentionMenuLight() = shot("mention-menu", false) {
        ComposerStack("Look at @") { MentionMenu(files, onSelect = {}, modifier = Modifier.padding(bottom = 6.dp)) }
    }

    @Test fun mentionMenuDark() = shot("mention-menu", true) {
        ComposerStack("Look at @") { MentionMenu(files, onSelect = {}, modifier = Modifier.padding(bottom = 6.dp)) }
    }

    @Test fun telemetryLight() = shot("telemetry", false) {
        ComposerStack("Ask anything") {
            LiquidGlassSurface(shape = PocketShape.capsule, layerSource = LiquidGlassLayers.Background, modifier = Modifier.padding(bottom = 8.dp)) {
                TokenTelemetryBar(metrics.copy(promptTokens = 171_000))
            }
        }
    }
}
