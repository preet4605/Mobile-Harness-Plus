package com.jarves.mh.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude Code's credential file lives inside the private runtime filesystem under `filesDir`.
 * These checks keep that data out of Android cloud backup and device-to-device transfer.
 */
class ClaudeCredentialBackupTest {
    private fun moduleFile(path: String): File {
        // Unit tests run from the module directory; also tolerate a repo-root working directory.
        return listOf(File(path), File("app/$path")).first { it.isFile }
    }

    @Test
    fun `app data is excluded from cloud backup and device transfer`() {
        val rules = moduleFile("src/main/res/xml/data_extraction_rules.xml").readText()
        val cloud = rules.substringAfter("<cloud-backup").substringBefore("</cloud-backup>")
        val transfer = rules.substringAfter("<device-transfer").substringBefore("</device-transfer>")
        listOf(cloud, transfer).forEach { section ->
            assertTrue("files dir must be excluded", Regex("<exclude\\s+domain=\"file\"\\s+path=\"\\.\"").containsMatchIn(section))
            assertTrue("root domain must be excluded", Regex("<exclude\\s+domain=\"root\"\\s+path=\"\\.\"").containsMatchIn(section))
        }
    }

    @Test
    fun `legacy auto backup is disabled`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }
}
