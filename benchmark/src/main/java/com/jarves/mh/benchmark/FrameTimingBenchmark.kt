package com.jarves.mh.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing for the paths where glass and motion cost the most: scrolling the Projects list
 * under the glass bars, opening and closing a project (push transition and title morph), and
 * scrolling a chat under the composer. The budget is no missed frames at the panel's refresh
 * rate (frameOverrunMs <= 0 at P90).
 *
 * Needs the benchmark build of the app (com.jarves.mh.bench) set up once by hand: finish the
 * runtime setup and create at least one project with a chat, then run the benchmark.
 */
@RunWith(AndroidJUnit4::class)
class FrameTimingBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    private fun MacrobenchmarkScope.openProjects() {
        pressHome()
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.text("Projects")), 15_000)) {
            "Projects did not appear: finish setup in the benchmark app first"
        }
    }

    private fun MacrobenchmarkScope.list(): UiObject2 =
        device.findObject(By.scrollable(true))?.also { it.setGestureMargin(device.displayWidth / 5) }
            ?: error("No scrolling list on screen")

    private fun MacrobenchmarkScope.firstProject(): UiObject2 =
        device.findObject(By.clickable(true).hasDescendant(By.textContains(" ago")))
            ?: error("No project row: create a project in the benchmark app first")

    private fun MacrobenchmarkScope.openFirstProject() {
        firstProject().click()
        check(device.wait(Until.hasObject(By.desc("Chat")), 10_000)) { "Workspace did not open" }
    }

    private fun measure(name: String, setup: MacrobenchmarkScope.() -> Unit, block: MacrobenchmarkScope.() -> Unit) =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.DEFAULT,
            startupMode = StartupMode.WARM,
            iterations = ITERATIONS,
            setupBlock = setup,
            measureBlock = block,
        ).also { println("$name done") }

    @Test
    fun scrollProjects() = measure("scrollProjects", { openProjects() }) {
        val list = list()
        repeat(3) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
            list.fling(Direction.UP)
            device.waitForIdle()
        }
    }

    @Test
    fun openAndCloseProject() = measure("openAndCloseProject", { openProjects() }) {
        repeat(2) {
            openFirstProject()
            device.waitForIdle()
            device.pressBack()
            device.wait(Until.hasObject(By.text("Projects")), 5_000)
            device.waitForIdle()
        }
    }

    @Test
    fun scrollChat() = measure("scrollChat", {
        openProjects()
        openFirstProject()
    }) {
        val list = list()
        repeat(3) {
            list.fling(Direction.UP)
            device.waitForIdle()
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE = "com.jarves.mh.bench"
        const val ITERATIONS = 5
    }
}
