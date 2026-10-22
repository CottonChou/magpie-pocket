package com.magpie.pocket.feature.routing

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.magpie.pocket.core.design.DataPanel
import com.magpie.pocket.core.design.BirdMark
import com.magpie.pocket.core.design.DecisionMark
import com.magpie.pocket.core.design.GlassSurface
import com.magpie.pocket.core.design.MagpieDimensions
import com.magpie.pocket.core.design.MagpieTheme
import com.magpie.pocket.core.design.MagpieTypography
import com.magpie.pocket.core.design.StatusBadge
import com.magpie.pocket.core.design.springPress
import com.magpie.pocket.core.model.Account
import com.magpie.pocket.core.model.ContextBreakdown
import com.magpie.pocket.core.model.RequestDetail
import com.magpie.pocket.core.model.RequestSummary
import com.magpie.pocket.core.model.RouteGroup
import com.magpie.pocket.core.model.TraceSnapshot
import com.magpie.pocket.core.model.FinalState
import com.magpie.pocket.core.routing.RoutePhase
import com.magpie.pocket.core.routing.RouteSettle
import com.magpie.pocket.core.routing.attemptEvidence
import com.magpie.pocket.core.routing.routePhase
import com.magpie.pocket.core.routing.visibleProviderIds
import com.magpie.pocket.core.util.MetricCalculators
import android.provider.Settings
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private sealed interface RoutingSheet {
    data object AllProviders : RoutingSheet
    data class ProviderAccounts(val providerId: String) : RoutingSheet
    data class AccountDetail(val providerId: String, val account: Account) : RoutingSheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(
    trace: TraceSnapshot?,
    groups: List<RouteGroup>,
    accounts: List<Account>,
    selectedGroupId: String?,
    onSelectGroupId: (String) -> Unit,
    selectedRequest: RequestSummary?,
    requestDetail: RequestDetail?,
    onSelectRequest: (RequestSummary) -> Unit,
    onResetToLatest: () -> Unit,
    onOpenRequestDetail: (RequestSummary) -> Unit,
    isDemo: Boolean = false,
    isLoading: Boolean = false,
    isOnline: Boolean = true,
    isFollowingLive: Boolean = true,
    settle: RouteSettle? = null,
    onConsumeSettle: (Long) -> Unit = {},
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val colors = MagpieTheme.colors
    val preferences = MagpieTheme.preferences
    var showGroupPicker by remember { mutableStateOf(false) }
    var showDecisionModal by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf<RoutingSheet?>(null) }

    val currentGroup = groups.find { it.id == selectedGroupId } ?: groups.firstOrNull()
    val runningCount = trace?.runningRouteIds?.size ?: 0
    val context = requestDetail?.context
    val groupProviders = currentGroup?.providerIds ?: emptyList()
    // Highlight only with matching request/attempt evidence. A running route id from a
    // different group is not evidence for this group.
    val matchingSelection = selectedRequest?.takeIf { it.groupId == currentGroup?.id }
    val evidence = attemptEvidence(matchingSelection)
    val activeProvider = evidence?.first
    val activeAccountId = evidence?.second
    val activeAccounts = activeAccountId
        ?.let { id -> accounts.find { it.id == id } }
        ?.let { listOf(it) }
        ?: emptyList()
    val visibleProviders = visibleProviderIds(groupProviders, activeProvider, 2)
    val extraProviders = groupProviders.filter { it !in visibleProviders }
    val phase = routePhase(matchingSelection, trace, pinned = !isFollowingLive, online = isOnline)
    val settleMotion = isFollowingLive && isOnline && matchingSelection != null &&
        settle != null && settle.routeId == matchingSelection.key.routeId
    LaunchedEffect(settle?.token, settleMotion, matchingSelection?.key, isOnline) {
        if (settle != null && !settleMotion) onConsumeSettle(settle.token)
    }

    val presentation = when {
        !isOnline -> Triple("离线", colors.warning, colors.warningSurface)
        trace == null && isLoading -> Triple("同步中", colors.textSecondary, colors.surfaceSubtle)
        trace == null -> Triple("未连接", colors.warning, colors.warningSurface)
        trace.observable == false -> Triple("不可观测", colors.warning, colors.warningSurface)
        isDemo -> Triple("演示", colors.success, colors.successSurface)
        else -> Triple("实时", colors.success, colors.successSurface)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = MagpieDimensions.PagePadding),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            PageHeader(
                title = "路由",
                subtitle = "看清每一次模型选择",
                trailing = {
                    LivePill(
                        label = presentation.first,
                        tone = presentation.second,
                        surface = presentation.third
                    )
                }
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "网关启动以来",
                    fontSize = MagpieTypography.LabelStyle.fontSize,
                    color = colors.textSecondary
                )
                Text(
                    text = if (runningCount > 0) "$runningCount 个请求处理中" else "暂无处理中请求",
                    fontSize = MagpieTypography.LabelStyle.fontSize,
                    color = colors.textSecondary
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            ) {
                HorizontalDivider(color = colors.divider)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    GatewayMetric("已完成请求", trace?.totals?.completedRequests?.toString() ?: "—", Modifier.weight(1f))
                    GatewayMetric("已改道", trace?.totals?.rerouteHandoffs?.toString() ?: "—", Modifier.weight(1f))
                    GatewayMetric("最终错误", trace?.totals?.finalErrors?.toString() ?: "—", Modifier.weight(1f))
                }
                HorizontalDivider(color = colors.divider)
            }
        }

        item { Spacer(modifier = Modifier.height(14.dp)) }

        item {
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(20.dp),
                onClick = { showGroupPicker = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .padding(horizontal = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentGroup?.name ?: "选择路由组",
                            fontSize = MagpieTypography.SubheadStyle.fontSize,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            text = currentGroup?.strategyLabel()?.let { " · $it" } ?: "",
                            fontSize = MagpieTypography.CaptionStyle.fontSize,
                            color = colors.textSecondary,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = "选择路由组",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            DataPanel(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                RouteCanvas(
                    selectedRequest = matchingSelection,
                    currentGroup = currentGroup,
                    accounts = accounts,
                    activeProvider = activeProvider,
                    activeAccountId = activeAccountId,
                    activeAccounts = activeAccounts,
                    visibleProviders = visibleProviders,
                    phase = phase,
                    isFollowingLive = isFollowingLive,
                    settle = settle,
                    onConsumeSettle = onConsumeSettle,
                    isOnline = isOnline,
                    onProviderClick = { providerId ->
                        activeSheet = RoutingSheet.ProviderAccounts(providerId)
                    }
                )
                if (extraProviders.isNotEmpty()) {
                    IconButton(
                        onClick = { activeSheet = RoutingSheet.AllProviders },
                        modifier = Modifier.align(Alignment.TopEnd).size(48.dp)
                    ) {
                        Icon(Icons.Filled.MoreHoriz, contentDescription = "更多供应商", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
                }
            }
        }

        item {
            if (!isFollowingLive || matchingSelection?.groupId != currentGroup?.id) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clickable(onClick = onResetToLatest),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (matchingSelection != null) "正在查看历史请求" else "当前组暂无匹配请求",
                        fontSize = MagpieTypography.LabelStyle.fontSize,
                        color = colors.textSecondary
                    )
                    Text(
                        text = "返回实时",
                        fontSize = MagpieTypography.LabelStyle.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = colors.accent
                    )
                }
            }
        }

        item {
            DecisionRow(
                evidence = requestDetail?.decisionEvidence.orEmpty(),
                summary = matchingSelection,
                onOpen = { showDecisionModal = true }
            )
        }

        item {
            if (context != null) {
                ContextSection(context = context)
            } else {
                HorizontalDivider(color = colors.divider)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 15.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "最近请求的上下文",
                        fontSize = MagpieTypography.LabelStyle.fontSize,
                        color = colors.textSecondary
                    )
                    Text(
                        "暂无详情",
                        fontSize = MagpieTypography.LabelStyle.fontSize,
                        color = colors.textSecondary
                    )
                }
            }
        }

        item {
            if (matchingSelection == null && trace?.observable != false) {
                Text(
                    text = "当前组暂无匹配请求；可从请求页打开一次调用查看完整经过。",
                    fontSize = MagpieTypography.LabelStyle.fontSize,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            if (trace?.observable == false) {
                Text(
                    text = "服务端未提供实时 trace；累计数据仍来自可用接口。",
                    fontSize = MagpieTypography.LabelStyle.fontSize,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }

    if (showGroupPicker) {
        ModalBottomSheet(
            onDismissRequest = { showGroupPicker = false },
            containerColor = colors.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            GroupPickerSheetContent(
                groups = groups,
                selectedGroupId = currentGroup?.id,
                onSelectGroup = {
                    onSelectGroupId(it)
                    showGroupPicker = false
                },
                onDismiss = { showGroupPicker = false }
            )
        }
    }

    if (showDecisionModal) {
        ModalBottomSheet(
            onDismissRequest = { showDecisionModal = false },
            containerColor = colors.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            DecisionDetailSheetContent(
                evidence = requestDetail?.decisionEvidence.orEmpty(),
                selectedRequest = selectedRequest,
                onDismiss = { showDecisionModal = false },
                onOpenRequest = selectedRequest?.let {
                    {
                        showDecisionModal = false
                        onOpenRequestDetail(it)
                    }
                }
            )
        }
    }

    when (val sheet = activeSheet) {
        null -> Unit
        RoutingSheet.AllProviders -> {
            ModalBottomSheet(
                onDismissRequest = { activeSheet = null },
                containerColor = colors.surface,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                AllProvidersSheetContent(
                    providerIds = groupProviders,
                    accounts = accounts,
                    maskEnabled = preferences.maskAccountNames,
                    onProviderClick = { providerId ->
                        activeSheet = RoutingSheet.ProviderAccounts(providerId)
                    },
                    onDismiss = { activeSheet = null }
                )
            }
        }
        is RoutingSheet.ProviderAccounts -> {
            ModalBottomSheet(
                onDismissRequest = { activeSheet = null },
                containerColor = colors.surface,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                ProviderAccountsSheetContent(
                    providerId = sheet.providerId,
                    accounts = accounts.filter { it.providerId == sheet.providerId },
                    activeAccountId = if (sheet.providerId == activeProvider) activeAccountId else null,
                    maskEnabled = preferences.maskAccountNames,
                    onAccountClick = { account ->
                        activeSheet = RoutingSheet.AccountDetail(sheet.providerId, account)
                    },
                    onBack = { activeSheet = RoutingSheet.AllProviders },
                    onDismiss = { activeSheet = null }
                )
            }
        }
        is RoutingSheet.AccountDetail -> {
            ModalBottomSheet(
                onDismissRequest = { activeSheet = null },
                containerColor = colors.surface,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                AccountDetailSheetContent(
                    account = sheet.account,
                    maskEnabled = preferences.maskAccountNames,
                    onBack = { activeSheet = RoutingSheet.ProviderAccounts(sheet.providerId) },
                    onDismiss = { activeSheet = null }
                )
            }
        }
    }

}

@Composable
private fun PageHeader(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    val colors = MagpieTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = MagpieTypography.PageTitleStyle.fontSize,
                fontWeight = MagpieTypography.PageTitleStyle.fontWeight,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                fontSize = MagpieTypography.SubheadStyle.fontSize,
                color = colors.textSecondary
            )
        }
        trailing()
    }
}

@Composable
private fun LivePill(label: String, tone: Color, surface: Color) {
    val colors = MagpieTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(surface)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(tone)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            fontSize = MagpieTypography.LabelStyle.fontSize,
            fontWeight = FontWeight.Medium,
            color = tone
        )
    }
}

@Composable
private fun GatewayMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = MagpieTheme.colors
    Column(modifier = modifier) {
        Text(
            label,
            fontSize = MagpieTypography.LabelStyle.fontSize,
            color = colors.textSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = MagpieTypography.InlineMetricStyle.fontSize,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary
        )
    }
}

@Composable
private fun RouteCanvas(
    selectedRequest: RequestSummary?,
    currentGroup: RouteGroup?,
    accounts: List<Account>,
    activeProvider: String?,
    activeAccountId: String?,
    activeAccounts: List<Account>,
    visibleProviders: List<String>,
    phase: RoutePhase,
    isFollowingLive: Boolean,
    settle: RouteSettle?,
    onConsumeSettle: (Long) -> Unit,
    isOnline: Boolean,
    onProviderClick: (String) -> Unit
) {
    val colors = MagpieTheme.colors
    val source = selectedRequest?.agentId?.displayAgent()
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    val systemAnimations = rememberSystemAnimationsEnabled()
    val motionEnabled = isOnline &&
        lifecycle.isAtLeast(Lifecycle.State.RESUMED) &&
        !MagpieTheme.preferences.reduceMotion &&
        systemAnimations
    val liveMotion = motionEnabled && phase == RoutePhase.ACTIVE
    // A completion received while this canvas was absent is already history.
    val initialSettleToken = remember { settle?.token }
    val settleMotion = motionEnabled && isFollowingLive && phase != RoutePhase.PINNED &&
        settle != null && settle.token != initialSettleToken &&
        settle.routeId == selectedRequest?.key?.routeId
    LaunchedEffect(settle?.token, settleMotion) {
        val pending = settle ?: return@LaunchedEffect
        if (settleMotion) delay(500) // Let the 450 ms return finish once.
        onConsumeSettle(pending.token)
    }
    DisposableEffect(settle?.token) {
        onDispose { settle?.let { onConsumeSettle(it.token) } }
    }
    val animatedPhase = settle?.state?.let { state ->
        if (settleMotion) state else null
    }
    val activeIndex = visibleProviders.indexOf(activeProvider)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = colors.textPrimary,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            if (source != null) {
                Text(
                    text = source,
                    fontSize = MagpieTypography.LabelStyle.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = "请求来源",
                fontSize = MagpieTypography.LabelStyle.fontSize,
                color = colors.textSecondary
            )
        }

        Spacer(modifier = Modifier.height(5.dp))
        WireSegment(
            active = liveMotion,
            color = colors.accent,
            modifier = Modifier
                .width(1.dp)
                .height(14.dp)
        )
        Spacer(modifier = Modifier.height(5.dp))

        Column(
            modifier = Modifier
                .width(152.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.accentSurface.copy(alpha = 0.78f))
                .border(
                    0.75.dp,
                    if (liveMotion) colors.accent.copy(alpha = 0.48f) else colors.accent.copy(alpha = 0.22f),
                    RoundedCornerShape(24.dp)
                )
                .padding(horizontal = 16.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BirdMark(tint = colors.accent, size = 16.dp, strokeWidth = 1.8f)
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "magpie",
                    fontSize = MagpieTypography.SubheadStyle.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent
                )
            }
            Text(
                text = when (phase) {
                    RoutePhase.ACTIVE -> "正在选择并转发"
                    RoutePhase.SUCCEEDED -> "已完成选路"
                    RoutePhase.FAILED -> "选路失败"
                    RoutePhase.CANCELLED -> "已取消"
                    RoutePhase.UNCONFIRMED -> "结果待确认"
                    RoutePhase.PINNED -> "历史请求"
                    RoutePhase.UNAVAILABLE -> "实时数据不可用"
                    RoutePhase.IDLE -> currentGroup?.affinityLabel() ?: "保持本轮账号"
                },
                fontSize = MagpieTypography.LabelStyle.fontSize,
                color = colors.textSecondary,
                maxLines = 1
            )
        }

        BranchConnector(
            providerCount = visibleProviders.size,
            activeIndex = activeIndex,
            phase = phase,
            liveMotion = liveMotion,
            settleState = animatedPhase,
            settleToken = settle?.token ?: 0L,
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
        )

        if (visibleProviders.isEmpty()) {
            Text(
                text = "当前组未提供供应商明细",
                fontSize = MagpieTypography.LabelStyle.fontSize,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                visibleProviders.forEach { providerId ->
                    val isActive = providerId == activeProvider
                    ProviderCard(
                        providerId = providerId,
                        isActive = isActive,
                        phase = if (isActive) phase else RoutePhase.IDLE,
                        motionEnabled = motionEnabled,
                        accounts = accounts.filter { it.providerId == providerId },
                        activeAccountId = if (isActive) activeAccountId else null,
                        activeAccounts = if (isActive) activeAccounts else emptyList(),
                        onClick = { onProviderClick(providerId) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (visibleProviders.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        if (visibleProviders.isNotEmpty()) {
            Spacer(modifier = Modifier.height(7.dp))
            Text(
                text = routeCaption(selectedRequest, currentGroup, activeAccounts, activeAccountId, phase),
                fontSize = MagpieTypography.LabelStyle.fontSize,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun rememberFlowOffset(active: Boolean, periodMs: Int, distance: Float): Float {
    val offset = remember { Animatable(0f) }
    LaunchedEffect(active, periodMs, distance) {
        if (!active) {
            offset.snapTo(0f)
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { }
            offset.snapTo(0f)
            offset.animateTo(distance, tween(periodMs, easing = LinearEasing))
        }
    }
    return offset.value
}

@Composable
private fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        )
    }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = Settings.Global.getFloat(
                    resolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                ) > 0f
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

@Composable
private fun WireSegment(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (!active) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { }
            progress.snapTo(0f)
            progress.animateTo(1f, tween(800, easing = LinearEasing))
        }
    }
    Canvas(modifier = modifier) {
        val dashOffset = progress.value * 6.dp.toPx()
        drawLine(
            color = if (active) color else color.copy(alpha = 0.34f),
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height),
            strokeWidth = 1.dp.toPx(),
            pathEffect = if (active) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()), dashOffset) else null
        )
        if (active) {
            val center = Offset(size.width / 2f, progress.value * size.height)
            drawCircle(color = color, radius = 1.7.dp.toPx(), center = center)
        }
    }
}

@Composable
private fun ProviderCard(
    providerId: String,
    isActive: Boolean,
    phase: RoutePhase,
    motionEnabled: Boolean,
    accounts: List<Account>,
    activeAccountId: String?,
    activeAccounts: List<Account>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MagpieTheme.colors
    val account = activeAccounts.firstOrNull()
    val accountLabel = account?.let {
        "${MetricCalculators.maskAccountName(it.alias, MagpieTheme.preferences.maskAccountNames)} · ${it.availabilityLabel()}"
    } ?: activeAccountId?.let {
        MetricCalculators.maskAccountName(it, MagpieTheme.preferences.maskAccountNames)
    }
    val answering = isActive && phase == RoutePhase.ACTIVE
    val settledColor = when (phase) {
        RoutePhase.SUCCEEDED -> colors.success
        RoutePhase.FAILED -> colors.error
        RoutePhase.CANCELLED -> colors.textTertiary
        RoutePhase.UNCONFIRMED -> colors.warning
        else -> colors.accent
    }
    Column(
        modifier = modifier
            .heightIn(min = MagpieDimensions.TouchTargetMinimum)
            .clip(RoundedCornerShape(15.dp))
            .background(if (isActive) colors.accentSurface else colors.surfaceSubtle)
            .border(
                0.75.dp,
                if (isActive) settledColor.copy(alpha = 0.46f) else colors.divider,
                RoundedCornerShape(15.dp)
            )
            .springPress(scaleDown = 0.98f, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = providerId.displayProvider(),
            fontSize = MagpieTypography.LabelStyle.fontSize,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isActive) {
                if (answering && motionEnabled) {
                    ProviderPulse(color = settledColor)
                } else {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(settledColor)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = when {
                    answering -> account?.let {
                        "${MetricCalculators.maskAccountName(it.alias, MagpieTheme.preferences.maskAccountNames)} · 回答中"
                    } ?: activeAccountId?.let {
                        "${MetricCalculators.maskAccountName(it, MagpieTheme.preferences.maskAccountNames)} · 回答中"
                    } ?: "回答中"
                    isActive && phase == RoutePhase.SUCCEEDED -> "已应答"
                    isActive && phase == RoutePhase.FAILED -> "失败"
                    isActive && phase == RoutePhase.CANCELLED -> "已取消"
                    isActive && phase == RoutePhase.UNCONFIRMED -> "待确认"
                    isActive && phase == RoutePhase.PINNED -> "历史"
                    accountLabel != null -> accountLabel
                    accounts.isNotEmpty() -> "${accounts.size} 个账号"
                    else -> "账号明细不可用"
                },
                fontSize = MagpieTypography.CaptionStyle.fontSize,
                color = if (isActive) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProviderPulse(color: Color) {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(900, easing = LinearEasing))
        }
    }
    Canvas(modifier = Modifier.size(10.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = color.copy(alpha = 0.20f * (1f - pulse.value)),
            radius = (2.5.dp.toPx() + 2.5.dp.toPx() * pulse.value)
        )
        drawCircle(color = color, radius = 2.5.dp.toPx(), center = center)
    }
}

@Composable
private fun BranchConnector(
    providerCount: Int,
    activeIndex: Int,
    phase: RoutePhase,
    liveMotion: Boolean,
    settleState: FinalState?,
    settleToken: Long,
    modifier: Modifier = Modifier
) {
    val colors = MagpieTheme.colors
    val dashOffset = rememberFlowOffset(liveMotion, 700, 6f)
    val travel = remember { Animatable(0f) }
    LaunchedEffect(liveMotion, settleToken, settleState) {
        if (liveMotion) {
            travel.snapTo(0f)
            while (true) {
                withFrameNanos { }
                travel.snapTo(0f)
                travel.animateTo(1f, tween(1250, easing = LinearEasing))
            }
        } else if (settleState != null) {
            // Return evidence travels provider -> hub -> source, then settles.
            travel.snapTo(1f)
            travel.animateTo(0f, tween(450))
        } else {
            travel.snapTo(0f)
        }
    }
    Canvas(modifier = modifier) {
        val midX = size.width / 2f
        val midY = size.height * 0.46f
        val stroke = 1.dp.toPx()
        val radius = 7.dp.toPx()
        val neutral = colors.divider
        val accent = when (phase) {
            RoutePhase.ACTIVE -> colors.accent
            RoutePhase.SUCCEEDED -> colors.success
            RoutePhase.FAILED -> colors.error
            RoutePhase.CANCELLED -> colors.textTertiary
            RoutePhase.UNCONFIRMED -> colors.warning
            RoutePhase.PINNED -> colors.textTertiary
            else -> neutral
        }
        val animated = liveMotion || settleState != null
        val dash = if (liveMotion) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()), dashOffset) else null

        drawLine(
            color = if (animated) accent.copy(alpha = 0.8f) else accent.copy(alpha = 0.35f),
            start = Offset(midX, 0f),
            end = Offset(midX, midY),
            strokeWidth = stroke,
            pathEffect = dash
        )

        val targets = when {
            providerCount <= 1 -> listOf(midX)
            else -> listOf(size.width * 0.25f, size.width * 0.75f)
        }
        targets.forEachIndexed { index, x ->
            val chosen = activeIndex == index || providerCount <= 1 && activeIndex == 0
            val chosenLit = chosen && (animated || phase in setOf(
                RoutePhase.SUCCEEDED,
                RoutePhase.FAILED,
                RoutePhase.CANCELLED,
                RoutePhase.UNCONFIRMED
            ))
            val path = Path().apply {
                moveTo(midX, midY)
                if (x == midX) {
                    lineTo(midX, size.height)
                } else {
                    lineTo(x - if (x < midX) -radius else radius, midY)
                    quadraticBezierTo(x, midY, x, midY + radius)
                    lineTo(x, size.height)
                }
            }
            drawPath(
                path = path,
                color = if (chosenLit) accent else neutral,
                style = Stroke(
                    width = if (chosenLit) stroke * 1.35f else stroke,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = if (chosen && liveMotion) dash else null
                )
            )
            if (chosen && (liveMotion || settleState != null)) {
                val pm = PathMeasure().apply { setPath(path, false) }
                val length = pm.length
                if (length > 0f) {
                    val position = pm.getPosition(travel.value * length)
                    drawCircle(colors.surface, radius = 4.dp.toPx(), center = position)
                    drawCircle(
                        if (settleState == FinalState.FAILED) colors.error else if (settleState == FinalState.SUCCEEDED) colors.success else colors.accent,
                        radius = 2.5.dp.toPx(),
                        center = position
                    )
                    if (settleState != null) {
                        val sourcePosition = Offset(
                            position.x + (midX - position.x) * (1f - travel.value),
                            position.y + (0f - position.y) * (1f - travel.value)
                        )
                        drawCircle(colors.surface, radius = 4.dp.toPx(), center = sourcePosition)
                        drawCircle(
                            if (settleState == FinalState.FAILED) colors.error else if (settleState == FinalState.SUCCEEDED) colors.success else colors.accent,
                            radius = 2.5.dp.toPx(),
                            center = sourcePosition
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandProvidersRow(hiddenCount: Int, onOpen: () -> Unit) {
    val colors = MagpieTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MagpieDimensions.TouchTargetMinimum)
            .clickable(onClick = onOpen)
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "更多供应商 · $hiddenCount",
            fontSize = MagpieTypography.LabelStyle.fontSize,
            color = colors.textSecondary
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun DecisionRow(evidence: List<String>, summary: RequestSummary?, onOpen: () -> Unit) {
    val colors = MagpieTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(5.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clickable(onClick = onOpen)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DecisionMark(tint = colors.accent)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "为什么选择这个账号",
                    fontSize = MagpieTypography.LabelStyle.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Text(
                    text = evidence.firstOrNull() ?: if (summary != null) "按组策略选择当前账号" else "暂无可复盘请求",
                    fontSize = MagpieTypography.LabelStyle.fontSize,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "查看选路原因",
                tint = colors.textSecondary,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun ContextSection(context: ContextBreakdown) {
    val colors = MagpieTheme.colors
    val segments = context.parts.take(3)
    HorizontalDivider(color = colors.divider)
    Column(modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "最近请求的上下文",
                fontSize = MagpieTypography.LabelStyle.fontSize,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary
            )
            Text(
                text = "${MetricCalculators.formatCompactNumber(context.totalTokens)} token",
                fontSize = MagpieTypography.LabelStyle.fontSize,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(colors.divider),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val palette = listOf(colors.accent, colors.accent.copy(alpha = 0.58f), colors.textTertiary.copy(alpha = 0.55f))
            segments.forEachIndexed { index, part ->
                val ratio = if (context.totalTokens > 0) part.tokens.toFloat() / context.totalTokens.toFloat() else 0f
                if (ratio > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(ratio)
                            .fillMaxSize()
                            .background(palette[index % palette.size])
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = segments.take(2).joinToString(" · ") { part ->
                    "${part.kind.contextLabel()} ${MetricCalculators.formatPercent(if (context.totalTokens > 0) part.tokens.toDouble() / context.totalTokens else null)}"
                },
                fontSize = MagpieTypography.CaptionStyle.fontSize,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (context.windowLimit != null) {
                    "窗口 ${MetricCalculators.formatCompactNumber(context.windowLimit)}"
                } else {
                    "窗口上限未知"
                },
                fontSize = MagpieTypography.CaptionStyle.fontSize,
                color = colors.textSecondary
            )
        }
    }
}

@Composable
fun GroupPickerSheetContent(
    groups: List<RouteGroup>,
    selectedGroupId: String?,
    onSelectGroup: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MagpieTheme.colors
    var searchQuery by remember { mutableStateOf("") }
    val filtered = groups.filter {
        it.name.contains(searchQuery, ignoreCase = true) || it.id.contains(searchQuery, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MagpieDimensions.PagePadding)
            .padding(bottom = 32.dp)
    ) {
        SheetHeading(title = "选择路由组", onDismiss = onDismiss)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("搜索路由组", fontSize = MagpieTypography.SubheadStyle.fontSize) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = colors.textSecondary) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.divider
            )
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth().height(280.dp)) {
            items(filtered, key = { it.id }) { group ->
                val isSelected = group.id == selectedGroupId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clickable { onSelectGroup(group.id) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            group.name,
                            fontSize = MagpieTypography.SubheadStyle.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Text(
                            "${group.strategyLabel()} · ${group.enabledMemberCount} 个成员",
                            fontSize = MagpieTypography.CaptionStyle.fontSize,
                            color = colors.textSecondary
                        )
                    }
                    if (isSelected) {
                        Icon(Icons.Filled.Check, contentDescription = "当前查看", tint = colors.accent, modifier = Modifier.size(18.dp))
                    }
                }
                HorizontalDivider(color = colors.divider)
            }
        }
    }
}

@Composable
fun DecisionDetailSheetContent(
    evidence: List<String>,
    selectedRequest: RequestSummary?,
    onDismiss: () -> Unit,
    onOpenRequest: (() -> Unit)? = null
) {
    val colors = MagpieTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MagpieDimensions.PagePadding)
            .padding(bottom = 32.dp)
    ) {
        SheetHeading(title = "为什么选择这个账号", onDismiss = onDismiss)
        Text(
            text = evidence.firstOrNull() ?: "暂无选路证据",
            fontSize = MagpieTypography.BodyStyle.fontSize,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary
        )
        if (evidence.size > 1) {
            Spacer(modifier = Modifier.height(12.dp))
            evidence.drop(1).forEachIndexed { index, step ->
                Row(modifier = Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        "${index + 2}.",
                        fontSize = MagpieTypography.LabelStyle.fontSize,
                        color = colors.textSecondary,
                        modifier = Modifier.width(22.dp)
                    )
                    Text(
                        step,
                        fontSize = MagpieTypography.LabelStyle.fontSize,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (selectedRequest != null) {
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = colors.divider)
            DetailRow("请求来源", selectedRequest.agentId ?: "—")
            DetailRow("路由组", selectedRequest.groupId ?: "—")
            DetailRow("最终模型", selectedRequest.servedModel ?: selectedRequest.requestedModel ?: "—")
            DetailRow("最终状态", selectedRequest.finalState.statusLabel())
        }
        if (onOpenRequest != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.accentSurface)
                    .clickable(onClick = onOpenRequest),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "查看这次请求",
                    fontSize = MagpieTypography.SubheadStyle.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent
                )
            }
        }
    }
}

@Composable
fun AccountDetailSheetContent(
    account: Account,
    maskEnabled: Boolean,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MagpieTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MagpieDimensions.PagePadding)
            .padding(bottom = 32.dp)
    ) {
        SheetHeading(
            title = MetricCalculators.maskAccountName(account.alias, maskEnabled),
            onBack = onBack,
            onDismiss = onDismiss
        )
        DetailRow("供应商", account.providerId.displayProvider())
        DetailRow("可用性", account.availabilityLabel())
        DetailRow("账号标识", MetricCalculators.maskAccountName(account.id, maskEnabled))
        DetailRow("操作权限", "只读查询")
        if (account.cooldownUntil != null) DetailRow("冷却至", account.cooldownUntil)
    }
}

@Composable
private fun AllProvidersSheetContent(
    providerIds: List<String>,
    accounts: List<Account>,
    maskEnabled: Boolean,
    onProviderClick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MagpieTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MagpieDimensions.PagePadding)
            .padding(bottom = 32.dp)
    ) {
        SheetHeading(title = "更多供应商", onDismiss = onDismiss)
        if (providerIds.isEmpty()) {
            Text(
                "当前路由组未提供供应商明细",
                fontSize = MagpieTypography.BodyStyle.fontSize,
                color = colors.textSecondary,
                modifier = Modifier.padding(vertical = 16.dp)
            )
            return@Column
        }
        providerIds.forEach { providerId ->
            val providerAccounts = accounts.filter { it.providerId == providerId }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MagpieDimensions.TouchTargetMinimum)
                    .clickable { onProviderClick(providerId) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        providerId.displayProvider(),
                        fontSize = MagpieTypography.BodyStyle.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    Text(
                        if (providerAccounts.isEmpty()) "账号明细不可用" else "${providerAccounts.size} 个账号",
                        fontSize = MagpieTypography.LabelStyle.fontSize,
                        color = colors.textSecondary
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            HorizontalDivider(color = colors.divider)
        }
    }
}

@Composable
private fun ProviderAccountsSheetContent(
    providerId: String,
    accounts: List<Account>,
    activeAccountId: String?,
    maskEnabled: Boolean,
    onAccountClick: (Account) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MagpieTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MagpieDimensions.PagePadding)
            .padding(bottom = 32.dp)
    ) {
        SheetHeading(title = providerId.displayProvider(), onBack = onBack, onDismiss = onDismiss)
        if (accounts.isEmpty()) {
            Text(
                "该供应商未返回账号明细",
                fontSize = MagpieTypography.BodyStyle.fontSize,
                color = colors.textSecondary,
                modifier = Modifier.padding(vertical = 16.dp)
            )
            return@Column
        }
        accounts.forEach { account ->
            val isActive = account.id == activeAccountId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MagpieDimensions.TouchTargetMinimum)
                    .clickable { onAccountClick(account) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) colors.success else when (account.availability) {
                                "AVAILABLE" -> colors.textTertiary
                                "COOLDOWN" -> colors.warning
                                else -> colors.textTertiary
                            }
                        )
                )
                Spacer(modifier = Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        MetricCalculators.maskAccountName(account.alias, maskEnabled),
                        fontSize = MagpieTypography.BodyStyle.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    Text(
                        account.availabilityLabel(),
                        fontSize = MagpieTypography.LabelStyle.fontSize,
                        color = colors.textSecondary
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "账号详情",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            HorizontalDivider(color = colors.divider)
        }
    }
}

@Composable
private fun SheetHeading(
    title: String,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val colors = MagpieTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(MagpieDimensions.TouchTargetMinimum)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.textSecondary
                )
            }
        }
        Text(
            title,
            fontSize = MagpieTypography.SheetTitleStyle.fontSize,
            fontWeight = MagpieTypography.SheetTitleStyle.fontWeight,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = colors.textSecondary)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = MagpieTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontSize = MagpieTypography.LabelStyle.fontSize,
            color = colors.textSecondary
        )
        Text(
            value,
            fontSize = MagpieTypography.LabelStyle.fontSize,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

private fun RouteGroup.strategyLabel(): String = when (strategy?.lowercase()) {
    "rotate" -> "轮流"
    "smart" -> "智能"
    null, "" -> "默认"
    else -> strategy
}

private fun RouteGroup.affinityLabel(): String = when (affinity?.lowercase()) {
    "turn" -> "保持本轮账号"
    "auto" -> "自动保持会话"
    null, "" -> "保持会话"
    else -> affinity
}

private fun String.displayAgent(): String = when (lowercase()) {
    "codex" -> "Codex"
    "claude-code" -> "Claude Code"
    else -> this
}

private fun String.displayProvider(): String = when (lowercase()) {
    "workbuddy" -> "WorkBuddy"
    "workbuddy-ai" -> "WorkBuddy AI"
    "qoder-cn" -> "Qoder CN"
    "opencode-zen-free" -> "OpenCode Zen"
    "moonshot" -> "Moonshot"
    "deepseek" -> "DeepSeek"
    else -> this
}

private fun Account.availabilityLabel(): String = when (availability) {
    "AVAILABLE" -> "可用"
    "BUSY" -> "回答中"
    "COOLDOWN" -> "冷却中"
    else -> "未知"
}

private fun com.magpie.pocket.core.model.FinalState.statusLabel(): String = when (this) {
    com.magpie.pocket.core.model.FinalState.SUCCEEDED -> "成功"
    com.magpie.pocket.core.model.FinalState.FAILED -> "最终失败"
    com.magpie.pocket.core.model.FinalState.IN_PROGRESS -> "处理中"
    com.magpie.pocket.core.model.FinalState.PENDING -> "排队中"
    com.magpie.pocket.core.model.FinalState.CANCELLED -> "已取消"
    com.magpie.pocket.core.model.FinalState.UNCONFIRMED -> "待确认"
}

@Composable
private fun routeCaption(
    summary: RequestSummary?,
    group: RouteGroup?,
    activeAccounts: List<Account>,
    activeAccountId: String?,
    phase: RoutePhase
): String {
    val model = summary?.servedModel ?: summary?.requestedModel
    val members = group?.enabledMemberCount?.takeIf { it > 0 }?.let { "$it 个启用成员" }
    val account = activeAccounts.firstOrNull()?.alias?.let {
        MetricCalculators.maskAccountName(it, MagpieTheme.preferences.maskAccountNames)
    } ?: activeAccountId?.let {
        MetricCalculators.maskAccountName(it, MagpieTheme.preferences.maskAccountNames)
    }
    val state = when (phase) {
        RoutePhase.ACTIVE -> "回答中"
        RoutePhase.SUCCEEDED -> "已应答"
        RoutePhase.FAILED -> "失败"
        RoutePhase.CANCELLED -> "已取消"
        RoutePhase.UNCONFIRMED -> "待确认"
        RoutePhase.PINNED -> "历史"
        else -> null
    }
    return listOfNotNull(model, account, state, members).joinToString(" · ").ifBlank { "等待实时请求" }
}

private fun String.contextLabel(): String = when (this) {
    "tool_results" -> "工具结果"
    "conversation" -> "会话"
    else -> "其他"
}
