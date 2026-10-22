package com.magpie.pocket.core.design

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.magpie.pocket.core.model.GlassTier

/**
 * Calm press feedback: at most 2% scale, never springy, and disabled for reduced motion.
 * It deliberately avoids the oversized bounce used by the previous iOS-inspired chrome.
 */
fun Modifier.springPress(
    enabled: Boolean = true,
    scaleDown: Float = 0.98f,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val preferences = MagpieTheme.preferences
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled && !preferences.reduceMotion) scaleDown.coerceAtLeast(0.98f) else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "calmPressScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                )
            } else Modifier
        )
}

/**
 * Sparse translucent control surface used for navigation and controls only.
 * This is a readable tinted material, not an optical blur claim.
 * A restrained neutral shadow plus a top highlight separates floating controls from
 * the page without turning them into outlined boxes.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    tier: GlassTier = MagpieTheme.preferences.glassTier,
    onClick: (() -> Unit)? = null,
    elevation: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    val colors = MagpieTheme.colors
    val background = when (tier) {
        GlassTier.CLEAR -> colors.glassTint.copy(alpha = colors.glassAlpha)
        GlassTier.TINTED -> colors.glassTint.copy(alpha = 0.90f)
        GlassTier.SOLID -> colors.surface
    }
    val border = if (colors.isDark) {
        Color.White.copy(alpha = 0.08f)
    } else {
        Color.White.copy(alpha = 0.86f)
    }
    val shadowColor = if (colors.isDark) Color.Black.copy(alpha = 0.42f) else Color(0xFF2D3150)
    val shadowAlpha = if (colors.isDark) 0.36f else 0.10f

    Box(
        modifier = modifier
            .then(if (onClick != null) Modifier.springPress(onClick = onClick) else Modifier)
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = shadowColor.copy(alpha = shadowAlpha * 0.55f),
                spotColor = shadowColor.copy(alpha = shadowAlpha)
            )
            .clip(shape)
            .background(background)
            .border(0.75.dp, border, shape)
    ) {
        // Thin top highlight: a quiet material cue, not a heavy outline.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (colors.isDark) 0.04f else 0.34f),
                        0.22f to Color.Transparent
                    )
                )
        )
        content()
    }
}

/** Opaque data panel. Data stays crisp and never sits under a blur effect. */
@Composable
fun DataPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(MagpieDimensions.RadiusDataPanel),
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit
) {
    val colors = MagpieTheme.colors
    Box(
        modifier = modifier
            .then(if (onClick != null) Modifier.springPress(onClick = onClick) else Modifier)
            .clip(shape)
            .background(colors.surface)
            .border(0.75.dp, colors.divider.copy(alpha = 0.72f), shape)
    ) {
        Box(modifier = Modifier.padding(contentPadding)) { content() }
    }
}

@Deprecated("Use DataPanel for content. GlassCard is retained only for source compatibility.")
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    tier: GlassTier = MagpieTheme.preferences.glassTier,
    onClick: (() -> Unit)? = null,
    elevation: Dp = 2.5.dp,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit
) {
    DataPanel(
        modifier = modifier,
        shape = shape,
        onClick = onClick,
        contentPadding = contentPadding,
        content = content
    )
}
