package com.jarves.mh.ui

import androidx.lifecycle.ViewModelStore
import com.jarves.mh.mods.ModHost
import com.jarves.mh.mods.ModRegistry
import com.jarves.mh.mods.ModRoles
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModsViewModelTest {
    private val app = robolectricApplication()
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        // Main.immediate and already-completed IO reproduce opening Mods after the host warmed up.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        resetModHostSingleton()
        resetModRegistrySingleton()
    }

    @After
    fun tearDown() {
        store.clear()
        resetModHostSingleton()
        resetModRegistrySingleton()
        Dispatchers.resetMain()
    }

    @Test
    fun eagerHostInitialValuesAndLaterUpdatesReachTheScreen() = runTest {
        val live = ModHost.getInstance(app)
        val initialChoice = ModRoles.Choice(harness = "codex", model = "initial")
        live.roles.setChoice("demo", "coder", initialChoice)
        live.clearDashboardChat("demo")

        val viewModel = ModsViewModel(app, Dispatchers.Unconfined)
        store.put("mods", viewModel)

        assertEquals(live.dashboardChats.value, viewModel.chats.value)
        assertEquals(initialChoice, viewModel.roleChoices.value["demo"]?.get("coder"))
        val updatedChoice = initialChoice.copy(model = "updated")
        live.roles.setChoice("demo", "coder", updatedChoice)
        live.clearDashboardChat("second")
        assertEquals(updatedChoice, viewModel.roleChoices.value["demo"]?.get("coder"))
        assertTrue(viewModel.chats.value.containsKey("second"))

        store.clear()
        live.roles.setChoice("demo", "coder", initialChoice)
        assertEquals("cleared screens stop collecting", updatedChoice, viewModel.roleChoices.value["demo"]?.get("coder"))
    }

    @Test
    fun failedPinWriteShowsAnErrorAndReleasesBusyState() = runTest {
        // Other tests can initialize process singletons with a different filesDir or cancel the
        // host scope. Pin persistence must exercise a fresh registry rooted at this app instance.
        val registry = ModRegistry.getInstance(app)
        registry.setPinned(null)
        val viewModel = ModsViewModel(app, Dispatchers.Unconfined)
        store.put("mods", viewModel)
        val banner = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.first() }
        // A directory at the atomic temporary-file path deterministically fails the disk write.
        val blockedWrite = File(app.filesDir, "mods/_state.json.tmp")
        assertTrue(blockedWrite.parentFile!!.isDirectory || blockedWrite.parentFile!!.mkdirs())
        assertTrue(blockedWrite.mkdir())
        try {
            viewModel.setPinned("demo")
            assertTrue(banner.await().startsWith("Could not pin that dashboard:"))
            assertFalse(viewModel.busy.value)
            assertEquals(null, viewModel.snapshot.value.pinned)
        } finally {
            assertTrue(blockedWrite.delete())
        }

        viewModel.setPinned("demo")
        assertEquals("a failed save leaves the registry state retryable", "demo", viewModel.snapshot.value.pinned)
    }

    private fun resetModHostSingleton() {
        val instanceField = ModHost::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val existing = instanceField.get(null) as? ModHost
        existing?.let {
            val scopeField = ModHost::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scopeField.get(it) as CoroutineScope).cancel()
        }
        instanceField.set(null, null)
        val currentField = ModHost::class.java.getDeclaredField("_current").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (currentField.get(null) as kotlinx.coroutines.flow.MutableStateFlow<ModHost?>).value = null
    }

    private fun resetModRegistrySingleton() {
        ModRegistry::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
    }
}
