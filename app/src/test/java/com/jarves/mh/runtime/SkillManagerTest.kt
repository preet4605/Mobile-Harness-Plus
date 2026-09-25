package com.jarves.mh.runtime

import com.jarves.mh.model.CustomizationScopeMode
import com.jarves.mh.model.LinkedSkillReference
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectCustomizationConfig
import com.jarves.mh.model.RuleInfo
import com.jarves.mh.model.RuleSource
import com.jarves.mh.model.SkillSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parseSkillFile_parsesFrontmatterAndStripsQuotes() {
        val root = tempFolder.newFolder("skills_root")
        val manager = SkillManager(root)

        val skillDir = tempFolder.newFolder("test_skill")
        val skillFile = File(skillDir, "SKILL.md")
        skillFile.writeText(
            """
            ---
            name: "quoted-skill-name"
            description: 'A skill with single quotes in description'
            ---
            
            # Body Instructions
            Perform test skill instructions.
            """.trimIndent()
        )

        val skill = manager.parseSkillFile(skillFile, SkillSource.PROJECT)
        assertNotNull(skill)
        assertEquals("quoted-skill-name", skill!!.name)
        assertEquals("A skill with single quotes in description", skill.description)
        assertTrue(skill.markdownContent?.contains("Perform test skill instructions") == true)
    }

    @Test
    fun parseSkillFile_handlesCrlfLineEndings() {
        val root = tempFolder.newFolder("skills_crlf_root")
        val manager = SkillManager(root)

        val skillDir = tempFolder.newFolder("crlf_skill")
        val skillFile = File(skillDir, "SKILL.md")
        skillFile.writeText("---\r\nname: crlf-name\r\ndescription: CRLF description\r\n---\r\n\r\nContent here.")

        val skill = manager.parseSkillFile(skillFile, SkillSource.GLOBAL)
        assertNotNull(skill)
        assertEquals("crlf-name", skill!!.name)
        assertEquals("CRLF description", skill.description)
    }

    @Test
    fun parseSkillFile_fallsBackWhenFrontmatterMissing() {
        val root = tempFolder.newFolder("skills_fallback_root")
        val manager = SkillManager(root)

        val skillDir = tempFolder.newFolder("custom-folder-name")
        val skillFile = File(skillDir, "SKILL.md")
        skillFile.writeText("# Just plain markdown\nNo frontmatter here.")

        val skill = manager.parseSkillFile(skillFile, SkillSource.BUNDLED)
        assertNotNull(skill)
        assertEquals("custom-folder-name", skill!!.name)
        assertEquals("Custom developer skill.", skill.description)
    }

    @Test
    fun buildProgressiveDisclosureIndex_escapesXmlAndOmitsWhenEmpty() {
        val root = tempFolder.newFolder("skills_xml_root")
        val manager = SkillManager(root)

        val emptyIndex = manager.buildProgressiveDisclosureIndex(emptyList())
        assertEquals("", emptyIndex)

        val skillFile = File(tempFolder.newFolder("dummy"), "SKILL.md")
        val skillInfo = manager.parseSkillFile(
            skillFile.apply {
                writeText(
                    """
                    ---
                    name: xml-test & more
                    description: Tests <xml> & 'escaping'
                    ---
                    Body
                    """.trimIndent()
                )
            },
            SkillSource.PROJECT
        )
        assertNotNull(skillInfo)

        val index = manager.buildProgressiveDisclosureIndex(listOf(skillInfo!!))
        assertTrue(index.contains("<skills>"))
        assertTrue(index.contains("xml-test &amp; more"))
        assertTrue(index.contains("Tests &lt;xml&gt; &amp; 'escaping'"))
        assertFalse(index.contains("<xml>"))
    }

    @Test
    fun saveProjectRule_enforcesWorkspaceContainment() {
        val root = tempFolder.newFolder("save_rule_root")
        val manager = SkillManager(root)

        val workspace = tempFolder.newFolder("project_workspace")
        val validFile = File(workspace, "CLAUDE.md")
        manager.saveProjectRule(validFile, "# Claude Rules", workspace)
        assertTrue(validFile.isFile)
        assertEquals("# Claude Rules", validFile.readText())

        val outsideFile = File(tempFolder.newFolder("outside"), "HACK.md")
        var caught = false
        try {
            manager.saveProjectRule(outsideFile, "# Injected", workspace)
        } catch (e: IllegalArgumentException) {
            caught = true
        }
        assertTrue("Should reject file outside workspace", caught)
    }

    @Test
    fun parseRuleFile_parsesFrontmatterAndHeaders() {
        val root = tempFolder.newFolder("rules_root")
        val manager = SkillManager(root)

        val ruleFile = File(root, "sample-rule.md")
        ruleFile.writeText(
            """
            ---
            name: "sample-rule"
            title: "Sample Guideline"
            description: "High quality test guideline"
            ---
            
            # Guideline Details
            Enforce strict guidelines.
            """.trimIndent()
        )

        val rule = manager.parseRuleFile(ruleFile, RuleSource.GLOBAL)
        assertNotNull(rule)
        assertEquals("sample-rule", rule!!.name)
        assertEquals("Sample Guideline", rule.title)
        assertEquals("High quality test guideline", rule.description)
        assertTrue(rule.content.contains("Enforce strict guidelines"))
    }

    @Test
    fun discoverRules_aggregatesProjectGlobalAndBundledPersonas() {
        val root = tempFolder.newFolder("rules_disc_root")
        val manager = SkillManager(root)

        val workspace = tempFolder.newFolder("workspace")
        val localGemini = File(workspace, "GEMINI.md").apply { writeText("# Gemini Project Rules") }

        val rootfs = tempFolder.newFolder("rootfs")
        val linuxRules = File(rootfs, "root/.gemini/config/rules").apply { mkdirs() }
        File(linuxRules, "custom-linux.md").writeText("# Custom Linux Rule")

        val discovered = manager.discoverRules(workspace, rootfs)
        assertTrue(discovered.any { it.name.equals("GEMINI", ignoreCase = true) })
        assertTrue(discovered.any { it.name.equals("custom-linux", ignoreCase = true) })
        assertTrue(discovered.any { it.name.equals("coding-persona", ignoreCase = true) })
        assertTrue(discovered.any { it.name.equals("security-persona", ignoreCase = true) })
    }

    @Test
    fun resolveActiveRules_handlesAllFourScopeModes() {
        val root = tempFolder.newFolder("rules_scope_root")
        val manager = SkillManager(root)

        val projRule = RuleInfo(
            id = "project:coding-persona",
            name = "coding-persona",
            title = "Project Software Architect",
            description = "Custom project coding standards",
            filePath = "/workspace/coding-persona.md",
            source = RuleSource.PROJECT,
            content = "Project specific coding rules",
        )
        val globalRule1 = RuleInfo(
            id = "global:coding-persona",
            name = "coding-persona",
            title = "Global Software Architect",
            description = "Global coding standards",
            filePath = "/global/coding-persona.md",
            source = RuleSource.GLOBAL,
            content = "Global coding rules",
        )
        val globalRule2 = RuleInfo(
            id = "global:security-persona",
            name = "security-persona",
            title = "Security Specialist",
            description = "Global security rules",
            filePath = "/global/security-persona.md",
            source = RuleSource.GLOBAL,
            content = "Global security rules",
        )

        val projectRules = listOf(projRule)
        val globalRules = listOf(globalRule1, globalRule2)

        // 1. PROJECT_ONLY
        val configProjOnly = ProjectCustomizationConfig("p1", CustomizationScopeMode.PROJECT_ONLY)
        val activeProjOnly = manager.resolveActiveRules(projectRules, globalRules, configProjOnly)
        assertEquals(1, activeProjOnly.size)
        assertEquals("project:coding-persona", activeProjOnly.first().id)

        // 2. GLOBAL_ONLY
        val configGlobalOnly = ProjectCustomizationConfig("p1", CustomizationScopeMode.GLOBAL_ONLY)
        val activeGlobalOnly = manager.resolveActiveRules(projectRules, globalRules, configGlobalOnly)
        assertEquals(2, activeGlobalOnly.size)

        // 3. INHERIT_AND_MERGE (project rule shadows global rule of same name)
        val configInherit = ProjectCustomizationConfig("p1", CustomizationScopeMode.INHERIT_AND_MERGE)
        val activeInherit = manager.resolveActiveRules(projectRules, globalRules, configInherit)
        assertEquals(2, activeInherit.size)
        val codingResolved = activeInherit.first { it.name == "coding-persona" }
        assertEquals("project:coding-persona", codingResolved.id)
        assertTrue(activeInherit.any { it.name == "security-persona" })

        // 4. CUSTOM
        val configCustom = ProjectCustomizationConfig(
            projectId = "p1",
            scopeMode = CustomizationScopeMode.CUSTOM,
            enabledRuleIds = setOf("global:security-persona"),
        )
        val activeCustom = manager.resolveActiveRules(projectRules, globalRules, configCustom)
        assertEquals(1, activeCustom.size)
        assertEquals("global:security-persona", activeCustom.first().id)
    }

    @Test
    fun buildRulesBlock_wrapsInUserRulesEnvelope() {
        val root = tempFolder.newFolder("rules_xml_root")
        val manager = SkillManager(root)

        val empty = manager.buildRulesBlock(emptyList())
        assertEquals("", empty)

        val rules = listOf(
            RuleInfo(
                id = "project:my-rules",
                name = "my-rules",
                title = "My Rules",
                description = "Custom rules",
                filePath = "/workspace/rules.md",
                source = RuleSource.PROJECT,
                content = "- Do not use deprecated APIs\n- Write unit tests",
            )
        )
        val xml = manager.buildRulesBlock(rules)
        assertTrue(xml.contains("<user_rules>"))
        assertTrue(xml.contains("<RULE[my-rules]>"))
        assertTrue(xml.contains("- Do not use deprecated APIs"))
        assertTrue(xml.contains("</RULE[my-rules]>"))
        assertTrue(xml.contains("</user_rules>"))
    }

    @Test
    fun crossProjectFederation_discoversLinksAndImports() {
        val root = tempFolder.newFolder("federation_root")
        val manager = SkillManager(root)
        val workspacesBase = tempFolder.newFolder("workspaces")

        // Project A with a skill
        val projectA = Project(id = "proj-a", name = "Project Alpha", description = "", language = "Kotlin")
        val projADir = File(workspacesBase, projectA.id).apply { mkdirs() }
        val skillADir = File(projADir, ".agents/skills/postgres-optimizer").apply { mkdirs() }
        File(skillADir, "SKILL.md").writeText(
            """
            ---
            name: postgres-optimizer
            description: Database query optimization skill
            ---
            Optimize SQL queries.
            """.trimIndent()
        )

        // Project B
        val projectB = Project(id = "proj-b", name = "Project Beta", description = "", language = "Python")
        val projBDir = File(workspacesBase, projectB.id).apply { mkdirs() }

        // Test 1: Discover all projects skills from Project B's viewpoint
        val catalog = manager.discoverAllProjectsSkills(listOf(projectA, projectB), projectB.id, workspacesBase)
        assertEquals(1, catalog.size)
        assertTrue(catalog.containsKey(projectA))
        assertEquals("postgres-optimizer", catalog[projectA]?.first()?.name)

        // Test 2: Virtual linking
        val linkRef = LinkedSkillReference(
            sourceProjectId = projectA.id,
            sourceProjectName = projectA.name,
            skillName = "postgres-optimizer",
            relativeSkillPath = ".agents/skills/postgres-optimizer",
        )
        val config = ProjectCustomizationConfig(
            projectId = projectB.id,
            linkedSkills = listOf(linkRef),
        )
        val activeInB = manager.compileActiveProjectSkills(
            activeProject = projectB,
            config = config,
            allProjects = listOf(projectA, projectB),
            workspacesBaseDir = workspacesBase,
        )
        val linkedSkill = activeInB.firstOrNull { it.name == "postgres-optimizer" }
        assertNotNull(linkedSkill)
        assertEquals(SkillSource.LINKED, linkedSkill!!.source)
        assertTrue(linkedSkill.isReadOnly)

        // Test 3: Physical import
        val importedDir = manager.importSkillToProject(skillADir, projBDir, "postgres-optimizer")
        assertTrue(File(importedDir, "SKILL.md").isFile)

        // Test 4: Path traversal protection
        var traversalBlocked = false
        try {
            manager.importSkillToProject(skillADir, projBDir, "../../../evil-skill")
        } catch (e: IllegalArgumentException) {
            traversalBlocked = true
        }
        assertTrue("Must block path traversal", traversalBlocked)
    }

    @Test
    fun promoteSkillAndRuleToGlobal_copiesToGlobalLocations() {
        val root = tempFolder.newFolder("promote_root")
        val manager = SkillManager(root)

        val localDir = tempFolder.newFolder("local_skill")
        File(localDir, "SKILL.md").writeText("---\nname: my-tool\ndescription: A tool\n---\nRun tool.")

        val promotedDir = manager.promoteSkillToGlobal(localDir, "my-tool")
        assertTrue(File(promotedDir, "SKILL.md").isFile)
        assertTrue(File(manager.globalSkillsDir, "my-tool/SKILL.md").isFile)

        val localRuleFile = File(tempFolder.newFolder("rule_dir"), "custom-policy.md")
        localRuleFile.writeText("# Custom Policy\nStrict security rules.")

        val promotedRule = manager.promoteRuleToGlobal(localRuleFile, "custom-policy.md")
        assertTrue(promotedRule.isFile)
        assertTrue(File(manager.globalRulesDir, "custom-policy.md").isFile)

        // Path traversal rejection checks
        var skillTraversalBlocked = false
        try {
            manager.promoteSkillToGlobal(localDir, "../../escape-skill")
        } catch (e: IllegalArgumentException) {
            skillTraversalBlocked = true
        }
        assertTrue("Must block path traversal in promoteSkillToGlobal", skillTraversalBlocked)

        var ruleTraversalBlocked = false
        try {
            manager.promoteRuleToGlobal(localRuleFile, "../../../escape-rule.md")
        } catch (e: IllegalArgumentException) {
            ruleTraversalBlocked = true
        }
        assertTrue("Must block path traversal in promoteRuleToGlobal", ruleTraversalBlocked)
    }
}
