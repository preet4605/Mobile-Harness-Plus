package com.jarves.mh.ui.theme.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.jarves.mh.ui.kit.GlassToolbarButton
import com.jarves.mh.ui.kit.GlassToolbarGroup
import com.jarves.mh.ui.kit.GlassToolbarItem
import com.jarves.mh.ui.theme.AppThemeMode
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketTheme
import com.jarves.mh.ui.theme.PocketType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Opt-in glass renders over busy content, per tier: ./gradlew :app:testOnlineDebugUnitTest -Pscreenshots
 * Robolectric does not run RenderEffect blur or AGSL faithfully, so these check layout, wash,
 * rim and fallbacks; the lens itself is judged on a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class GlassScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Composable
    private fun Busy() {
        val colors = listOf(Color(0xFFFF6B6B), Color(0xFFFFD93D), Color(0xFF6BCB77), Color(0xFF4D96FF), Color(0xFF9B5DE5))
        Column(Modifier.fillMaxSize().hostBackdropSource()) {
            repeat(14) { i ->
                Box(Modifier.fillMaxWidth().height(60.dp).background(colors[i % colors.size]), contentAlignment = Alignment.CenterStart) {
                    Text("Row $i · The quick brown fox jumps over the lazy dog", style = PocketType.body, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }

    private fun shot(name: String, dark: Boolean, config: LiquidGlassConfig) {
        compose.setContent {
            PocketTheme(if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                LiquidGlassHost(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), config = config) { Box(Modifier.fillMaxSize()) {
                    Busy()
                    Row(Modifier.fillMaxWidth().padding(16.dp).offset(y = 40.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        GlassToolbarButton(Icons.Outlined.Search, "Search", {})
                        GlassToolbarGroup {
                            GlassToolbarItem(Icons.Outlined.Add, "Add", {})
                            GlassToolbarItem(Icons.Outlined.MoreHoriz, "More", {})
                        }
                    }
                    LiquidGlassSurface(
                        modifier = Modifier.align(Alignment.Center).size(300.dp, 64.dp),
                        shape = PocketShape.capsule,
                        layerSource = LiquidGlassLayers.Background,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Glass capsule", style = PocketType.headline) }
                    }
                    GlassGroup(Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LiquidGlassSurface(Modifier.size(220.dp, 56.dp), shape = PocketShape.capsule, layerSource = LiquidGlassLayers.Background) {}
                            LiquidGlassSurface(Modifier.size(56.dp), shape = PocketShape.capsule, layerSource = LiquidGlassLayers.Background) {}
                        }
                    }
                } }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        captureScreenRoboImage("${System.getProperty("roborazzi.output.dir")}/glass-$name-${if (dark) "dark" else "light"}.png")
    }

    @Test fun glassLight() = shot("default", false, LiquidGlassConfig())
    @Test fun glassDark() = shot("default", true, LiquidGlassConfig())
    @Test fun reduceTransparencyLight() = shot("reduce-transparency", false, LiquidGlassConfig(reduceTransparency = true))
    @Test fun increaseContrastDark() = shot("increase-contrast", true, LiquidGlassConfig(increaseContrast = true))
}
