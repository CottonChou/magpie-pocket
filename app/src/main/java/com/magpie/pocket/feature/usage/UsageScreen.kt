package com.magpie.pocket.feature.usage

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magpie.pocket.core.design.DataPanel
import com.magpie.pocket.core.design.GlassSurface
import com.magpie.pocket.core.design.MagpieDimensions
import com.magpie.pocket.core.design.MagpieTheme
import com.magpie.pocket.core.design.MagpieTypography
import com.magpie.pocket.core.design.springPress
import com.magpie.pocket.core.model.AccountQuota
import com.magpie.pocket.core.model.Period
import com.magpie.pocket.core.model.UsageSnapshot
import com.magpie.pocket.core.util.MetricCalculators

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    usage: UsageSnapshot?,
    quotas: List<AccountQuota>,
    selectedPeriod: Period,
    onPeriodChange: (Period) -> Unit,
    isLoading: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val colors = MagpieTheme.colors
    val preferences = MagpieTheme.preferences
    var selectedQuotaDetail by remember { mutableStateOf<AccountQuota?>(null) }
    val totalTokens = MetricCalculators.calculateTotalTokens(usage?.tokens)
    val cacheHitRate = MetricCalculators.calculateCacheHitRate(usage?.tokens)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = MagpieDimensions.PagePadding),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = "用量",
                    fontSize = MagpieTypography.PageTitle,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text("Token、缓存与账号额度", fontSize = 13.sp, color = colors.textSecondary,
                lineHeight = 18.sp
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        item {
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Row(modifier = Modifier.padding(4.dp)) {
                    listOf(Period.TODAY to "今日", Period.SEVEN_DAYS to "近 7 天").forEach { (period, label) ->
                        val selected = period == selectedPeriod
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (selected) colors.surface else Color.Transparent)
                                .springPress(scaleDown = 0.98f) { onPeriodChange(period) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) colors.textPrimary else colors.textSecondary
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

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
                        Text(
                            text = if (selectedPeriod == Period.TODAY) "累计 Token" else "近 7 天 Token",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                        Text("含缓存读取", fontSize = 11.sp, color = colors.textSecondary,
                        lineHeight = 16.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = if (usage == null) "—" else MetricCalculators.formatCompactNumber(totalTokens),
                            fontSize = MagpieTypography.HeroMetric,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            "token",
                            fontSize = 13.sp,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    TokenCompositionBar(usage)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "缓存命中率 ${MetricCalculators.formatPercent(cacheHitRate)}",
                            fontSize = 11.sp,
                            color = colors.textSecondary
                        )
                        Text(
                            text = if (selectedPeriod == Period.TODAY) "今日" else "近 7 天",
                            fontSize = 11.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }

        item { SectionLabel("按模型分布", "Token") }

        item {
            val models = usage?.models.orEmpty()
            if (models.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无模型计量数据", fontSize = 12.sp, color = colors.textSecondary,
                    lineHeight = 16.sp
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    models.forEach { item ->
                        val ratio = if (totalTokens > 0) item.totalTokens.toFloat() / totalTokens.toFloat() else 0f
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.modelId,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${MetricCalculators.formatCompactNumber(item.totalTokens)}  ${MetricCalculators.formatPercent(ratio.toDouble())}",
                                    fontSize = 11.sp,
                                    color = colors.textSecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.divider)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(ratio.coerceIn(0f, 1f))
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(colors.accent)
                                )
                            }
                        }
                    }
                }
            }
        }

        item { SectionLabel("账号额度", "只读") }

        if (quotas.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("未配置或暂无可查询的额度窗口", fontSize = 12.sp, color = colors.textSecondary,
                    lineHeight = 16.sp
                    )
                }
            }
        } else {
            items(quotas, key = { it.accountId }) { quota ->
                QuotaRow(
                    quota = quota,
                    maskedAccount = MetricCalculators.maskAccountName(quota.accountId, preferences.maskAccountNames),
                    onClick = { selectedQuotaDetail = quota }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }

    if (selectedQuotaDetail != null) {
        val quota = selectedQuotaDetail!!
        ModalBottomSheet(
            onDismissRequest = { selectedQuotaDetail = null },
            containerColor = colors.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MagpieDimensions.PagePadding)
                    .padding(bottom = 32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("额度窗口详情", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary,
                        lineHeight = 26.sp
                        )
                        Text(
                            MetricCalculators.maskAccountName(quota.accountId, preferences.maskAccountNames),
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                    IconButton(onClick = { selectedQuotaDetail = null }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = colors.textSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (quota.windows.isEmpty()) {
                    Text("供应商未返回窗口明细", fontSize = 12.sp, color = colors.textSecondary,
                    lineHeight = 16.sp
                    )
                }
                quota.windows.forEach { window ->
                    val ratio = MetricCalculators.calculateQuotaUsedRatio(window)
                    Column(modifier = Modifier.padding(vertical = 9.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(window.id, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary,
                            lineHeight = 16.sp
                            )
                            Text(
                                quotaWindowText(window, ratio),
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                        if (window.resetsAt != null) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text("重置 ${window.resetsAt}", fontSize = 11.sp, color = colors.textSecondary,
                            lineHeight = 16.sp
                            )
                        }
                    }
                    HorizontalDivider(color = colors.divider)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(label: String, trailing: String) {
    val colors = MagpieTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary,
        lineHeight = 16.sp
        )
        Text(trailing, fontSize = 11.sp, color = colors.textSecondary,
        lineHeight = 16.sp
        )
    }
}

@Composable
private fun TokenCompositionBar(usage: UsageSnapshot?) {
    val colors = MagpieTheme.colors
    val tokens = usage?.tokens
    val values = listOf(
        tokens?.cacheRead ?: 0L,
        tokens?.inputUncached ?: 0L,
        tokens?.output ?: 0L,
        tokens?.cacheWrite ?: 0L
    )
    val total = values.sum()
    val palette = listOf(colors.accent, colors.accent.copy(alpha = 0.62f), colors.accent.copy(alpha = 0.34f), colors.textTertiary.copy(alpha = 0.45f))
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.divider),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (total > 0) {
                values.forEachIndexed { index, value ->
                    if (value > 0) {
                        Box(
                            modifier = Modifier
                                .weight(value.toFloat() / total.toFloat())
                                .fillMaxSize()
                                .background(palette[index])
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("缓存", "未缓存", "输出", "写入").forEachIndexed { index, label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(5.dp).clip(RoundedCornerShape(5.dp)).background(palette[index]))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(label, fontSize = 10.sp, color = colors.textSecondary,
                    lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

private fun quotaWindowText(window: com.magpie.pocket.core.model.QuotaWindow, ratio: Double?): String {
    if (window.unlimited) return "无限额"
    if (!window.known) return "额度未知"
    val percent = ratio?.let { MetricCalculators.formatPercent(it) } ?: window.display ?: "—"
    val amount = window.amount ?: window.allowanceLimit
    return if (amount != null && window.allowanceLimit != null) {
        "${formatQuotaNumber(window.amount ?: 0.0)} / ${formatQuotaNumber(window.allowanceLimit)} ${window.unit} · $percent"
    } else {
        percent + if (window.unit.isNotBlank()) " ${window.unit}" else ""
    }
}

private fun formatQuotaNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(java.util.Locale.US, "%.1f", value)

@Composable
private fun QuotaRow(quota: AccountQuota, maskedAccount: String, onClick: () -> Unit) {
    val colors = MagpieTheme.colors
    val warningWindow = quota.windows.firstOrNull { MetricCalculators.isQuotaWarning(it) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(maskedAccount, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary,
                lineHeight = 16.sp
                )
                val primary = quota.windows.firstOrNull()
                Text(
                    text = when {
                        !quota.known -> "供应商未提供额度"
                        primary == null -> "暂无额度窗口"
                        else -> "已用 ${MetricCalculators.formatPercent(MetricCalculators.calculateQuotaUsedRatio(primary))}" +
                            (primary.resetsAt?.let { " · $it 重置" } ?: "")
                    },
                    fontSize = 11.sp,
                    color = colors.textSecondary
                )
            }
            if (!quota.known) {
                Text("未知", fontSize = 11.sp, color = colors.textSecondary,
                lineHeight = 16.sp
                )
            } else if (warningWindow != null) {
                Text(
                    text = "额度偏低",
                    fontSize = 10.sp,
                    color = colors.warning,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.warningSurface)
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                )
            }
        }
        HorizontalDivider(color = colors.divider)
    }
}
