package com.jarves.mh.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KitFoundationTest {

    private val density = Density(1f)

    @Test
    fun continuousCorner_rampsIntoAShorterArc() {
        val k = continuousCorner(radius = 20f, requestedSmoothing = 0.6f, budget = 100f)
        assertEquals("the ramp starts (1 + smoothing) × radius from the corner", 32f, k.p, 1e-3f)
        assertTrue(k.a > 0f && k.b > 0f && k.c > 0f && k.d > 0f)
        // The pieces add back up to the start distance along each edge.
        assertEquals(k.p, k.a + k.b + k.c + k.arcSectionLength + k.d, 1e-3f)
        val plain = continuousCorner(radius = 20f, requestedSmoothing = 0f, budget = 100f)
        assertEquals(20f, plain.p, 1e-3f)
    }

    @Test
    fun continuousCorner_givesUpSmoothingWhenThereIsNoRoom() {
        val k = continuousCorner(radius = 20f, requestedSmoothing = 0.6f, budget = 24f)
        assertEquals(24f, k.p, 1e-3f)
        assertEquals(k.p, k.a + k.b + k.c + k.arcSectionLength + k.d, 1e-3f)
    }

    @Test
    fun continuousShape_staysInsideItsBoundsAndBecomesACapsule() {
        val size = Size(200f, 80f)
        val outline = ContinuousRoundedShape(20.dp).createOutline(size, LayoutDirection.Ltr, density)
        val bounds = (outline as Outline.Generic).path.getBounds()
        assertEquals(0f, bounds.left, 0.5f)
        assertEquals(0f, bounds.top, 0.5f)
        assertEquals(200f, bounds.right, 0.5f)
        assertEquals(80f, bounds.bottom, 0.5f)
        val capsule = ContinuousRoundedShape(40.dp).createOutline(size, LayoutDirection.Ltr, density)
        assertTrue("radius at half the height is a capsule", capsule !is Outline.Generic)
        assertEquals(20f, ContinuousRoundedShape(20.dp).cornerRadiusPx(size, density)!!, 1e-3f)
        assertEquals(40f, PocketShape.capsule.cornerRadiusPx(size, density)!!, 1e-3f)
    }

    @Test
    fun typeRoles_descendAndBodyIsSeventeen() {
        val roles = listOf(
            PocketType.largeTitle, PocketType.title1, PocketType.title2, PocketType.title3, PocketType.body,
            PocketType.callout, PocketType.subheadline, PocketType.footnote, PocketType.caption1, PocketType.caption2,
        )
        roles.zipWithNext().forEach { (a, b) -> assertTrue(a.fontSize.value > b.fontSize.value) }
        assertEquals(17f, PocketType.body.fontSize.value)
        assertEquals(PocketType.body.fontSize, PocketType.headline.fontSize)
        assertTrue(roles.all { it.lineHeight.value >= it.fontSize.value })
    }

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun colorRoles_keepTextReadableOnGroupedCards() {
        listOf(LightColorRoles, DarkColorRoles).forEach { roles ->
            assertTrue(contrast(roles.label, roles.groupedSurface) >= 7f)
            assertTrue("secondary text AA", contrast(roles.secondaryLabel, roles.groupedSurface) >= 4.5f)
            assertTrue("secondary text AA on canvas", contrast(roles.secondaryLabel, roles.groupedBackground) >= 4.5f)
            assertTrue(roles.fill.alpha > roles.secondaryFill.alpha && roles.secondaryFill.alpha > roles.tertiaryFill.alpha)
        }
        assertEquals(LightColors.primary, LightColorRoles.accent)
        assertEquals(DarkColors.primary, DarkColorRoles.accent)
    }
}
