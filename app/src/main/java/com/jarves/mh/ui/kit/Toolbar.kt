package com.jarves.mh.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.glass.LiquidGlassLayers
import com.jarves.mh.ui.theme.glass.LiquidGlassSurface
import com.jarves.mh.ui.theme.glass.LiquidGlassTokens

/** A 44 dp glass circle holding one symbol, for bar actions (back, add, more). */
@Composable
fun GlassToolbarButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    prominent: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    LiquidGlassSurface(
        modifier = modifier.size(LiquidGlassTokens.MinTouchTarget),
        shape = PocketShape.capsule,
        layerSource = LiquidGlassLayers.Background,
        tint = if (prominent) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else null,
        onClick = if (enabled) onClick else null,
        onLongClick = onLongClick,
        semanticRole = Role.Button,
        contentDescription = contentDescription,
    ) {
        Box(Modifier.size(LiquidGlassTokens.MinTouchTarget), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                tint = when {
                    prominent -> Color.White
                    enabled -> tint
                    else -> tint.copy(alpha = 0.35f)
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Two or three related bar actions joined into one glass capsule. Items are [GlassToolbarItem]s.
 */
@Composable
fun GlassToolbarGroup(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    LiquidGlassSurface(
        modifier = modifier.height(LiquidGlassTokens.MinTouchTarget),
        shape = PocketShape.capsule,
        layerSource = LiquidGlassLayers.Background,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** One symbol inside a [GlassToolbarGroup], 44 dp square. */
@Composable
fun GlassToolbarItem(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .widthIn(min = LiquidGlassTokens.MinTouchTarget)
            .height(LiquidGlassTokens.MinTouchTarget)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(22.dp),
        )
    }
}
