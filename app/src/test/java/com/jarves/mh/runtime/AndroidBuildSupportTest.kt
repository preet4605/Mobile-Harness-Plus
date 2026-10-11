package com.jarves.mh.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBuildSupportTest {
    private val root: File = Files.createTempDirectory("android-build").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun gradleCommandUsesInstalledGradleWithoutWrapper() {
        val command = androidGradleCommand(root, "assembleDebug")

        assertEquals(
            "gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 " +
                "--init-script /root/.gradle/init.d/pocketdev-android.gradle " +
                "-Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 " +
                "assembleDebug --console=plain --stacktrace",
            command,
        )
    }

    @Test
    fun gradleCommandPrefersTheProjectWrapper() {
        File(root, "gradlew").writeText("#!/bin/sh\n")

        val command = androidGradleCommand(root, "clean")

        assertTrue(command.startsWith("bash ./gradlew -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 "))
        assertTrue(command.endsWith(" clean --console=plain --stacktrace"))
    }

    @Test
    fun findsTheNewestDebugApk() {
        val older = writeFile("app/build/outputs/apk/debug/old.apk", "a", time = 1_000_000L)
        val newer = writeFile("app/build/outputs/apk/debug/app-debug.apk", "b", time = 2_000_000L)
        writeFile("app/build/outputs/apk/release/app-release.apk", "c", time = 3_000_000L)

        assertEquals(newer, findDebugApk(root))
        assertTrue(older.isFile)
    }

    @Test
    fun unchangedProjectReusesItsApk() {
        writeFile("app/src/main/java/App.kt", "class App", time = 1_000_000L)
        writeFile("build.gradle.kts", "", time = 1_000_000L)
        val apk = writeFile("app/build/outputs/apk/debug/app-debug.apk", "apk", time = 2_000_000L)
        // Outputs and IDE/VCS folders never count as changes.
        writeFile("app/build/intermediates/x.json", "{}", time = 3_000_000L)
        writeFile(".gradle/cache.properties", "", time = 3_000_000L)
        writeFile(".git/config.xml", "", time = 3_000_000L)
        writeFile("notes.txt", "", time = 3_000_000L)

        assertEquals(apk, findReusableDebugApk(root))
    }

    @Test
    fun anyNewerBuildInputForcesARebuild() {
        listOf(
            "app/src/main/java/App.kt",
            "app/src/main/res/values/strings.xml",
            "gradle/libs.versions.toml",
            "gradle.properties",
            "app/proguard-rules.pro",
            "gradlew",
            "app/src/main/assets/data.bin",
        ).forEach { path ->
            val apk = writeFile("app/build/outputs/apk/debug/app-debug.apk", "apk", time = 2_000_000L)
            val changed = writeFile(path, "x", time = 3_000_000L)

            assertNull(path, findReusableDebugApk(root))
            changed.delete()
            assertEquals(apk, findReusableDebugApk(root))
        }
    }

    @Test
    fun noApkMeansNothingToReuse() {
        writeFile("app/src/main/java/App.kt", "class App", time = 1_000_000L)

        assertNull(findReusableDebugApk(root))
    }

    @Test
    fun apkFingerprintIsTheSha256OfItsBytes() {
        val apk = writeFile("app-debug.apk", "abc", time = 1_000_000L)

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex(apk))
    }

    private fun writeFile(path: String, text: String, time: Long): File =
        File(root, path).apply {
            parentFile?.mkdirs()
            writeText(text)
            setLastModified(time)
        }
}
