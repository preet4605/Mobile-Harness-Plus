package com.jarves.mh.runtime

import com.jarves.mh.model.RuntimeEvent
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RemainingResourceUiRegressionTest {
    @Test fun inspectorDoesNotPretendToControlProviderChildren() {
        val vm = source("ui/MainViewModel.kt")
        val controls = vm.substringAfter("fun terminateSubagent").substringBefore("fun clearCompletedTasks")
        assertFalse(controls.contains("Registry.terminate"))
        assertTrue(controls.contains("does not support"))
        assertFalse(source("ui/CliParityComponents.kt").contains("?.readLines()"))
    }
    @Test fun attachmentPublicationAndPreviewAreOwned() {
        val vm = source("ui/MainViewModel.kt").substringAfter("fun addChatAttachments").substringBefore("fun removePendingAttachment")
        assertTrue(vm.contains("state.activeProject?.id != project.id"))
        assertTrue(vm.contains("state.activeChatId != chatId"))
        assertTrue(vm.contains("state.isRunning"))
        val preview = source("ui/WorkspaceScreen.kt").substringAfter("private fun PreviewTab")
        assertFalse(preview.contains("current.url != targetUrl"))
        assertTrue(preview.contains("onRelease"))
    }
    private fun source(path: String): String {
        val root = if (File("src/main/java").isDirectory) File("src/main/java/com/jarves/mh") else File("app/src/main/java/com/jarves/mh")
        return File(root, path).readText()
    }
}
