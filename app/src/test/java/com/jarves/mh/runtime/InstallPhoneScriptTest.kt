package com.jarves.mh.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallPhoneScriptTest {
    @Test fun launchesTheComponentTheDebugBuildInstallsInsteadOfTheStablePackage() {
        val root = if (File("src/main/java").isDirectory) File("..") else File(".")
        val script = File(root, "scripts/install-phone.sh").readText()
        val launch = script.lines().single { it.contains("am start") }
        assertFalse("launch line hard-codes the stable package: $launch", launch.contains("com.jarves.mh/"))
        assertTrue(script.contains("app/src/main/AndroidManifest.xml"))
        assertTrue(script.contains("app/build.gradle.kts"))
        assertTrue(script.contains(".dev"))
    }
}
