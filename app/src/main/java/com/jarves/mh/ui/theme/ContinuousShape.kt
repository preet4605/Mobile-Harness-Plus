package com.jarves.mh.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Rounded rectangle with continuous ("squircle") corners: the curvature ramps up from the
 * straight edge into a shorter circular arc instead of jumping straight to the arc, which is
 * what makes a corner read as smooth rather than stamped. [smoothing] 0 is a plain rounded
 * rectangle; 0.6 matches the corners of modern phone UIs.
 *
 * When the corner reaches half the short side the shape becomes a capsule.
 */
@Immutable
class ContinuousRoundedShape(
    val cornerRadius: Dp,
    val smoothing: Float = DefaultSmoothing,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { cornerRadius.toPx() }
        val budget = min(size.width, size.height) / 2f
        if (radius <= 0f || size.minDimension <= 0f) return Outline.Rectangle(size.toRect())
        if (radius >= budget) return CircleShape.createOutline(size, layoutDirection, density)
        return Outline.Generic(continuousRoundedRectPath(size, radius, smoothing))
    }

    override fun equals(other: Any?): Boolean =
        other is ContinuousRoundedShape && other.cornerRadius == cornerRadius && other.smoothing == smoothing

    override fun hashCode(): Int = cornerRadius.hashCode() * 31 + smoothing.hashCode()

    override fun toString(): String = "ContinuousRoundedShape($cornerRadius, smoothing=$smoothing)"

    companion object {
        const val DefaultSmoothing: Float = 0.6f
    }
}

private fun Size.toRect() = androidx.compose.ui.geometry.Rect(Offset.Zero, this)

/** Corner geometry for one corner (lengths in px). See [continuousRoundedRectPath]. */
internal data class ContinuousCorner(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    /** Distance from the corner along each edge where the curve starts. */
    val p: Float,
    val arcSectionLength: Float,
    val radius: Float,
)

internal fun continuousCorner(radius: Float, requestedSmoothing: Float, budget: Float): ContinuousCorner {
    var smoothing = requestedSmoothing.coerceIn(0f, 1f)
    var p = (1f + smoothing) * radius
    if (p > budget) {
        // Not enough room for the full ramp: trade smoothing for room, keeping the radius.
        smoothing = (budget / radius - 1f).coerceIn(0f, smoothing)
        p = min(p, budget)
    }
    val arcMeasure = 90f * (1f - smoothing)
    val arcSectionLength = (sin(rad(arcMeasure / 2f)) * radius * sqrt(2f))
    val angleAlpha = (90f - arcMeasure) / 2f
    val p3ToP4 = radius * tan(rad(angleAlpha / 2f))
    val angleBeta = 45f * smoothing
    val c = p3ToP4 * cos(rad(angleBeta))
    val d = c * tan(rad(angleBeta))
    val b = (p - arcSectionLength - c - d) / 3f
    val a = 2f * b
    return ContinuousCorner(a, b, c, d, p, arcSectionLength, radius)
}

private fun rad(degrees: Float): Float = (degrees * PI / 180.0).toFloat()

/**
 * Builds the outline clockwise from the top edge. Each corner is: a cubic ramp from the edge,
 * a circular arc (as one cubic) around 45°, and the mirrored ramp onto the next edge.
 */
internal fun continuousRoundedRectPath(size: Size, radius: Float, smoothing: Float): Path {
    val w = size.width
    val h = size.height
    val k = continuousCorner(radius, smoothing, min(w, h) / 2f)
    val path = Path()
    path.moveTo(k.p, 0f)
    // Corners in order: top-right, bottom-right, bottom-left, top-left. Each has the corner
    // point, the direction travelled into it (u) and the direction travelled out of it (v).
    val corners = listOf(
        Triple(Offset(w, 0f), Offset(1f, 0f), Offset(0f, 1f)),
        Triple(Offset(w, h), Offset(0f, 1f), Offset(-1f, 0f)),
        Triple(Offset(0f, h), Offset(-1f, 0f), Offset(0f, -1f)),
        Triple(Offset(0f, 0f), Offset(0f, -1f), Offset(1f, 0f)),
    )
    for ((corner, u, v) in corners) {
        val start = corner - u * k.p
        path.lineTo(start.x, start.y)
        appendCorner(path, start, u, v, k)
    }
    path.close()
    return path
}

private fun appendCorner(path: Path, start: Offset, u: Offset, v: Offset, k: ContinuousCorner) {
    fun at(origin: Offset, along: Float, across: Float) = origin + u * along + v * across

    // Ramp from the edge into the arc.
    val c1 = at(start, k.a, 0f)
    val c2 = at(start, k.a + k.b, 0f)
    val arcStart = at(start, k.a + k.b + k.c, k.d)
    path.cubicTo(c1.x, c1.y, c2.x, c2.y, arcStart.x, arcStart.y)

    // Arc centred on the corner circle, approximated by one cubic (error < 0.03% for ≤ 90°).
    val arcEnd = at(arcStart, k.arcSectionLength, k.arcSectionLength)
    val corner = start + u * k.p
    val center = corner - u * k.radius + v * k.radius
    if (k.arcSectionLength > 0f) {
        val sweep = 2f * kotlin.math.asin((k.arcSectionLength * sqrt(2f) / 2f / k.radius).coerceIn(-1f, 1f))
        val handle = 4f / 3f * tan(sweep / 4f) * k.radius
        val chord = arcEnd - arcStart
        val t0 = tangentToward(arcStart - center, chord)
        val t1 = tangentToward(arcEnd - center, chord)
        val h1 = arcStart + t0 * handle
        val h2 = arcEnd - t1 * handle
        path.cubicTo(h1.x, h1.y, h2.x, h2.y, arcEnd.x, arcEnd.y)
    }

    // Mirrored ramp out of the arc onto the next edge.
    val c3 = at(arcEnd, k.d, k.c)
    val c4 = at(arcEnd, k.d, k.b + k.c)
    val end = at(arcEnd, k.d, k.a + k.b + k.c)
    path.cubicTo(c3.x, c3.y, c4.x, c4.y, end.x, end.y)
}

/** Unit tangent of a circle at the point with [radial] offset, pointing along [travel]. */
private fun tangentToward(radial: Offset, travel: Offset): Offset {
    val length = radial.getDistance().takeIf { it > 0f } ?: return Offset.Zero
    val t = Offset(-radial.y / length, radial.x / length)
    return if (t.x * travel.x + t.y * travel.y >= 0f) t else -t
}

/** Corner radius in px of a shape the glass shader can model, or null for other shapes. */
internal fun Shape.cornerRadiusPx(size: Size, density: Density): Float? = when (this) {
    is ContinuousRoundedShape -> with(density) { cornerRadius.toPx() }.coerceAtMost(size.minDimension / 2f)
    is androidx.compose.foundation.shape.CornerBasedShape ->
        topStart.toPx(size, density).coerceAtMost(size.minDimension / 2f)
    androidx.compose.ui.graphics.RectangleShape -> 0f
    else -> null
}

/** Continuous-corner shapes on the [PocketRadius] scale, plus a capsule. */
object PocketShape {
    val xs: Shape = ContinuousRoundedShape(PocketRadius.xs)
    val sm: Shape = ContinuousRoundedShape(PocketRadius.sm)
    val md: Shape = ContinuousRoundedShape(PocketRadius.md)
    val lg: Shape = ContinuousRoundedShape(PocketRadius.lg)
    val xl: Shape = ContinuousRoundedShape(PocketRadius.xl)
    val capsule: Shape = ContinuousRoundedShape(PocketRadius.full)

    /** Small inline fills such as inline code and badges. */
    val inline: Shape = ContinuousRoundedShape(5.dp)

    /** Top corners only, for sheets attached to the bottom edge. */
    fun top(radius: Dp): Shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = radius, topEnd = radius)
}
