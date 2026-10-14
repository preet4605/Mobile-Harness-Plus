package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBuildOutputTest {
    private val root = "/workspace/notes"

    @Test
    fun stageFollowsGradleTasksAndNeverMovesBack() {
        var stage = AndroidBuildStage.PREPARING
        stage = inferAndroidBuildStage("> Configure project :app\n", stage)
        assertEquals(AndroidBuildStage.CONFIGURING, stage)
        stage = inferAndroidBuildStage("> Task :app:preBuild UP-TO-DATE\n> Task :app:mergeDebugResources\n", stage)
        assertEquals(AndroidBuildStage.RESOURCES, stage)
        stage = inferAndroidBuildStage("> Task :app:compileDebugKotlin\n", stage)
        assertEquals(AndroidBuildStage.COMPILING, stage)
        // A late resource task from another module does not move progress back.
        stage = inferAndroidBuildStage("> Task :lib:processDebugManifest\n", stage)
        assertEquals(AndroidBuildStage.COMPILING, stage)
        stage = inferAndroidBuildStage("> Task :app:dexBuilderDebug\n> Task :app:mergeDexDebug\n", stage)
        assertEquals(AndroidBuildStage.DEXING, stage)
        stage = inferAndroidBuildStage("> Task :app:packageDebug\n> Task :app:assembleDebug\n", stage)
        assertEquals(AndroidBuildStage.PACKAGING, stage)
    }

    @Test
    fun packageResourcesIsNotPackaging() {
        assertEquals(
            AndroidBuildStage.RESOURCES,
            inferAndroidBuildStage("> Task :app:packageDebugResources\n", AndroidBuildStage.PREPARING),
        )
    }

    @Test
    fun kotlinErrorsBecomeClickableIssues() {
        val output = """
            > Task :app:compileDebugKotlin FAILED
            e: file:///workspace/notes/app/src/main/java/com/example/MainActivity.kt:12:5 Unresolved reference 'foo'.
            e: /workspace/notes/app/src/main/java/com/example/Old.kt: (7, 9): Type mismatch
            w: file:///workspace/notes/app/src/main/java/com/example/Warn.kt:1:1 Deprecated
            FAILURE: Build failed with an exception.
        """.trimIndent()

        val issues = parseAndroidBuildIssues(output, root, 1)

        assertEquals(2, issues.size)
        assertEquals("app/src/main/java/com/example/MainActivity.kt", issues[0].filePath)
        assertEquals(12, issues[0].line)
        assertEquals(5, issues[0].column)
        assertEquals("Unresolved reference 'foo'.", issues[0].detail)
        assertEquals("app/src/main/java/com/example/Old.kt", issues[1].filePath)
        assertEquals(7, issues[1].line)
        assertEquals(9, issues[1].column)
    }

    @Test
    fun javaResourceAndBuildScriptErrorsAreParsed() {
        val output = """
            /workspace/notes/app/src/main/java/com/example/A.java:3: error: cannot find symbol
            ERROR: /workspace/notes/app/src/main/res/layout/main.xml:14: AAPT: error: attribute android:foo not found.
            * Where:
            Build file '/workspace/notes/app/build.gradle.kts' line: 20

            * What went wrong:
            Plugin [id: 'com.example.missing'] was not found
            * Try:
        """.trimIndent()

        val issues = parseAndroidBuildIssues(output, root, 1)

        assertEquals(listOf("Java error", "Resource error", "Build script error"), issues.map { it.title })
        assertEquals("app/src/main/java/com/example/A.java", issues[0].filePath)
        assertEquals("cannot find symbol", issues[0].detail)
        assertEquals("app/src/main/res/layout/main.xml", issues[1].filePath)
        assertEquals(14, issues[1].line)
        assertEquals("attribute android:foo not found.", issues[1].detail)
        assertEquals("app/build.gradle.kts", issues[2].filePath)
        assertEquals(20, issues[2].line)
        assertEquals("Plugin [id: 'com.example.missing'] was not found", issues[2].detail)
    }

    @Test
    fun pathsOutsideTheProjectAreNotOpenable() {
        val output = "e: file:///root/.gradle/caches/x/Generated.kt:1:1 Boom\n" +
            "e: file:///workspace/notes/../other/Secret.kt:1:1 Boom"

        val issues = parseAndroidBuildIssues(output, root, 1)

        assertTrue(issues.all { it.filePath == null })
    }

    @Test
    fun failuresWithoutLocationsAreSummarised() {
        val output = """
            FAILURE: Build failed with an exception.

            * What went wrong:
            Could not resolve all files for configuration ':app:debugRuntimeClasspath'.
               > Could not resolve androidx.core:core-ktx:1.99.0.
            * Try:
        """.trimIndent()

        val issues = parseAndroidBuildIssues(output, root, 1)

        assertEquals(1, issues.size)
        assertNull(issues[0].filePath)
        assertEquals("A dependency could not be downloaded. Check the connection and retry.", issues[0].title)
        assertTrue(issues[0].detail.startsWith("Could not resolve all files"))
        assertEquals("Gradle build failed (exit code 137).", androidBuildFailureSummary("Killed", 137))
    }

    @Test
    fun estimateUsesTheMedianOfRecentBuilds() {
        assertNull(typicalAndroidBuildMillis(emptyList()))
        assertEquals(60_000L, typicalAndroidBuildMillis(listOf(300_000L, 50_000L, 60_000L)))
        assertNull(androidBuildEstimateText(running = false, elapsedMillis = 0L, typicalMillis = null))
        assertEquals("Timing this first build", androidBuildEstimateText(true, 5_000L, null))
        assertEquals("Usually about 1 min 30 s", androidBuildEstimateText(false, 0L, 90_000L))
        assertEquals("About 40 s remaining", androidBuildEstimateText(true, 50_000L, 90_000L))
        assertEquals("Taking longer than usual", androidBuildEstimateText(true, 95_000L, 90_000L))
        assertEquals("2 min", formatBuildDuration(120_000L))
        assertEquals("1 s", formatBuildDuration(10L))
    }
}
