package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.model.CustomizationScopeMode
import com.jarves.mh.model.LinkedSkillReference
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectCustomizationConfig
import com.jarves.mh.model.ProjectRule
import com.jarves.mh.model.RuleInfo
import com.jarves.mh.model.RuleSource
import com.jarves.mh.model.SkillInfo
import com.jarves.mh.model.SkillSource
import java.io.File

class SkillManager(
    private val baseDir: File,
) {
    constructor(context: Context) : this(context.filesDir)

    val globalSkillsDir: File
        get() = File(baseDir, "skills").apply { mkdirs() }

    val bundledSkillsDir: File
        get() = File(baseDir, "builtin_skills").apply { mkdirs() }

    val globalRulesDir: File
        get() = File(baseDir, "rules").apply { mkdirs() }

    val bundledRulesDir: File
        get() = File(baseDir, "builtin_rules").apply { mkdirs() }

    init {
        ensureBundledSkills()
        ensureBundledRules()
    }

    /**
     * Seeds built-in skills if not already written.
     */
    private fun ensureBundledSkills() {
        val defaultSkills = mapOf(
            "android-developer" to Pair(
                "Android expert for Gradle, Jetpack Compose, build variants, APK packaging, and Android SDK debugging.",
                """
                # Android Developer Skill
                
                You are an Android expert specializing in Modern Android Development:
                - Gradle Kotlin DSL and Groovy build scripts
                - Jetpack Compose UI architecture and state hoisting
                - Android SDK, AAPT2, and R8/D8 desugaring
                - Troubleshooting compilation errors and runtime exceptions
                
                Always inspect `build.gradle`, `settings.gradle`, and `AndroidManifest.xml` when working with Android apps.
                """.trimIndent()
            ),
            "code-reviewer" to Pair(
                "Code quality, performance, and security review specialist.",
                """
                # Code Reviewer Skill
                
                Perform a structured, meticulous code review:
                1. Inspect edge cases, potential NullPointerExceptions, and resource leaks.
                2. Check architectural consistency and naming conventions.
                3. Validate error handling, logging, and security boundaries.
                4. Verify test coverage and propose concrete test cases.
                """.trimIndent()
            ),
            "git-expert" to Pair(
                "Git specialist for branch management, rebases, clean commit history, and conflict resolution.",
                """
                # Git Expert Skill
                
                Guide and automate git workflows:
                - Craft conventional commits (`feat:`, `fix:`, `refactor:`, `test:`)
                - Resolve merge conflicts cleanly
                - Inspect `git status`, `git diff`, and `git log` carefully before destructive actions
                """.trimIndent()
            ),
            "antigravity-guide" to Pair(
                "Comprehensive guide and sitemap for Google Antigravity, subagents, and customizations.",
                """
                # Antigravity Guide Skill
                
                Provides reference information for:
                - Antigravity CLI (`agy`), slash commands, and multi-account Google OAuth
                - Subagent orchestration (`invoke_subagent`, `manage_subagents`)
                - Background task management (`manage_task`, `schedule`)
                - Customization system (rules, skills, hooks, MCP)
                """.trimIndent()
            ),
        )

        defaultSkills.forEach { (name, info) ->
            val (desc, content) = info
            val dir = File(bundledSkillsDir, name).apply { mkdirs() }
            val skillFile = File(dir, "SKILL.md")
            if (!skillFile.isFile) {
                val fullText = buildString {
                    appendLine("---")
                    appendLine("name: $name")
                    appendLine("description: $desc")
                    appendLine("---")
                    appendLine()
                    appendLine(content)
                }
                skillFile.writeText(fullText)
            }
        }
    }

    /**
     * Seeds built-in persona rules covering the 5 core software engineering disciplines.
     */
    private fun ensureBundledRules() {
        val defaultRules = listOf(
            RuleInfo(
                id = "bundled:coding-persona",
                name = "coding-persona",
                title = "Software Architect",
                description = "Clean code, test-driven development, minimal complexity, idiomatic typing.",
                filePath = File(bundledRulesDir, "coding-persona.md").absolutePath,
                source = RuleSource.BUNDLED,
                content = """
                # Software Architect Persona & Engineering Discipline
                
                - Clean Code & Test-Driven Development: Write unit tests first; ensure red-green-refactor loop.
                - Minimal Complexity: Reject premature abstractions and speculative features.
                - Idiomatic Typing: Enforce strict null safety, immutable data structures, and expressive interfaces.
                - Quality Gate: Verify that compilation, unit tests, and style checks pass before declaring work complete.
                """.trimIndent()
            ),
            RuleInfo(
                id = "bundled:design-persona",
                name = "design-persona",
                title = "Frontend Design Lead",
                description = "Distinctive, bespoke UI/UX, intentional typography and palettes, anti-AI-slop design.",
                filePath = File(bundledRulesDir, "design-persona.md").absolutePath,
                source = RuleSource.BUNDLED,
                content = """
                # Frontend Design Lead Persona
                
                - Anti-AI-Slop Craft: Reject generic templates, unstyled cards, and arbitrary purple gradients.
                - Design Tokens & Systems: Follow brand color palettes, strict spacing grids, and intentional typography.
                - Accessible & Responsive: Meet WCAG standards, support edge-to-edge layouts, and dynamic window sizes.
                """.trimIndent()
            ),
            RuleInfo(
                id = "bundled:debugging-persona",
                name = "debugging-persona",
                title = "Systems Diagnostician",
                description = "Stop-the-line triage, root cause isolation, scientific error recovery.",
                filePath = File(bundledRulesDir, "debugging-persona.md").absolutePath,
                source = RuleSource.BUNDLED,
                content = """
                # Systems Diagnostician Persona
                
                - Scientific Method: Never guess or blindly patch. Stop the line and reproduce systematically.
                - Root Cause Isolation: Gather logs, inspect stack traces, check process exit codes, and test hypotheses.
                - Regression Guards: Write tests reproducing the failure before applying the fix.
                """.trimIndent()
            ),
            RuleInfo(
                id = "bundled:performance-persona",
                name = "performance-persona",
                title = "Performance Engineer",
                description = "Measurement-first profiling, Core Web Vitals, runtime and query efficiency.",
                filePath = File(bundledRulesDir, "performance-persona.md").absolutePath,
                source = RuleSource.BUNDLED,
                content = """
                # Performance Engineer Persona
                
                - Measure First: Establish baselines before touching code. Profile allocations and render bottlenecks.
                - Algorithmic Efficiency: Eliminate N+1 query patterns, minimize disk I/O, and optimize background threads.
                - Verification: Re-measure after optimization to objectively prove performance gains.
                """.trimIndent()
            ),
            RuleInfo(
                id = "bundled:security-persona",
                name = "security-persona",
                title = "Security Specialist",
                description = "STRIDE threat modeling, OWASP Top 10 defense, boundary sanitization, AST audit.",
                filePath = File(bundledRulesDir, "security-persona.md").absolutePath,
                source = RuleSource.BUNDLED,
                content = """
                # Security Specialist Persona
                
                - Hostile Boundaries: Treat all external input and cross-process data as untrusted.
                - Threat Modeling: Apply STRIDE to evaluate spoofing, tampering, information disclosure, and elevation.
                - Sandboxing: Prevent path traversal (`../`), enforce least privilege, and validate file containment.
                """.trimIndent()
            ),
        )

        defaultRules.forEach { rule ->
            val file = File(bundledRulesDir, "${rule.name}.md")
            if (!file.isFile) {
                val fullText = buildString {
                    appendLine("---")
                    appendLine("name: ${rule.name}")
                    appendLine("title: ${rule.title}")
                    appendLine("description: ${rule.description}")
                    appendLine("---")
                    appendLine()
                    appendLine(rule.content)
                }
                file.writeText(fullText)
            }
        }
    }

    // =========================================================================
    // RULES MANAGEMENT & DISCOVERY
    // =========================================================================

    /**
     * Parses a Markdown rule file, extracting frontmatter if present or falling back to Markdown headers.
     */
    fun parseRuleFile(file: File, source: RuleSource): RuleInfo? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val match = Regex("""^---\s*\r?\n([\s\S]*?)\r?\n---\s*(\r?\n[\s\S]*)?$""").find(text)
        val frontmatter = match?.groupValues?.get(1).orEmpty()
        val markdownBody = match?.groupValues?.getOrNull(2)?.trim().orEmpty()

        val rawName = Regex("""^name:\s*(.+)$""", RegexOption.MULTILINE).find(frontmatter)?.groupValues?.get(1)?.trim()
        val name = rawName?.removeSurrounding("\"")?.removeSurrounding("'")?.trim()
            ?: file.nameWithoutExtension

        val rawTitle = Regex("""^title:\s*(.+)$""", RegexOption.MULTILINE).find(frontmatter)?.groupValues?.get(1)?.trim()
        val title = rawTitle?.removeSurrounding("\"")?.removeSurrounding("'")?.trim()
            ?: Regex("""^#\s+(.+)$""", RegexOption.MULTILINE).find(markdownBody.ifBlank { text })?.groupValues?.get(1)?.trim()
            ?: name.replace('-', ' ').replaceFirstChar { it.uppercase() }

        val rawDesc = Regex("""^description:\s*(.+)$""", RegexOption.MULTILINE).find(frontmatter)?.groupValues?.get(1)?.trim()
        val description = rawDesc?.removeSurrounding("\"")?.removeSurrounding("'")?.trim()
            ?: "System and project operational guidelines."

        return RuleInfo(
            id = "${source.name.lowercase()}:$name",
            name = name,
            title = title,
            description = description,
            filePath = file.absolutePath,
            source = source,
            isEnabled = true,
            content = markdownBody.ifBlank { text },
        )
    }

    /**
     * Discovers all rules across Project Workspace, Global Android Host, Linux PRoot rootfs, and Bundled systems.
     */
    fun discoverRules(projectWorkspaceDir: File?, rootfsDir: File? = null): List<RuleInfo> {
        val result = mutableListOf<RuleInfo>()
        val seenNames = mutableSetOf<String>()

        // 1. Project Rules (Highest precedence)
        if (projectWorkspaceDir != null && projectWorkspaceDir.isDirectory) {
            val rootRuleFiles = listOf("GEMINI.md", "CLAUDE.md", "AGENTS.md")
            rootRuleFiles.forEach { fileName ->
                val f = File(projectWorkspaceDir, fileName)
                if (f.isFile) {
                    parseRuleFile(f, RuleSource.PROJECT)?.let { rule ->
                        if (seenNames.add(rule.name.lowercase())) result.add(rule)
                    }
                }
            }

            val projectRuleDirs = listOf(
                File(projectWorkspaceDir, ".agents/rules"),
                File(projectWorkspaceDir, ".gemini/rules"),
                File(projectWorkspaceDir, ".claude/rules"),
            )
            for (dir in projectRuleDirs) {
                if (!dir.isDirectory) continue
                dir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.forEach { f ->
                    parseRuleFile(f, RuleSource.PROJECT)?.let { rule ->
                        if (seenNames.add(rule.name.lowercase())) result.add(rule)
                    }
                }
            }
        }

        // 2. Global Host Rules (Android host)
        globalRulesDir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.forEach { f ->
            parseRuleFile(f, RuleSource.GLOBAL)?.let { rule ->
                if (seenNames.add(rule.name.lowercase())) result.add(rule)
            }
        }

        // 3. Linux Guest RootFS Global Rules (PRoot Linux config)
        if (rootfsDir != null && rootfsDir.isDirectory) {
            val linuxRulePaths = listOf(
                File(rootfsDir, "root/.gemini/config/rules"),
                File(rootfsDir, "root/.agents/rules"),
            )
            for (dir in linuxRulePaths) {
                if (!dir.isDirectory) continue
                dir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.forEach { f ->
                    parseRuleFile(f, RuleSource.GLOBAL)?.let { rule ->
                        if (seenNames.add(rule.name.lowercase())) result.add(rule)
                    }
                }
            }
            listOf(
                File(rootfsDir, "root/.gemini/config/GEMINI.md"),
                File(rootfsDir, "root/.gemini/config/AGENTS.md"),
                File(rootfsDir, "root/.claude/CLAUDE.md"),
            ).forEach { f ->
                if (f.isFile) {
                    parseRuleFile(f, RuleSource.GLOBAL)?.let { rule ->
                        if (seenNames.add(rule.name.lowercase())) result.add(rule)
                    }
                }
            }
        }

        // 4. Bundled Built-in Discipline Personas
        bundledRulesDir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.forEach { f ->
            parseRuleFile(f, RuleSource.BUNDLED)?.let { rule ->
                if (seenNames.add(rule.name.lowercase())) result.add(rule)
            }
        }

        return result
    }

    /**
     * Resolves the active rules for a project based on the configured CustomizationScopeMode.
     */
    fun resolveActiveRules(
        projectRules: List<RuleInfo>,
        globalRules: List<RuleInfo>,
        config: ProjectCustomizationConfig,
    ): List<RuleInfo> {
        return when (config.scopeMode) {
            CustomizationScopeMode.PROJECT_ONLY -> {
                projectRules.filter { it.id !in config.disabledRuleIds }
            }
            CustomizationScopeMode.GLOBAL_ONLY -> {
                globalRules.filter { it.id !in config.disabledRuleIds }
            }
            CustomizationScopeMode.INHERIT_AND_MERGE -> {
                val projectNames = projectRules.map { it.name.lowercase() }.toSet()
                val inheritedGlobals = globalRules.filter { it.name.lowercase() !in projectNames }
                (projectRules + inheritedGlobals).filter { it.id !in config.disabledRuleIds }
            }
            CustomizationScopeMode.CUSTOM -> {
                (projectRules + globalRules).filter { it.id in config.enabledRuleIds }
            }
        }
    }

    /**
     * Constructs the structured `<user_rules>` XML envelope for runtime prompt injection.
     */
    fun buildRulesBlock(activeRules: List<RuleInfo>): String {
        val enabled = activeRules.filter { it.isEnabled && it.content.isNotBlank() }
        if (enabled.isEmpty()) return ""
        return buildString {
            appendLine("<user_rules>")
            enabled.forEach { rule ->
                appendLine("<RULE[${rule.name}]>")
                appendLine(rule.content.trim())
                appendLine("</RULE[${rule.name}]>")
            }
            appendLine("</user_rules>")
        }
    }

    // =========================================================================
    // SKILLS DISCOVERY, FEDERATION & COMPILATION
    // =========================================================================

    /**
     * Scans a single project workspace directory for all contained skills.
     */
    fun scanProjectWorkspaceForSkills(projectWorkspaceDir: File, source: SkillSource): List<SkillInfo> {
        if (!projectWorkspaceDir.isDirectory) return emptyList()
        val result = mutableListOf<SkillInfo>()
        val seenNames = mutableSetOf<String>()

        val searchDirs = listOf(
            File(projectWorkspaceDir, ".agents/skills"),
            File(projectWorkspaceDir, ".claude/skills"),
            File(projectWorkspaceDir, ".antigravity/skills"),
            File(projectWorkspaceDir, ".gemini/skills"),
            File(projectWorkspaceDir, "skills"),
        )
        for (dir in searchDirs) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                val skillFile = File(sub, "SKILL.md")
                if (skillFile.isFile) {
                    parseSkillFile(skillFile, source)?.let { skill ->
                        if (seenNames.add(skill.name.lowercase())) {
                            result.add(skill)
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * Discovers all skills available across all registered projects in Mobile Harness.
     */
    fun discoverAllProjectsSkills(
        allProjects: List<Project>,
        currentProjectId: String?,
        workspacesBaseDir: File,
    ): Map<Project, List<SkillInfo>> {
        val catalog = mutableMapOf<Project, List<SkillInfo>>()
        allProjects.filter { it.id != currentProjectId }.forEach { project ->
            val projectDir = File(workspacesBaseDir, project.id)
            if (projectDir.isDirectory) {
                val skills = scanProjectWorkspaceForSkills(projectDir, SkillSource.OTHER_PROJECT)
                    .map { it.copy(sourceProjectId = project.id, sourceProjectName = project.name) }
                if (skills.isNotEmpty()) {
                    catalog[project] = skills
                }
            }
        }
        return catalog
    }

    /**
     * Discovers global host skills and PRoot Linux guest skills.
     */
    fun discoverGlobalAndLinuxSkills(rootfsDir: File? = null): List<SkillInfo> {
        val result = mutableListOf<SkillInfo>()
        val seenNames = mutableSetOf<String>()

        // Host global
        globalSkillsDir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            val skillFile = File(sub, "SKILL.md")
            if (skillFile.isFile) {
                parseSkillFile(skillFile, SkillSource.GLOBAL)?.let { skill ->
                    if (seenNames.add(skill.name.lowercase())) result.add(skill)
                }
            }
        }

        // PRoot Linux guest global
        if (rootfsDir != null && rootfsDir.isDirectory) {
            val linuxSkillPaths = listOf(
                File(rootfsDir, "root/.gemini/config/skills"),
                File(rootfsDir, "root/.agents/skills"),
            )
            for (dir in linuxSkillPaths) {
                if (!dir.isDirectory) continue
                dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                    val skillFile = File(sub, "SKILL.md")
                    if (skillFile.isFile) {
                        parseSkillFile(skillFile, SkillSource.GLOBAL)?.let { skill ->
                            if (seenNames.add(skill.name.lowercase())) result.add(skill)
                        }
                    }
                }
            }
        }

        return result
    }

    /**
     * Discovers bundled built-in skills.
     */
    fun discoverBundledSkills(): List<SkillInfo> {
        val result = mutableListOf<SkillInfo>()
        val seenNames = mutableSetOf<String>()
        bundledSkillsDir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            val skillFile = File(sub, "SKILL.md")
            if (skillFile.isFile) {
                parseSkillFile(skillFile, SkillSource.BUNDLED)?.let { skill ->
                    if (seenNames.add(skill.name.lowercase())) result.add(skill)
                }
            }
        }
        return result
    }

    /**
     * Discovers skills according to single project scope (backward-compatible).
     */
    fun discoverSkills(projectWorkspaceDir: File?): List<SkillInfo> {
        val result = mutableListOf<SkillInfo>()
        val seenNames = mutableSetOf<String>()

        if (projectWorkspaceDir != null) {
            scanProjectWorkspaceForSkills(projectWorkspaceDir, SkillSource.PROJECT).forEach { skill ->
                if (seenNames.add(skill.name.lowercase())) result.add(skill)
            }
        }

        discoverGlobalAndLinuxSkills().forEach { skill ->
            if (seenNames.add(skill.name.lowercase())) result.add(skill)
        }

        discoverBundledSkills().forEach { skill ->
            if (seenNames.add(skill.name.lowercase())) result.add(skill)
        }

        return result
    }

    /**
     * Compiles the comprehensive active skill set for a specific project combining Local, Linked, Global, and Bundled.
     */
    fun compileActiveProjectSkills(
        activeProject: Project,
        config: ProjectCustomizationConfig,
        allProjects: List<Project>,
        workspacesBaseDir: File,
        rootfsDir: File? = null,
    ): List<SkillInfo> {
        val result = mutableListOf<SkillInfo>()
        val seenNames = mutableSetOf<String>()

        val allowLocal = config.scopeMode != CustomizationScopeMode.GLOBAL_ONLY
        val allowGlobalAndBundled = config.scopeMode != CustomizationScopeMode.PROJECT_ONLY

        // 1. Current Project Local Skills (Highest precedence)
        if (allowLocal) {
            val localDir = File(workspacesBaseDir, activeProject.id)
            val localSkills = scanProjectWorkspaceForSkills(localDir, SkillSource.PROJECT)
            localSkills.forEach { skill ->
                val isEnabled = if (config.scopeMode == CustomizationScopeMode.CUSTOM) {
                    skill.id in config.enabledSkillIds
                } else {
                    skill.id !in config.disabledSkillIds
                }
                if (seenNames.add(skill.name.lowercase())) {
                    result.add(skill.copy(isEnabled = isEnabled))
                }
            }
        }

        // 2. Linked Skills from other projects (Virtually referenced)
        config.linkedSkills.forEach { link ->
            val sourceDir = File(workspacesBaseDir, link.sourceProjectId)
            val skillFile = File(sourceDir, "${link.relativeSkillPath}/SKILL.md")
            if (skillFile.isFile) {
                parseSkillFile(skillFile, SkillSource.LINKED)?.let { parsed ->
                    val isEnabled = parsed.id !in config.disabledSkillIds
                    if (seenNames.add(parsed.name.lowercase())) {
                        result.add(
                            parsed.copy(
                                id = "linked:${link.sourceProjectId}:${parsed.name}",
                                sourceProjectId = link.sourceProjectId,
                                sourceProjectName = link.sourceProjectName,
                                isEnabled = isEnabled,
                                isReadOnly = true,
                            )
                        )
                    }
                }
            } else {
                val orphanId = "linked:${link.sourceProjectId}:${link.skillName}"
                if (seenNames.add(link.skillName.lowercase())) {
                    result.add(
                        SkillInfo(
                            id = orphanId,
                            name = link.skillName,
                            description = "Source file missing from ${link.sourceProjectName}",
                            filePath = skillFile.absolutePath,
                            source = SkillSource.LINKED,
                            isEnabled = false,
                            sourceProjectId = link.sourceProjectId,
                            sourceProjectName = link.sourceProjectName,
                            isMissingSource = true,
                        )
                    )
                }
            }
        }

        // 3. Global Skills (Host + PRoot Linux)
        if (allowGlobalAndBundled) {
            val globalSkills = discoverGlobalAndLinuxSkills(rootfsDir)
            globalSkills.forEach { skill ->
                if (seenNames.add(skill.name.lowercase())) {
                    val isEnabled = if (config.scopeMode == CustomizationScopeMode.CUSTOM) {
                        skill.id in config.enabledSkillIds
                    } else {
                        skill.id !in config.disabledSkillIds
                    }
                    result.add(skill.copy(isEnabled = isEnabled))
                }
            }

            // 4. Bundled Built-in Skills
            val bundled = discoverBundledSkills()
            bundled.forEach { skill ->
                if (seenNames.add(skill.name.lowercase())) {
                    val isEnabled = if (config.scopeMode == CustomizationScopeMode.CUSTOM) {
                        skill.id in config.enabledSkillIds
                    } else {
                        skill.id !in config.disabledSkillIds
                    }
                    result.add(skill.copy(isEnabled = isEnabled))
                }
            }
        }

        return result
    }

    /**
     * Copies a skill directory from any source project or global repository into the target project workspace.
     */
    fun importSkillToProject(
        sourceSkillDir: File,
        targetWorkspaceDir: File,
        skillName: String,
    ): File {
        require(!skillName.contains("..") && !skillName.startsWith("/") && !skillName.contains('\\')) {
            "Invalid skill name attempting path traversal: $skillName"
        }
        val cleanName = skillName.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-")
        require(cleanName.isNotBlank()) { "Skill name cannot be blank" }
        val targetDir = File(targetWorkspaceDir, ".agents/skills/$cleanName")
        require(targetDir.canonicalFile.toPath().startsWith(targetWorkspaceDir.canonicalFile.toPath())) {
            "Target escapes workspace directory: ${targetDir.path}"
        }
        targetDir.mkdirs()
        sourceSkillDir.copyRecursively(targetDir, overwrite = true)
        return targetDir
    }

    /**
     * Promotes a local skill to Global storage for universal availability across all projects.
     */
    fun promoteSkillToGlobal(
        sourceSkillDir: File,
        skillName: String,
        rootfsDir: File? = null,
    ): File {
        require(!skillName.contains("..") && !skillName.startsWith("/") && !skillName.contains('\\')) {
            "Invalid skill name attempting path traversal: $skillName"
        }
        val cleanName = skillName.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-")
        require(cleanName.isNotBlank()) { "Skill name cannot be blank" }
        val targetDir = File(globalSkillsDir, cleanName)
        require(targetDir.canonicalFile.toPath().startsWith(globalSkillsDir.canonicalFile.toPath())) {
            "Target escapes global skills directory: ${targetDir.path}"
        }
        targetDir.mkdirs()
        sourceSkillDir.copyRecursively(targetDir, overwrite = true)

        if (rootfsDir != null && rootfsDir.isDirectory) {
            val linuxGlobalDir = File(rootfsDir, "root/.gemini/config/skills/$cleanName")
            if (linuxGlobalDir.canonicalFile.toPath().startsWith(rootfsDir.canonicalFile.toPath())) {
                linuxGlobalDir.mkdirs()
                runCatching { sourceSkillDir.copyRecursively(linuxGlobalDir, overwrite = true) }
            }
        }
        return targetDir
    }

    /**
     * Promotes a local rule file to the Global rules directory.
     */
    fun promoteRuleToGlobal(
        sourceRuleFile: File,
        ruleFileName: String,
        rootfsDir: File? = null,
    ): File {
        require(!ruleFileName.contains("..") && !ruleFileName.startsWith("/") && !ruleFileName.contains('\\')) {
            "Invalid rule file name attempting path traversal: $ruleFileName"
        }
        val sanitizedBase = File(ruleFileName).name
        val cleanName = if (sanitizedBase.endsWith(".md")) sanitizedBase else "$sanitizedBase.md"
        val targetFile = File(globalRulesDir, cleanName)
        require(targetFile.canonicalFile.toPath().startsWith(globalRulesDir.canonicalFile.toPath())) {
            "Target escapes global rules directory: ${targetFile.path}"
        }
        sourceRuleFile.copyTo(targetFile, overwrite = true)

        if (rootfsDir != null && rootfsDir.isDirectory) {
            val linuxGlobalRules = File(rootfsDir, "root/.gemini/config/rules")
            val linuxTarget = File(linuxGlobalRules, cleanName)
            if (linuxTarget.canonicalFile.toPath().startsWith(rootfsDir.canonicalFile.toPath())) {
                linuxGlobalRules.mkdirs()
                runCatching { sourceRuleFile.copyTo(linuxTarget, overwrite = true) }
            }
        }
        return targetFile
    }

    /**
     * Parses SKILL.md YAML frontmatter into a SkillInfo object.
     */
    fun parseSkillFile(file: File, source: SkillSource): SkillInfo? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val match = Regex("""^---\s*\r?\n([\s\S]*?)\r?\n---\s*(\r?\n[\s\S]*)?$""").find(text)
        val frontmatter = match?.groupValues?.get(1).orEmpty()
        val markdownBody = match?.groupValues?.getOrNull(2)?.trim().orEmpty()

        val rawName = Regex("""^name:\s*(.+)$""", RegexOption.MULTILINE).find(frontmatter)?.groupValues?.get(1)?.trim()
        val name = rawName?.removeSurrounding("\"")?.removeSurrounding("'")?.trim()
            ?: file.parentFile?.name
            ?: file.nameWithoutExtension

        val rawDescription = Regex("""^description:\s*(.+)$""", RegexOption.MULTILINE).find(frontmatter)?.groupValues?.get(1)?.trim()
        val description = rawDescription?.removeSurrounding("\"")?.removeSurrounding("'")?.trim()
            ?: "Custom developer skill."

        return SkillInfo(
            id = "${source.name.lowercase()}:$name",
            name = name,
            description = description,
            filePath = file.absolutePath,
            source = source,
            isEnabled = true,
            markdownContent = markdownBody.ifBlank { text },
        )
    }

    /**
     * Builds the progressive disclosure index table for injection into the system prompt.
     */
    fun buildProgressiveDisclosureIndex(skills: List<SkillInfo>): String {
        val enabled = skills.filter { it.isEnabled }
        if (enabled.isEmpty()) return ""
        fun escapeXml(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        return buildString {
            appendLine("<skills>")
            appendLine("You can use specialized 'skills' to help you with complex tasks.")
            appendLine("Each skill folder contains a SKILL.md file with detailed instructions and runbooks.")
            appendLine("If a skill seems relevant to your current task, view its SKILL.md file using view_file before proceeding.")
            appendLine()
            appendLine("Available skills:")
            enabled.forEach { skill ->
                val safeName = escapeXml(skill.name)
                val safePath = escapeXml(skill.filePath)
                val safeDesc = escapeXml(skill.description)
                appendLine("- $safeName ($safePath): $safeDesc")
            }
            appendLine("</skills>")
        }
    }

    /**
     * Loads standard project rules files: GEMINI.md, CLAUDE.md, AGENTS.md (backward-compatible).
     */
    fun loadProjectRules(projectWorkspaceDir: File?): List<ProjectRule> {
        if (projectWorkspaceDir == null || !projectWorkspaceDir.isDirectory) return emptyList()
        val candidateNames = listOf("GEMINI.md", "CLAUDE.md", "AGENTS.md")
        return candidateNames.map { name ->
            val file = File(projectWorkspaceDir, name)
            ProjectRule(
                fileName = name,
                filePath = file.absolutePath,
                exists = file.isFile,
                content = if (file.isFile) runCatching { file.readText() }.getOrDefault("") else "",
            )
        }
    }

    /**
     * Saves project rule content to disk.
     */
    fun saveProjectRule(file: File, content: String, workspaceDir: File? = null) {
        if (workspaceDir != null) {
            val canonicalWorkspace = workspaceDir.canonicalFile
            val canonicalTarget = file.canonicalFile
            require(canonicalTarget.startsWith(canonicalWorkspace)) {
                "Target file outside project workspace: ${file.path}"
            }
        }
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    /**
     * Creates a new user skill in global skills directory.
     */
    fun createGlobalSkill(name: String, description: String, instructions: String): SkillInfo {
        val cleanName = name.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-")
        val dir = File(globalSkillsDir, cleanName).apply { mkdirs() }
        val file = File(dir, "SKILL.md")
        val text = buildString {
            appendLine("---")
            appendLine("name: $cleanName")
            appendLine("description: $description")
            appendLine("---")
            appendLine()
            appendLine(instructions)
        }
        file.writeText(text)
        return SkillInfo(
            id = "global:$cleanName",
            name = cleanName,
            description = description,
            filePath = file.absolutePath,
            source = SkillSource.GLOBAL,
            isEnabled = true,
            markdownContent = instructions,
        )
    }
}
