package com.jarves.mh.ui

import com.jarves.mh.model.CustomizationScopeMode
import com.jarves.mh.model.ProjectCustomizationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M1: customization handlers hand their disk scan to the IO dispatcher instead of running it on Main. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelCustomizationThreadTest {
    private val app = robolectricApplication()

    @Test
    fun toggleRuleQueuesItsScanForIoInsteadOfRunningItOnMain() {
        val project = testProject("Rules")
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        // The config is the one the project loads with, so a saved change reads back under the same project id.
        viewModel.updateStateForTest {
            it.copy(activeProject = project, activeCustomizationConfig = ProjectCustomizationConfig(project.id))
        }

        viewModel.toggleRule("rule-1")

        assertTrue("the rule scan must be queued for IO", io.pendingCount > 0)
        io.runAll()
        assertEquals(setOf("rule-1"), viewModel.state.value.activeCustomizationConfig.disabledRuleIds)
    }

    @Test
    fun setScopeModeQueuesItsScanForIoInsteadOfRunningItOnMain() {
        val project = testProject("Scope")
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        // The config is the one the project loads with, so a saved change reads back under the same project id.
        viewModel.updateStateForTest {
            it.copy(activeProject = project, activeCustomizationConfig = ProjectCustomizationConfig(project.id))
        }

        viewModel.setCustomizationScopeMode(CustomizationScopeMode.CUSTOM)

        assertTrue("the scope scan must be queued for IO", io.pendingCount > 0)
        io.runAll()
        assertEquals(CustomizationScopeMode.CUSTOM, viewModel.state.value.activeCustomizationConfig.scopeMode)
    }

    @Test
    fun reloadStartedForOneProjectDoesNotPublishAfterTheProjectChanges() {
        val first = testProject("First")
        val second = testProject("Second")
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.updateStateForTest { it.copy(activeProject = first) }
        viewModel.reloadCustomizations(first)

        viewModel.updateStateForTest { it.copy(activeProject = second) }
        io.runAll()

        assertNotEquals(first.id, viewModel.state.value.activeCustomizationConfig.projectId)
    }
}
