package com.jarves.mh.runtime

import com.jarves.mh.model.SkillInfo
import com.jarves.mh.model.SkillSource
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuestSkillAccessTest {
    @get:Rule val temp = TemporaryFolder()
    private fun skill(dir: File, source: SkillSource): SkillInfo {
        val file = File(dir, "SKILL.md").apply { parentFile!!.mkdirs(); writeText("skill body") }
        return SkillInfo("${source.name}:fixture", "fixture", "description", file.path, source, sourceWorkspacePath = dir.canonicalPath)
    }
    @Test fun projectAndBuiltinPathsUseGuestLocationsWithoutChangingSourcePaths() {
        val files = temp.newFolder(); val rootfs = temp.newFolder(); val workspace = temp.newFolder()
        val local = skill(File(workspace, ".agents/skills/local"), SkillSource.PROJECT)
        val builtin = skill(File(files, "builtin_skills/android"), SkillSource.BUNDLED)
        val actual = GuestSkillAccess(files).prepare(listOf(local, builtin), workspace, rootfs, "/workspace/demo")
        assertEquals("/workspace/demo/.agents/skills/local/SKILL.md", actual[0].guestFilePath)
        assertTrue(actual[1].guestFilePath!!.startsWith("/opt/pocket/skills/linked/"))
        val staged = File(GuestSkillAccess.linkedDirectory(files, workspace), actual[1].guestFilePath!!.removePrefix("/opt/pocket/skills/linked/"))
        assertEquals("skill body", staged.readText())
        staged.writeText("guest edit")
        assertEquals("skill body", File(builtin.filePath).readText())
        assertEquals(builtin.filePath, actual[1].filePath)
    }
    @Test fun guestGlobalSkillsSurviveCredentialMaskingAndProjectCachesStaySeparate() {
        val files = temp.newFolder(); val rootfs = temp.newFolder(); val first = temp.newFolder(); val second = temp.newFolder()
        val global = skill(File(rootfs, "root/.gemini/skills/global"), SkillSource.GLOBAL)
        val access = GuestSkillAccess(files)
        val prepared = access.prepare(listOf(global), first, rootfs, "/workspace/first").single()
        assertTrue(prepared.guestFilePath!!.startsWith("/opt/pocket/skills/linked/"))
        val published = File(GuestSkillAccess.linkedDirectory(files, first), prepared.guestFilePath!!.removePrefix("/opt/pocket/skills/linked/"))
        published.writeText("guest edit")
        val restored = access.prepare(listOf(global), first, rootfs, "/workspace/first").single()
        assertNotEquals(prepared.guestFilePath, restored.guestFilePath)
        assertEquals("skill body", File(GuestSkillAccess.linkedDirectory(files, first), restored.guestFilePath!!.removePrefix("/opt/pocket/skills/linked/")).readText())
        assertEquals("guest edit", published.readText())
        access.prepare(listOf(global), second, rootfs, "/workspace/second")
        assertNotEquals(GuestSkillAccess.linkedDirectory(files, first), GuestSkillAccess.linkedDirectory(files, second))
    }
    @Test fun linkedSkillsIncludeResourcesAndPublishedSnapshotsAreImmutable() {
        val files = temp.newFolder(); val rootfs = temp.newFolder(); val workspace = temp.newFolder()
        val source = temp.newFolder(); val linked = skill(source, SkillSource.LINKED)
        val resource = File(source, "references/details.md").apply { parentFile!!.mkdirs(); writeText("first") }
        File(source, "scripts/run.sh").apply { parentFile!!.mkdirs(); writeText("#!/bin/sh\nexit 0\n"); setExecutable(true, false) }
        val access = GuestSkillAccess(files)
        val first = access.prepare(listOf(linked), workspace, rootfs, "/workspace/demo").single()
        assertNotNull(first.guestFilePath)
        val snapshots = GuestSkillAccess.linkedDirectory(files, workspace)
        val relative = first.guestFilePath!!.removePrefix("/opt/pocket/skills/linked/")
        val published = File(snapshots, relative).parentFile!!
        assertEquals("first", File(published, "references/details.md").readText())
        assertTrue(File(published, "scripts/run.sh").canExecute())
        resource.writeText("second")
        val second = access.prepare(listOf(linked), workspace, rootfs, "/workspace/demo").single()
        assertNotEquals(first.guestFilePath, second.guestFilePath)
        assertEquals("first", File(published, "references/details.md").readText())
        assertEquals(second.guestFilePath, access.prepare(listOf(linked), workspace, rootfs, "/workspace/demo").single().guestFilePath)
    }
    @Test fun disabledMissingAndEscapingResourcesAreNotPublished() {
        val files = temp.newFolder(); val rootfs = temp.newFolder(); val workspace = temp.newFolder()
        val source = temp.newFolder(); val linked = skill(source, SkillSource.LINKED)
        val outside = temp.newFile().apply { writeText("private") }
        Files.createSymbolicLink(File(source, "escape").toPath(), outside.toPath())
        val missing = linked.copy(filePath = File(source, "absent.md").path)
        val result = GuestSkillAccess(files).prepare(listOf(linked, missing, linked.copy(isEnabled = false)), workspace, rootfs, "/workspace/demo")
        assertTrue(result.all { it.guestFilePath == null })
    }
    @Test fun guestCreatedCacheLinksCannotMakeAnUnchangedSkillUnavailable() {
        val files = temp.newFolder(); val workspace = temp.newFolder(); val source = temp.newFolder()
        val linked = skill(source, SkillSource.LINKED); val access = GuestSkillAccess(files)
        val first = access.prepare(listOf(linked), workspace, null, "/workspace/demo").single()
        val snapshots = GuestSkillAccess.linkedDirectory(files, workspace)
        val published = File(snapshots, first.guestFilePath!!.removePrefix("/opt/pocket/skills/linked/")).parentFile!!
        val outside = temp.newFolder()
        val large = File(outside, "external-data")
        java.io.RandomAccessFile(large, "rw").use { it.setLength(64L * 1024 * 1024) }
        Files.createSymbolicLink(File(published, "guest-link").toPath(), outside.toPath())
        val repaired = access.prepare(listOf(linked), workspace, null, "/workspace/demo").single()
        assertNotNull("Cache accounting must not traverse guest-created links", repaired.guestFilePath)
        assertNotEquals(first.guestFilePath, repaired.guestFilePath)
        assertEquals("skill body", File(linked.filePath).readText())
        assertEquals(64L * 1024 * 1024, large.length())
    }
    @Test fun guestGlobalPublicationCannotCopyAnArbitraryRootfsFolder() {
        val files = temp.newFolder(); val rootfs = temp.newFolder(); val workspace = temp.newFolder()
        val outside = skill(File(rootfs, "root/.codex/private-fixture"), SkillSource.GLOBAL)
        val prepared = GuestSkillAccess(files).prepare(listOf(outside), workspace, rootfs, "/workspace/demo")
        org.junit.Assert.assertNull(prepared.single().guestFilePath)
    }

    @Test fun modSkillsRemainExplicitContributionsWithoutPublishingHostPaths() {
        val files = temp.newFolder(); val workspace = temp.newFolder()
        val mod = skill(temp.newFolder(), SkillSource.MOD).copy(markdownContent = "Explicit mod instructions")
        val prepared = GuestSkillAccess(files).prepare(listOf(mod), workspace, null, "/workspace/demo").single()
        assertNull(prepared.guestFilePath)
        assertEquals(mod.markdownContent, prepared.markdownContent)
        assertEquals(mod.filePath, prepared.filePath)
    }

}
