package com.jarves.mh.runtime

import com.jarves.mh.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CodexSkillsRegressionTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun skill(dir: File, name: String = "fixture", description: String = "Fixture skill"): File {
        dir.mkdirs()
        return File(dir, "SKILL.md").apply { writeText("---\nname: $name\ndescription: $description\n---\nFixture instructions.") }
    }
    private fun project(id: String) = Project(id = id, name = id, description = "", language = "Kotlin")
    private fun compile(config: ProjectCustomizationConfig, base: File, manager: SkillManager) =
        manager.compileActiveProjectSkills(project("target"), config, listOf(project("source"), project("target")), base)
    private fun link(path: String = ".agents/skills/fixture") = LinkedSkillReference(
        sourceProjectId = "source", sourceProjectName = "source", skillName = "fixture", relativeSkillPath = path)

    @Test fun linkedToggleUsesPublishedId() {
        val base = tmp.newFolder(); val manager = SkillManager(tmp.newFolder())
        skill(File(base, "source/.agents/skills/fixture")); File(base, "target").mkdirs()
        val config = ProjectCustomizationConfig(projectId = "target", linkedSkills = listOf(link()))
        val published = compile(config, base, manager).single { it.source == SkillSource.LINKED }
        assertFalse(compile(config.copy(disabledSkillIds = setOf(published.id)), base, manager).single { it.source == SkillSource.LINKED }.isEnabled)
    }
    @Test fun customScopeExcludesUnselectedLinkedSkill() {
        val base = tmp.newFolder(); val manager = SkillManager(tmp.newFolder())
        skill(File(base, "source/.agents/skills/fixture")); File(base, "target").mkdirs()
        val config = ProjectCustomizationConfig(projectId = "target", scopeMode = CustomizationScopeMode.CUSTOM, linkedSkills = listOf(link()))
        assertFalse(compile(config, base, manager).single { it.source == SkillSource.LINKED }.isEnabled)
    }
    @Test fun linkedPathCannotPublishOutsideSourceWorkspace() {
        val base = tmp.newFolder(); val files = tmp.newFolder(); val manager = SkillManager(files)
        File(base, "source").mkdirs(); val target = File(base, "target").apply { mkdirs() }
        skill(File(base, "private-fixture"))
        val config = ProjectCustomizationConfig(projectId = "target", linkedSkills = listOf(link("../private-fixture")))
        val prepared = GuestSkillAccess(files).prepare(compile(config, base, manager), target, null, "/workspace/target")
        assertFalse(prepared.any { it.source == SkillSource.LINKED && it.isEnabled && it.guestFilePath != null })
    }
    @Test fun manualOnlyPolicyIsNotAdvertisedForImplicitMatching() {
        val files = tmp.newFolder(); val manager = SkillManager(files); val dir = File(manager.globalSkillsDir, "manual")
        val file = skill(dir, "manual")
        File(dir, "agents").mkdirs(); File(dir, "agents/openai.yaml").writeText("policy:\n  allow_implicit_invocation: false\n")
        val parsed = manager.parseSkillFile(file, SkillSource.GLOBAL)!!.copy(guestFilePath = "/opt/pocket/skills/linked/fixture/SKILL.md")
        assertFalse(manager.buildProgressiveDisclosureIndex(listOf(parsed)).contains("- manual ("))
    }
    @Test fun foldedDescriptionKeepsTriggerWords() {
        val manager = SkillManager(tmp.newFolder()); val file = skill(tmp.newFolder(), description = ">-\n  Android build verification\n  and APK packaging.")
        assertEquals("Android build verification and APK packaging.", manager.parseSkillFile(file, SkillSource.PROJECT)!!.description)
    }
    @Test fun unavailableSkillCannotBeInvoked() {
        val manager = SkillManager(tmp.newFolder()); val parsed = manager.parseSkillFile(skill(tmp.newFolder()), SkillSource.PROJECT)!!
        assertNull(SlashCommandEngine.parseSkillInvocation("/fixture do work", listOf(parsed.copy(guestFilePath = null))))
    }
    @Test fun codexInitTargetsItsNativeInstructionFile() {
        val command = SlashCommandEngine.parseCommand("/init")!!.first
        assertTrue(SlashCommandEngine.buildPromptForCommand(command, "", AgentKind.CODEX).contains("AGENTS.md"))
    }
    @Test fun helpSkillSpellingIsExecutable() {
        assertNotNull(SlashCommandEngine.parseCommand("/skill link source fixture"))
    }
    @Test fun importDoesNotCopyResourcesOutsideSourceFolder() {
        val manager = SkillManager(tmp.newFolder()); val source = tmp.newFolder(); skill(source)
        val outside = File(tmp.newFolder(), "private-fixture.txt").apply { writeText("NON_SECRET_OUTSIDE_FIXTURE") }
        java.nio.file.Files.createSymbolicLink(File(source, "resource.txt").toPath(), outside.toPath())
        val workspace = tmp.newFolder()
        val imported = runCatching { manager.importSkillToProject(source, workspace, "fixture") }.getOrNull()
        assertFalse(imported != null && File(imported, "resource.txt").isFile &&
            File(imported, "resource.txt").readText() == "NON_SECRET_OUTSIDE_FIXTURE")
    }

    @Test fun promotionRejectsEscapingLinksBeforePublishingManifest() {
        val manager = SkillManager(tmp.newFolder()); val source = tmp.newFolder(); skill(source)
        val external = File(tmp.newFolder(), "outside.txt").apply { writeText("FIXTURE") }
        java.nio.file.Files.createSymbolicLink(File(source, "resource.txt").toPath(), external.toPath())
        assertTrue(runCatching { manager.promoteSkillToGlobal(source, "fixture") }.isFailure)
        assertFalse(File(manager.globalSkillsDir, "fixture/SKILL.md").exists())
    }
    @Test fun executableResourcesAndUnrelatedDestinationDataArePreserved() {
        val manager = SkillManager(tmp.newFolder()); val source = tmp.newFolder(); skill(source)
        val script = File(source, "script.sh").apply { writeText("#!/bin/sh\necho fixture"); setExecutable(true) }
        val workspace = tmp.newFolder()
        val imported = manager.importSkillToProject(source, workspace, "fixture")
        File(imported, "personal.txt").writeText("keep")
        manager.importSkillToProject(source, workspace, "fixture")
        assertEquals("keep", File(imported, "personal.txt").readText())
        assertEquals(script.readText(), File(imported, "script.sh").readText())
        assertTrue(File(imported, "script.sh").canExecute())
    }
    @Test fun customLinkedSelectionAndLegacyDisabledIdRemainReadable() {
        val base = tmp.newFolder(); val manager = SkillManager(tmp.newFolder())
        skill(File(base, "source/.agents/skills/fixture")); File(base, "target").mkdirs()
        val config = ProjectCustomizationConfig(projectId = "target", linkedSkills = listOf(link()))
        assertFalse(compile(config.copy(disabledSkillIds = setOf("linked:fixture")), base, manager).single { it.source == SkillSource.LINKED }.isEnabled)
        assertTrue(compile(config.copy(scopeMode = CustomizationScopeMode.CUSTOM, enabledSkillIds = setOf("linked:source:fixture")), base, manager).single { it.source == SkillSource.LINKED }.isEnabled)
    }
    @Test fun manualOnlySkillStillSupportsExplicitInvocation() {
        val manager = SkillManager(tmp.newFolder()); val dir = tmp.newFolder(); val file = skill(dir)
        File(dir, "agents").mkdirs(); File(dir, "agents/openai.yaml").writeText("policy:\n  allow_implicit_invocation: false\n")
        val parsed = manager.parseSkillFile(file, SkillSource.PROJECT)!!.copy(guestFilePath = "/workspace/demo/SKILL.md")
        assertNotNull(SlashCommandEngine.parseSkillInvocation("/fixture request", listOf(parsed)))
    }
    @Test fun yamlObjectsDuplicatesAndOversizedManifestsAreRejected() {
        val manager = SkillManager(tmp.newFolder()); val file = skill(tmp.newFolder())
        file.writeText("---\nname: first\nname: second\n---\nBody")
        assertNull(manager.parseSkillFile(file, SkillSource.PROJECT))
        file.writeText("---\nname: !!java.lang.ProcessBuilder {}\n---\nBody")
        assertNull(manager.parseSkillFile(file, SkillSource.PROJECT))
        file.writeText("x".repeat(SkillFiles.MAX_MANIFEST_BYTES + 1))
        assertNull(manager.parseSkillFile(file, SkillSource.PROJECT))
    }

    @Test fun newlyCreatedSkillDescriptionsRoundTripYamlPunctuation() {
        val manager = SkillManager(tmp.newFolder())
        val created = manager.createGlobalSkill("fixture", "Build: APKs and \"debug\" checks", "Instructions")
        assertEquals(created.description, manager.parseSkillFile(File(created.filePath), SkillSource.GLOBAL)!!.description)
    }

    @Test fun guestLibraryDiscoveryKeepsExistingPrecedenceAndRoots() {
        val manager = SkillManager(tmp.newFolder()); val rootfs = tmp.newFolder()
        val original = skill(File(rootfs, "root/.gemini/config/skills/fixture"))
        skill(File(rootfs, "root/.agents/skills/fixture"))
        skill(File(rootfs, "root/.gemini/skills/legacy"), "legacy")
        val catalog = manager.discoverGlobalAndLinuxSkills(rootfs)
        assertEquals(original.path, catalog.single { it.name == "fixture" }.filePath)
        assertFalse(catalog.any { it.name == "legacy" })
    }

}
