package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectRule
import com.jarves.mh.model.SkillInfo
import com.jarves.mh.model.SkillSource
import java.io.File

class SkillManager(
    private val baseDir: File,
) {
    constructor(context: Context) : this(context.filesDir)

    private val globalSkillsDir: File
        get() = File(baseDir, "skills").apply { mkdirs() }

    private val bundledSkillsDir: File
        get() = File(baseDir, "builtin_skills").apply { mkdirs() }

    init {
        ensureBundledSkills()
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
     * Discovers all skills from Project, Global, and Bundled sources.
     */
    fun discoverSkills(projectWorkspaceDir: File?): List<SkillInfo> {
        val result = mutableListOf<SkillInfo>()
        val seenNames = mutableSetOf<String>()

        // 1. Project skills (highest precedence)
        if (projectWorkspaceDir != null && projectWorkspaceDir.isDirectory) {
            val projectSearchDirs = listOf(
                File(projectWorkspaceDir, ".agents/skills"),
                File(projectWorkspaceDir, ".claude/skills"),
                File(projectWorkspaceDir, ".antigravity/skills"),
                File(projectWorkspaceDir, ".gemini/skills"),
                File(projectWorkspaceDir, "skills"),
            )
            for (dir in projectSearchDirs) {
                if (!dir.isDirectory) continue
                dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                    val skillFile = File(sub, "SKILL.md")
                    if (skillFile.isFile) {
                        parseSkillFile(skillFile, SkillSource.PROJECT)?.let { skill ->
                            if (seenNames.add(skill.name.lowercase())) result.add(skill)
                        }
                    }
                }
            }
        }

        // 2. Global user skills
        globalSkillsDir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            val skillFile = File(sub, "SKILL.md")
            if (skillFile.isFile) {
                parseSkillFile(skillFile, SkillSource.GLOBAL)?.let { skill ->
                    if (seenNames.add(skill.name.lowercase())) result.add(skill)
                }
            }
        }

        // 3. Bundled built-in skills
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
     * Loads standard project rules files: GEMINI.md, CLAUDE.md, AGENTS.md.
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
