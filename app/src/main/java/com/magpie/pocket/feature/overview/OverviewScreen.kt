package com.magpie.pocket.feature.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magpie.pocket.core.design.DataPanel
import com.magpie.pocket.core.design.MagpieDimensions
import com.magpie.pocket.core.design.MagpieTheme
import com.magpie.pocket.core.design.MagpieTypography
import com.magpie.pocket.core.model.AccountQuota
import com.magpie.pocket.core.model.HourlyTrendPoint
import com.magpie.pocket.core.model.OverviewSnapshot
import com.magpie.pocket.core.model.RequestSummary
import com.magpie.pocket.core.model.TraceSnapshot
import com.magpie.pocket.core.util.MetricCalculators
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OverviewScreen(
    overview: OverviewSnapshot?,
    trace: TraceSnapshot?,
    runningRequest: RequestSummary?,
    quotas: List<AccountQuota>,
    isLoading: Boolean,
    onNavigateToRouting: (String?) -> Unit,
    onNavigateToErrors: () -> Unit,
    onNavigateToQuotas: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val colors = MagpieTheme.colors
    val date = SimpleDateFormat("M 月 d 日 · EEEE", Locale.CHINESE).format(Date())
    val successRate = overview?.let { MetricCalculators.calculateSuccessRate(it.completedRequests, it.finalErrors) }
    val ttftMeanMs = overview?.let { MetricCalculators.calculateTtftMean(it.ttftSumMs, it.ttftSampleCount) }
    val alertQuota = quotas.firstOrNull { quota ->
        quota.windows.any { MetricCalculators.isQuotaWarning(it) }
    }
    val yesterday = overview?.sameTimeYesterdayCompletedRequests
    val delta = if (yesterday != null && yesterday > 0L && overview != null) {
        (overview.completedRequests - yesterday).toDouble() / yesterday.toDouble()
    } else null

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = MagpieDimensions.PagePadding),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                ) {
                    Text(
                        text = "一切，尽在掌握",
                        fontSize = MagpieTypography.PageTitle,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(text = date, fontSize = 13.sp, color = colors.textSecondary,
                    lineHeight = 18.sp
                    )
                }
                StatusPill(
                    label = if (isLoading && overview == null) "同步中" else "已连接",
                    color = colors.success
                )
            }
        }

        item { Spacer(modifier = Modifier.height(18.dp)) }

        item {
            DataPanel(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(18.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("今日请求", fontSize = 12.sp, color = colors.textSecondary,
                        lineHeight = 16.sp
                        )
                        Text(
                            text = overview?.rangeEnd?.take(16)?.replace('T', ' ')?.takeLast(5)?.let { "截至 $it" } ?: "今日",
                            fontSize = 11.sp,
                            color = colors.textSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = overview?.completedRequests?.toString() ?: "—",
                            fontSize = MagpieTypography.HeroMetric,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "次",
                            fontSize = 15.sp,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }
                    if (delta != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        StatusPill(
                            label = "${if (delta >= 0) "↑" else "↓"} ${MetricCalculators.formatPercent(kotlin.math.abs(delta))} 较昨日同期",
                            color = colors.success
                        )
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("昨日同期数据不可用", fontSize = 11.sp, color = colors.textSecondary,
                        lineHeight = 16.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OverviewTrendChart(
                        points = overview?.hourlyTrends.orEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricPanel(
                    label = "请求成功率",
                    value = MetricCalculators.formatPercent(successRate),
                    detail = if (overview != null && overview.completedRequests > 0) {
                        "${overview.finalErrors} 次最终错误"
                    } else "暂无完成请求",
                    modifier = Modifier.weight(1f)
                )
                MetricPanel(
                    label = "首响均值",
                    value = MetricCalculators.formatDurationMs(ttftMeanMs?.toLong()),
                    detail = if (overview?.ttftSampleCount != null && overview.ttftSampleCount > 0) {
                        "${overview.ttftSampleCount} 个流式样本"
                    } else "暂无流式样本",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (alertQuota != null) {
            item { SectionLabel("需要留意", "额度") }
            item {
                val window = alertQuota.windows.first { MetricCalculators.isQuotaWarning(it) }
                PlainRow(
                    icon = Icons.Filled.HourglassBottom,
                    title = MetricCalculators.maskAccountName(alertQuota.accountId, true),
                    subtitle = "额度已用 ${MetricCalculators.formatPercent(MetricCalculators.calculateQuotaUsedRatio(window))} · 建议关注可用状态",
                    onClick = onNavigateToQuotas
                )
            }
        }

        item { SectionLabel("当前活动", if (trace?.runningRouteIds?.isNotEmpty() == true) "${trace.runningRouteIds.size} 个请求" else "") }
        item {
            if (runningRequest != null) {
                PlainRow(
                    icon = Icons.Filled.Terminal,
                    title = runningRequest.servedModel ?: runningRequest.requestedModel ?: "请求处理中",
                    subtitle = listOfNotNull(
                        runningRequest.agentId,
                        runningRequest.groupId,
                        runningRequest.providerId
                    ).joinToString(" · ").ifBlank { "等待更多路由信息" },
                    onClick = { onNavigateToRouting(runningRequest.key.routeId) },
                    trailing = {
                        StatusPill("回答中", colors.accent)
                    }
                )
            } else {
                PlainRow(
                    icon = Icons.Filled.Terminal,
                    title = "当前没有处理中的请求",
                    subtitle = "新的调用会出现在这里",
                    onClick = null
                )
            }
        }

        item {
            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp),
                color = colors.divider
            )
            Text(
                text = "成功含改道后成功；改道与最终错误分开计数。",
                fontSize = 11.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun MetricPanel(label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    val colors = MagpieTheme.colors
    DataPanel(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(14.dp)
    ) {
        Column {
            Text(label, fontSize = 12.sp, color = colors.textSecondary,
            lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, fontSize = MagpieTypography.InlineMetric, fontWeight = FontWeight.SemiBold, color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(detail, fontSize = 11.sp, color = colors.textSecondary, maxLines = 2,
            lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun SectionLabel(label: String, trailing: String) {
    val colors = MagpieTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary,
        lineHeight = 16.sp
        )
        if (trailing.isNotBlank()) Text(trailing, fontSize = 11.sp, color = colors.textSecondary,
        lineHeight = 16.sp
        )
    }
}

@Composable
private fun PlainRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = MagpieTheme.colors
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.accentSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (trailing != null) {
                trailing()
            } else if (onClick != null) {
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(15.dp))
            }
        }
        HorizontalDivider(color = colors.divider)
    }
}

@Composable
private fun StatusPill(label: String, color: androidx.compose.ui.graphics.Color) {
    val colors = MagpieTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (color == colors.success) colors.successSurface else colors.accentSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(5.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = color, maxLines = 1,
        lineHeight = 14.sp
        )
    }
}

@Composable
private fun OverviewTrendChart(points: List<HourlyTrendPoint>, modifier: Modifier = Modifier) {
    val colors = MagpieTheme.colors
    if (points.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("暂无小时趋势", fontSize = 11.sp, color = colors.textSecondary,
            lineHeight = 16.sp
            )
        }
        return
    }
    val maxValue = points.maxOfOrNull { it.completedRequests }?.coerceAtLeast(1L) ?: 1L
    Canvas(modifier = modifier) {
        val left = 24.dp.toPx()
        val right = size.width
        val top = 4.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val axisColor = colors.divider
        drawLine(axisColor, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Offset(left, bottom), 1.dp.toPx())
        drawLine(axisColor, androidx.compose.ui.geometry.Offset(left, bottom), androidx.compose.ui.geometry.Offset(right, bottom), 1.dp.toPx())
        for (i in 0..2) {
            val y = top + (bottom - top) * i / 2f
            drawLine(axisColor.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(right, y), 0.5.dp.toPx())
        }
        val stepX = if (points.size > 1) (right - left) / (points.size - 1) else 0f
        val path = Path()
        val fill = Path()
        points.forEachIndexed { index, point ->
            val x = left + stepX * index
            val y = bottom - (bottom - top) * (point.completedRequests.toFloat() / maxValue.toFloat())
            if (index == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, bottom)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
            if (index == points.lastIndex) {
                fill.lineTo(x, bottom)
                fill.close()
            }
        }
        drawPath(
            path = fill,
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(colors.accent.copy(alpha = 0.16f), colors.accent.copy(alpha = 0.02f)),
                startY = top,
                endY = bottom
            )
        )
        drawPath(path, color = colors.accent.copy(alpha = 0.82f), style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
    }
}
