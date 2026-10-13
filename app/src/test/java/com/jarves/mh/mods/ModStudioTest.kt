package com.jarves.mh.mods

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ModStudioTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun registry() = ModRegistry(File(temp.root, "mods"), appVersionCode = 5)

    private fun draftManifest(extra: JSONObject.() -> Unit = {}) = JSONObject(ModAuthoringGuide.starterManifest("studio.local.notes-ab12", "Notes", "Keeps notes"))
        .put("contributes", JSONObject()
            .put("rules", JSONArray().put(JSONObject().put("id", "style").put("file", "rules/style.md")))
            .put("skills", JSONArray().put(JSONObject().put("id", "tidy-up").put("dir", "skills/tidy-up"))))
        .apply(extra)
        .toString(2)

    private fun draft(manifest: String = draftManifest()): File {
        val dir = File(temp.root, "project").apply { mkdirs() }
        mapOf(
            "mod.json" to manifest,
            "rules/style.md" to "Prefer small functions.",
            "skills/tidy-up/SKILL.md" to "---\nname: tidy-up\ndescription: Tidies.\n---\nTidy.",
            "skills/tidy-up/notes.txt" to "extra",
            "assets/icon.txt" to "icon",
            "README.md" to "# Notes",
            ModAuthoringGuide.GUIDE_FILE to ModAuthoringGuide.markdown(),
            "AGENTS.md" to ModAuthoringGuide.pointer(),
            "src/app.kt" to "fun main() {}",
            ".git/config" to "[core]",
        ).forEach { (path, text) -> File(dir, path).apply { parentFile?.mkdirs(); writeText(text) } }
        return dir
    }

    @Test
    fun `a draft packs only the mod's own files and gets its integrity filled in`() {
        val registry = registry()
        val staged = registry.stageDraft(draft())
        assertEquals(
            setOf("README.md", "assets/icon.txt", "rules/style.md", "skills/tidy-up/SKILL.md", "skills/tidy-up/notes.txt"),
            staged.files.keys,
        )
        assertEquals(staged.files, staged.manifest.integrity)
        val mod = registry.install(staged, setOf(ModCapability.UI_TOAST), ModTrust.BUILT, "Studio draft")
        assertEquals(ModTrust.BUILT, mod.trust)
        assertTrue(File(mod.dir, "rules/style.md").isFile)
        assertTrue(!File(mod.dir, "AGENTS.md").exists() && !File(mod.dir, "src").exists())

        // The agent keeps working; installing again updates the same mod in place.
        File(temp.root, "project/rules/style.md").writeText("Prefer tiny functions.")
        val again = registry.install(registry.stageDraft(File(temp.root, "project")), emptySet(), ModTrust.BUILT, "Studio draft")
        assertEquals("Prefer tiny functions.", File(again.dir, "rules/style.md").readText())
        assertEquals(1, registry.snapshot.value.mods.size)
    }

    @Test
    fun `a draft's own integrity is ignored and links are refused`() {
        val registry = registry()
        val withBogus = draftManifest { put("integrity", JSONObject().put("rules/style.md", "nope")) }
        assertEquals(5, registry.stageDraft(draft(withBogus)).files.size)

        val outside = File(temp.root, "secret.md").apply { writeText("secret") }
        val rule = File(temp.root, "project/rules/style.md")
        rule.delete()
        Files.createSymbolicLink(rule.toPath(), outside.toPath())
        try {
            registry.stageDraft(File(temp.root, "project"))
            fail("a linked rule must be refused")
        } catch (e: ModInstallException) {
            assertTrue(e.message.orEmpty(), "link" in e.message.orEmpty())
        }
    }

    @Test
    fun `a broken draft lists its problems`() {
        val registry = registry()
        try {
            registry.stageDraft(draft(draftManifest { put("panels", JSONArray()) }))
            fail("unknown fields must be reported")
        } catch (e: ModInstallException) {
            assertTrue(e.problems.toString(), e.problems.any { "Unknown field \"panels\"" in it })
        }
        File(temp.root, "project/mod.json").writeText("{ not json")
        try {
            registry.stageDraft(File(temp.root, "project"))
            fail("bad JSON must be reported")
        } catch (e: ModInstallException) {
            assertTrue(e.message.orEmpty(), "not valid JSON" in e.message.orEmpty())
        }
    }

    @Test
    fun `the guide's example and starter manifest are valid, and it covers every event`() {
        ModManifestParser.parse(ModAuthoringGuide.starterManifest("studio.local.x-1234", "X", "Does x"), 5)
        val guide = ModAuthoringGuide.markdown()
        val example = guide.substringAfter("```json\n").substringBefore("```")
        val parsed = ModManifestParser.parse(example, 5)
        assertEquals("studio.you.build-notes", parsed.id)
        ModEvent.entries.forEach { assertTrue("guide lists ${it.id}", "`${it.id}`" in guide) }
        ModCapability.entries.filter { it.usable }.forEach { assertTrue("guide lists ${it.id}", "`${it.id}`" in guide) }
    }
}
