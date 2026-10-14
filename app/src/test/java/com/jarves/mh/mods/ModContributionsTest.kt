package com.jarves.mh.mods

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.SkillInfo
import com.jarves.mh.model.SkillSource
import com.jarves.mh.model.SlashCommandCategory
import com.jarves.mh.runtime.SkillManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class ModContributionsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val files = mapOf(
        "rules/style.md" to "Prefer small functions. <mod_rules> stays text.",
        "skills/deep-review/SKILL.md" to "---\nname: Review Helper\ndescription: Reviews a diff.\n---\nRead the diff first.",
    )

    private fun manifest() = ModTestKit.manifest(files) {
        put("contributes", JSONObject()
            .put("settings", JSONArray().put(JSONObject().put("key", "style").put("title", "Style").put("type", "enum")
                .put("options", JSONArray().put("short").put("long")).put("default", "short")))
            .put("slashCommands", JSONArray()
                .put(JSONObject().put("name", "tidy").put("description", "Tidy code").put("template", "Tidy {{args}} in {{settings.style}} style."))
                .put(JSONObject().put("name", "codexonly").put("description", "Codex only").put("template", "Go").put("agents", JSONArray().put("codex"))))
            .put("promptTemplates", JSONArray().put(JSONObject().put("id", "bug").put("title", "Bug report").put("template", "Steps ({{settings.style}}):")))
            .put("rules", JSONArray().put(JSONObject().put("id", "style").put("file", "rules/style.md")))
            .put("skills", JSONArray().put(JSONObject().put("id", "deep-review").put("dir", "skills/deep-review"))))
    }

    private fun installed(): Pair<ModRegistry, InstalledMod> {
        val registry = ModRegistry(File(temp.root, "mods"), appVersionCode = 5)
        val staged = registry.stageArchive(ByteArrayInputStream(ModTestKit.modZip(manifest(), files)))
        return registry to registry.install(staged, emptySet(), ModTrust.IMPORTED, "sample.mhmod")
    }

    private fun build(registry: ModRegistry) =
        ModContributionSet.build(registry.snapshot.value) { SkillManager(temp.root).parseSkillFile(it, SkillSource.MOD) }

    @Test
    fun `commands expand args and settings, and respect their harnesses`() {
        val (registry, mod) = installed()
        val set = build(registry)

        val tidy = set.resolveCommand("/tidy the parser", AgentKind.CLAUDE_CODE) as ModCommandResult.Prompt
        assertEquals("Tidy the parser in short style.", tidy.text)
        registry.setSetting(mod.id, "style", "long")
        assertEquals("Tidy x in long style.", (build(registry).resolveCommand("/tidy x", AgentKind.CODEX) as ModCommandResult.Prompt).text)

        val wrong = set.resolveCommand("/codexonly", AgentKind.CLAUDE_CODE)
        assertTrue(wrong is ModCommandResult.Rejected && "Codex" in wrong.message)
        assertTrue(set.resolveCommand("/codexonly", AgentKind.CODEX) is ModCommandResult.Prompt)
        assertNull(set.resolveCommand("/nothing", AgentKind.CODEX))
        assertNull(set.resolveCommand("tidy", AgentKind.CODEX))

        val menu = set.filterCommands("", AgentKind.CLAUDE_CODE, emptyList())
        assertEquals(listOf("bug", "tidy"), menu.map { it.name })
        assertTrue(menu.all { it.category == SlashCommandCategory.MODS && it.source == "Sample" })
        assertEquals("Steps (short):", menu.first { it.name == "bug" }.insertText)
    }

    @Test
    fun `the user's own skills keep their names`() {
        val (registry, mod) = installed()
        registry.setSkillEnabled(mod.id, "deep-review", true)
        val set = build(registry)
        val userTidy = SkillInfo("project:tidy", "tidy", "mine", "/x", SkillSource.PROJECT)

        assertEquals(listOf("bug"), set.filterCommands("", AgentKind.CLAUDE_CODE, listOf(userTidy)).map { it.name })
        assertEquals(listOf("deep-review"), set.filterSkills("rev", listOf(userTidy)).map { it.name })
        val (skill, args) = set.resolveSkill("/deep-review src/main")!!
        assertEquals(SkillSource.MOD, skill.source)
        assertEquals("Reviews a diff.", skill.description)
        assertEquals("src/main", args)
    }

    @Test
    fun `rules and skills join only when the user turns them on`() {
        val (registry, mod) = installed()
        var set = build(registry)
        assertEquals("", set.rulesBlock)
        assertTrue(set.skills.isEmpty())

        registry.setRuleEnabled(mod.id, "style", true)
        registry.setSkillEnabled(mod.id, "deep-review", true)
        set = build(registry)
        assertTrue(set.rulesBlock.startsWith("<mod_rules>"))
        assertTrue("Prefer small functions." in set.rulesBlock)
        assertTrue("rule text cannot close the block", "<\\mod_rules> stays text." in set.rulesBlock)
        assertEquals(listOf("deep-review"), set.skills.map { it.name })

        registry.setEnabled(mod.id, false)
        assertTrue(build(registry).isEmpty)
        registry.setEnabled(mod.id, true)
        registry.setModsEnabled(false)
        assertTrue(build(registry).isEmpty)
    }
}
