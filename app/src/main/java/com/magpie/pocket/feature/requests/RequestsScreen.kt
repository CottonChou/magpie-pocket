package com.magpie.pocket.feature.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magpie.pocket.core.design.MagpieDimensions
import com.magpie.pocket.core.design.MagpieTheme
import com.magpie.pocket.core.design.MagpieTypography
import com.magpie.pocket.core.design.StatusBadge
import com.magpie.pocket.core.design.springPress
import com.magpie.pocket.core.model.Account
import com.magpie.pocket.core.model.FinalState
import com.magpie.pocket.core.model.RequestDetail
import com.magpie.pocket.core.model.RequestPage
import com.magpie.pocket.core.model.RequestSummary
import com.magpie.pocket.core.util.MetricCalculators
import com.magpie.pocket.core.util.RequestTimestamps
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RequestsScreen(
    requestPage: RequestPage?,
    isLoading: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    statusFilter: String,
    onStatusFilterChange: (String) -> Unit,
    selectedDay: String?,
    onSelectDay: (String?) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRequestClick: (RequestSummary) -> Unit,
    accounts: List<Account> = emptyList(),
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val colors = MagpieTheme.colors
    val preferences = MagpieTheme.preferences
    var localSearch by remember(searchQuery) { mutableStateOf(searchQuery) }
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(localSearch) {
        if (localSearch != searchQuery) {
            delay(300)
            onSearchQueryChange(localSearch)
        }
    }

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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "请求",
                        fontSize = MagpieTypography.PageTitle,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        "从一次调用，看到完整经过",
                        fontSize = MagpieTypography.SubheadStyle.fontSize,
                        color = colors.textSecondary
                    )
                }
                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showDatePicker = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selectedDay == null) colors.successSurface else colors.surfaceSubtle)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(if (selectedDay == null) colors.success else colors.textSecondary)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (selectedDay == null) "实时" else "历史",
                            fontSize = MagpieTypography.LabelStyle.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = if (selectedDay == null) colors.success else colors.textSecondary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Outlined.CalendarMonth,
                            contentDescription = "选择日期",
                            tint = if (selectedDay == null) colors.success else colors.textSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(14.dp)) }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(10.dp))
                BasicTextField(
                    value = localSearch,
                    onValueChange = { localSearch = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp),
                    singleLine = true,
                    textStyle = MagpieTypography.SubheadStyle.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (localSearch.isEmpty()) {
                                Text(
                                    "搜索模型、Agent 或路由组",
                                    fontSize = MagpieTypography.SubheadStyle.fontSize,
                                    color = colors.textSecondary
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                if (localSearch.isNotEmpty()) {
                    IconButton(onClick = {
                        localSearch = ""
                        onSearchQueryChange("")
                    }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "清除搜索", tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        item {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "ALL" to "全部",
                    "RUNNING" to "处理中",
                    "REROUTED" to "改道",
                    "ERROR" to "错误"
                ).forEach { (code, label) ->
                    val selected = statusFilter == code
                    val disabled = code == "RUNNING" && selectedDay != null
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(if (selected) colors.accentSurface else colors.surface)
                            .border(0.75.dp, if (selected) colors.accent.copy(alpha = 0.26f) else colors.divider, RoundedCornerShape(17.dp))
                            .springPress(enabled = !disabled, scaleDown = 0.98f) { onStatusFilterChange(code) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = MagpieTypography.LabelStyle.fontSize,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                disabled -> colors.textSecondary.copy(alpha = 0.42f)
                                selected -> colors.accent
                                else -> colors.textSecondary
                            }
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedDay == null) "最近请求" else "历史请求 · $selectedDay",
                    fontSize = MagpieTypography.LabelStyle.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Text(
                    text = if (requestPage != null) "共 ${requestPage.total} 条" else "加载中",
                    fontSize = MagpieTypography.LabelStyle.fontSize,
                    color = colors.textSecondary
                )
            }
        }

        item { HorizontalDivider(color = colors.divider) }

        val itemsList = requestPage?.items.orEmpty()
        if (itemsList.isEmpty() && !isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "未找到匹配的请求记录",
                        fontSize = MagpieTypography.LabelStyle.fontSize,
                        color = colors.textSecondary
                    )
                }
            }
        } else {
            items(itemsList, key = { it.key.routeId }) { request ->
                RequestRow(
                    summary = request,
                    maskAccount = preferences.maskAccountNames,
                    accountAlias = request.accountId?.let { accounts.firstOrNull { account -> account.id == it }?.alias },
                    onClick = { onRequestClick(request) }
                )
            }
        }

        if (requestPage?.hasMore == true) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable(onClick = onLoadMore),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "加载更多",
                        fontSize = MagpieTypography.LabelStyle.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = colors.accent
                    )
                }
            }
        }

    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDay?.let { parseDayMillis(it) }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    // Material encodes the picked calendar day as UTC midnight.
                    // Keep that UTC date; local conversion would shift it a day.
                    onSelectDay(datePickerState.selectedDateMillis?.let { formatDayMillis(it) })
                    showDatePicker = false
                }) { Text("查看") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onSelectDay(null)
                        showDatePicker = false
                    }) { Text("实时") }
                    TextButton(onClick = { showDatePicker = false }) { Text("取消") }
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

}

@Composable
fun RequestRow(
    summary: RequestSummary,
    maskAccount: Boolean,
    accountAlias: String? = null,
    onClick: () -> Unit
) {
    val colors = MagpieTheme.colors
    val totalTokens = MetricCalculators.calculateTotalTokens(summary.usage)
    val account = (accountAlias ?: summary.accountId)?.let { MetricCalculators.maskAccountName(it, maskAccount) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayModel(summary.servedModel, summary.requestedModel),
                fontSize = MagpieTypography.SubheadStyle.fontSize,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            StatusBadge(summary.finalState, rerouteCount = 0)
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = listOfNotNull(summary.agentId, summary.providerId, account).joinToString(" · ").ifBlank { "来源未知" },
            fontSize = MagpieTypography.CaptionStyle.fontSize,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(7.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = listOf(
                    "首响 ${MetricCalculators.formatDurationMs(summary.ttftMs)}",
                    "耗时 ${MetricCalculators.formatDurationMs(summary.durationMs)}",
                    "${MetricCalculators.formatCompactNumber(totalTokens)} token"
                ).joinToString("  ·  "),
                fontSize = MagpieTypography.CaptionStyle.fontSize,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = RequestTimestamps.formatTime(summary.startedAt),
                fontSize = MagpieTypography.CaptionStyle.fontSize,
                color = colors.textSecondary
            )
        }
        Spacer(modifier = Modifier.height(13.dp))
        HorizontalDivider(color = colors.divider)
    }
}

@Composable
fun RequestDetailSheetContent(
    detail: RequestDetail,
    maskAccount: Boolean,
    accounts: List<Account> = emptyList(),
    onDismiss: () -> Unit
) {
    val colors = MagpieTheme.colors
    val summary = detail.summary
    val totalTokens = MetricCalculators.calculateTotalTokens(summary.usage)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MagpieDimensions.PagePadding)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "请求详情",
                    fontSize = MagpieTypography.SheetTitleStyle.fontSize,
                    fontWeight = MagpieTypography.SheetTitleStyle.fontWeight,
                    color = colors.textPrimary
                )
                Text(
                    text = "#${summary.key.routeId} · ${RequestTimestamps.formatFullLocal(summary.startedAt)}",
                    fontSize = MagpieTypography.CaptionStyle.fontSize,
                    color = colors.textSecondary,
                    lineHeight = 16.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = colors.textSecondary)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(summary.finalState, rerouteCount = 0)
            Spacer(modifier = Modifier.width(8.dp))
            if (summary.rerouteCount > 0) {
                Text(
                    "改道 ${summary.rerouteCount} 次",
                    fontSize = MagpieTypography.LabelStyle.fontSize,
                    color = colors.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        DetailRow("开始时间", RequestTimestamps.formatFullLocal(summary.startedAt))
        DetailRow("请求来源", summary.agentId ?: "—")
        DetailRow("路由组", summary.groupId ?: "—")
        DetailRow("请求模型", displayModel(summary.requestedModel))
        DetailRow("应答模型", displayModel(summary.servedModel, summary.requestedModel))
        DetailRow("供应商", summary.providerId ?: "—")
        DetailRow("账号", summary.accountId?.let { accountId ->
            MetricCalculators.maskAccountName(accounts.firstOrNull { it.id == accountId }?.alias ?: accountId, maskAccount)
        } ?: "—")
        DetailRow("首响", MetricCalculators.formatDurationMs(summary.ttftMs))
        DetailRow("总耗时", MetricCalculators.formatDurationMs(summary.durationMs))
        DetailRow("Token", "${MetricCalculators.formatCompactNumber(totalTokens)}")

        if (detail.sanitizedError != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = detail.sanitizedError,
                fontSize = 12.sp,
                color = colors.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.errorSurface)
                    .padding(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("尝试过程", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary,
        lineHeight = 16.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (detail.attempts.isEmpty()) {
            Text(
                "服务端未返回尝试时间线",
                fontSize = MagpieTypography.LabelStyle.fontSize,
                color = colors.textSecondary
            )
        } else {
            detail.attempts.forEach { attempt ->
                val success = attempt.httpStatus in 200..299
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (success) colors.success else colors.textTertiary)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "#${attempt.index} ${attempt.providerId ?: "未知供应商"} · ${accountDisplay(attempt.accountId, accounts, maskAccount)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = listOfNotNull(
                                validModel(attempt.model),
                                attempt.httpStatus?.let { "HTTP $it" },
                                attempt.errorCode,
                                MetricCalculators.formatDurationMs(attempt.durationMs)
                            ).joinToString(" · ").ifBlank { "进行中" },
                            fontSize = MagpieTypography.CaptionStyle.fontSize,
                            color = if (success) colors.textSecondary else colors.error
                        )
                    }
                }
                HorizontalDivider(color = colors.divider)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = MagpieTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = colors.textSecondary,
        lineHeight = 16.sp
        )
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

private fun validModel(value: String?): String? {
    return value?.trim()?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
}

private fun displayModel(value: String?, fallback: String? = null): String {
    return validModel(value) ?: validModel(fallback) ?: "未记录模型"
}

private fun accountDisplay(accountId: String?, accounts: List<Account>, maskAccount: Boolean): String {
    val value = accounts.firstOrNull { it.id == accountId }?.alias ?: accountId
    return value?.let { MetricCalculators.maskAccountName(it, maskAccount) } ?: "—"
}

private fun parseDayMillis(day: String): Long? {
    return try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(day)?.time
    } catch (_: Exception) {
        null
    }
}

private fun formatDayMillis(millis: Long): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(millis))
}
