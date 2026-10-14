package com.jarves.mh.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Glass work that streamed output used to trigger on every chunk or every frame. */
class GlassStreamingWorkTest {
    private fun source(path: String) = File("src/main/java/com/jarves/mh/ui/$path").readText()

    @Test fun spinningRingRotatesItsOwnLayer() {
        val ring = source("kit/Feedback.kt").substringAfter("fun ProgressRing(").substringBefore("/** A centred symbol")
        assertTrue(ring.contains(".graphicsLayer { rotationZ = angle }"))
        assertFalse("reading the angle while drawing redraws the parent every frame", ring.contains("rotate(angle)"))
    }

    @Test fun autoFollowRecapturesAtTheContentRate() {
        val workspace = source("WorkspaceScreen.kt")
        val effect = workspace.substringAfter("snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }")
            .substringBefore("// Older messages stay on disk")
        assertTrue(effect.contains(".conflate()"))
        assertTrue(effect.contains("if (!listState.isScrollInProgress) delay(CONTENT_RECAPTURE_INTERVAL_MS)"))
        assertTrue(workspace.contains("val tabs = remember(modPanes) {"))
    }

    @Test fun equalGlassLooksAreEqual() {
        assertTrue(source("theme/glass/GlassEngine.kt").contains("internal data class GlassLook("))
    }
}
