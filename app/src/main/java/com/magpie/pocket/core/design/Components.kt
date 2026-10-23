package com.magpie.pocket.core.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magpie.pocket.core.model.DataMode
import com.magpie.pocket.core.model.FinalState
import com.magpie.pocket.core.model.HourlyTrendPoint

data class SwitchPalette(
    val checkedTrack: Color,
    val checkedThumb: Color,
    val uncheckedTrack: Color,
    val uncheckedThumb: Color,
    val border: Color,
    val disabledTrack: Color,
    val disabledThumb: Color
)

fun switchPalette(colors: MagpieColorScheme): SwitchPalette = SwitchPalette(
    checkedTrack = colors.accent,
    // A white thumb on the light accent and the dark page background on the
    // pastel dark accent both keep the thumb/track separation explicit.
    checkedThumb = if (colors.isDark) MagpieColors.DarkBackground else Color.White,
    uncheckedTrack = colors.surfaceSubtle,
    uncheckedThumb = colors.textSecondary,
    border = colors.textTertiary.copy(alpha = 0.72f),
    disabledTrack = colors.surfaceSubtle.copy(alpha = 0.55f),
    disabledThumb = colors.textTertiary.copy(alpha = 0.7f)
)

@Composable
fun MagpieSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = MagpieTheme.colors
    val palette = switchPalette(colors)
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier
            .sizeIn(
                minWidth = MagpieDimensions.TouchTargetMinimum,
                minHeight = MagpieDimensions.TouchTargetMinimum
            )
            .semantics {
                contentDescription = label
                stateDescription = if (checked) "已开启" else "已关闭"
                role = Role.Switch
            },
        colors = SwitchDefaults.colors(
            checkedThumbColor = palette.checkedThumb,
            checkedTrackColor = palette.checkedTrack,
            checkedBorderColor = palette.border,
            uncheckedThumbColor = palette.uncheckedThumb,
            uncheckedTrackColor = palette.uncheckedTrack,
            uncheckedBorderColor = palette.border,
            disabledCheckedThumbColor = palette.disabledThumb,
            disabledCheckedTrackColor = palette.disabledTrack,
            disabledCheckedBorderColor = palette.border.copy(alpha = 0.5f),
            disabledUncheckedThumbColor = palette.disabledThumb,
            disabledUncheckedTrackColor = palette.disabledTrack,
            disabledUncheckedBorderColor = palette.border.copy(alpha = 0.5f)
        )
    )
}

enum class AppTab(val label: String) {
    OVERVIEW("概览"),
    ROUTING("路由"),
    USAGE("用量"),
    REQUESTS("请求")
}

/** One floating capsule, four equal slots, icon above an always-visible label. */
@Composable
fun FloatingBottomNav(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MagpieTheme.colors
    val preferences = MagpieTheme.preferences

    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(MagpieDimensions.NavigationHeight),
        shape = RoundedCornerShape(32.dp),
        tier = preferences.glassTier,
        elevation = 10.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppTab.values().forEach { tab ->
                val isSelected = tab == selectedTab
                val targetContentColor = if (isSelected) colors.accent else colors.textSecondary
                val contentColor by animateColorAsState(
                    targetValue = targetContentColor,
                    animationSpec = tween(if (preferences.reduceMotion) 0 else 180),
                    label = "TabColor"
                )

                val targetBgColor = if (isSelected) colors.accent.copy(alpha = 0.07f) else Color.Transparent
                val bgColor by animateColorAsState(
                    targetValue = targetBgColor,
                    animationSpec = tween(if (preferences.reduceMotion) 0 else 180),
                    label = "TabBgColor"
                )

                val targetBorderColor = Color.Transparent
                val borderColor by animateColorAsState(
                    targetValue = targetBorderColor,
                    animationSpec = tween(if (preferences.reduceMotion) 0 else 180),
                    label = "TabBorderColor"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp)
                        .clip(RoundedCornerShape(25.dp))
                        .background(bgColor)
                        .border(0.5.dp, borderColor, RoundedCornerShape(25.dp))
                        .springPress(scaleDown = 0.98f) { onTabSelected(tab) }
                        .padding(horizontal = 2.dp, vertical = 5.dp)
                        .semantics {
                            role = Role.Tab
                            contentDescription = "${tab.label} 页签，${if (isSelected) "已选中" else "未选中"}"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        NavigationMark(tab = tab, tint = contentColor)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = tab.label,
                            color = contentColor,
                            fontSize = MagpieTypography.Navigation,
                            lineHeight = 16.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** Clean brand chrome: one identity target on the left, one theme action on the right. */
@Composable
fun AppChrome(
    connectionName: String,
    mode: DataMode,
    isOnline: Boolean,
    status: com.magpie.pocket.core.model.ConnectionStatus? = null,
    onGatewayClick: () -> Unit,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MagpieTheme.colors
    val statusLabel = when (status?.health) {
        com.magpie.pocket.core.model.ConnectionHealth.CONNECTING -> "连接中"
        com.magpie.pocket.core.model.ConnectionHealth.AUTH_REQUIRED -> "需要 Magpie 登录"
        com.magpie.pocket.core.model.ConnectionHealth.ACCESS_DENIED -> "CF Access 被拒绝"
        com.magpie.pocket.core.model.ConnectionHealth.NETWORK_UNREACHABLE -> "网络不可达"
        com.magpie.pocket.core.model.ConnectionHealth.INCOMPATIBLE -> "响应不兼容"
        com.magpie.pocket.core.model.ConnectionHealth.OFFLINE -> "离线"
        else -> null
    }
    val healthy = statusLabel == null && isOnline

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MagpieDimensions.ChromeHeight)
            .padding(horizontal = MagpieDimensions.PagePadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = MagpieDimensions.TouchTargetMinimum)
                .clip(RoundedCornerShape(16.dp))
                .springPress { onGatewayClick() }
                .padding(horizontal = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(colors.textPrimary),
                contentAlignment = Alignment.Center
            ) {
                BirdMark(tint = colors.background, size = 23.dp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "magpie",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "pocket",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    mode == DataMode.DEMO -> colors.success
                                    healthy -> colors.success
                                    else -> colors.warning
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = when {
                            mode == DataMode.DEMO -> "$connectionName · 演示"
                            statusLabel != null -> "$connectionName · $statusLabel"
                            isOnline -> connectionName
                            else -> "$connectionName · 离线"
                        },
                        fontSize = MagpieTypography.CaptionStyle.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = "打开网关详情",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(MagpieDimensions.TouchTargetMinimum)
                .clip(CircleShape)
                .background(colors.surface.copy(alpha = 0.72f))
                .border(0.75.dp, colors.divider.copy(alpha = 0.7f), CircleShape)
                .springPress { onToggleTheme() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (colors.isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                contentDescription = "切换深浅主题",
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun StatusBadge(
    state: FinalState,
    rerouteCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val colors = MagpieTheme.colors

    val (tintColor, label) = when (state) {
        FinalState.SUCCEEDED -> {
            val text = if (rerouteCount > 0) "成功 · 改道 $rerouteCount 次" else "成功"
            Pair(colors.success, text)
        }
        FinalState.IN_PROGRESS -> {
            Pair(colors.accent, "处理中")
        }
        FinalState.FAILED -> {
            val text = if (rerouteCount > 0) "失败 · 改道 $rerouteCount 次" else "最终失败"
            Pair(colors.error, text)
        }
        FinalState.PENDING -> {
            Pair(colors.textSecondary, "排队中")
        }
        FinalState.CANCELLED -> {
            Pair(colors.textSecondary, "已取消")
        }
        FinalState.UNCONFIRMED -> {
            Pair(colors.warning, "待确认")
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tintColor.copy(alpha = 0.10f))
            .border(0.5.dp, tintColor.copy(alpha = 0.20f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.5.dp)
    ) {
        Text(
            text = label,
            color = tintColor,
            fontSize = MagpieTypography.LabelStyle.fontSize,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Trend Chart with smooth bezier-like line and glowing ambient gradient fill.
 * Seamlessly integrates into liquid glass card without harsh black borders.
 */
@Composable
fun TrendChart(
    points: List<HourlyTrendPoint>,
    modifier: Modifier = Modifier
) {
    val colors = MagpieTheme.colors
    val accentColor = colors.accent

    if (points.isEmpty()) {
        Box(
            modifier = modifier.height(60.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无小时趋势",
                color = colors.textSecondary,
                fontSize = 12.sp
            )
        }
        return
    }

    val maxVal = points.maxOfOrNull { it.completedRequests }?.coerceAtLeast(1L) ?: 1L

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics {
                contentDescription = "今日小时完成量趋势图，峰值为 $maxVal 请求"
            }
    ) {
        val width = size.width
        val height = size.height
        val stepX = if (points.size > 1) width / (points.size - 1) else width

        val path = Path()
        val fillPath = Path()

        points.forEachIndexed { index, point ->
            val x = index * stepX
            val normalizedY = 1.0f - (point.completedRequests.toFloat() / maxVal.toFloat())
            val y = normalizedY * (height - 18.dp.toPx()) + 6.dp.toPx()

            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }

            if (index == points.size - 1) {
                fillPath.lineTo(x, height)
                fillPath.close()
            }
        }

        // Ambient glowing gradient fill
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.22f),
                    accentColor.copy(alpha = 0.04f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = height
            )
        )

        // Sleek trend line
        drawPath(
            path = path,
            color = accentColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun InlineNotice(
    message: String,
    icon: ImageVector = Icons.Filled.Warning,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = MagpieTheme.colors
    val tintColor = if (isError) colors.error else colors.warning

    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = 1.5.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tintColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                color = colors.textPrimary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.accent.copy(alpha = 0.10f))
                        .border(0.5.dp, colors.accent.copy(alpha = 0.20f), RoundedCornerShape(8.dp))
                        .springPress(scaleDown = 0.94f) { onAction() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = actionLabel,
                        color = colors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
