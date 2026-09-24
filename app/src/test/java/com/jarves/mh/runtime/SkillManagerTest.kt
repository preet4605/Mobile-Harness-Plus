package com.jarves.mh.runtime

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
}
