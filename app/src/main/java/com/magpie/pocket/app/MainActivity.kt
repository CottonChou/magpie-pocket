package com.magpie.pocket.app

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import com.magpie.pocket.core.design.AppChrome
import com.magpie.pocket.core.design.AppTab
import com.magpie.pocket.core.design.FloatingBottomNav
import com.magpie.pocket.core.design.MagpieColors
import com.magpie.pocket.core.design.MagpieDimensions
import com.magpie.pocket.core.design.MagpiePocketTheme
import com.magpie.pocket.core.design.MagpieTheme
import com.magpie.pocket.core.model.DataMode
import com.magpie.pocket.core.model.ThemeMode
import com.magpie.pocket.feature.connect.ConnectBottomSheet
import com.magpie.pocket.feature.connect.DisplayPreferencesSheet
import com.magpie.pocket.feature.connect.GatewayDetailSheet
import com.magpie.pocket.feature.overview.OverviewScreen
import com.magpie.pocket.feature.requests.RequestsScreen
import com.magpie.pocket.feature.requests.RequestDetailSheetContent
import com.magpie.pocket.feature.routing.RoutingScreen
import com.magpie.pocket.feature.usage.UsageScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var appViewModel: AppViewModel? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as MagpieApplication
        val sharedViewModel = app.appViewModel
        appViewModel = sharedViewModel
        installNetworkCallback(sharedViewModel)

        setContent {
            val viewModel: AppViewModel = sharedViewModel

            val preferences by viewModel.displayPreferences.collectAsState()
            val preferencesReady by viewModel.displayPreferencesReady.collectAsState()
            val connection by viewModel.connectionConfig.collectAsState()
            val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()

            LaunchedEffect(
                preferencesReady,
                lifecycleState,
                connection.mode,
                preferences.keepLiveConnectionInBackground
            ) {
                if (!preferencesReady) return@LaunchedEffect
                maybeRequestNotificationPermission(
                    lifecycleState = lifecycleState,
                    mode = connection.mode,
                    keepaliveEnabled = preferences.keepLiveConnectionInBackground
                )
            }

            val isDark = when (preferences.themeMode) {
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    }
                )
            }

            MagpiePocketTheme(displayPreferences = preferences) {
                MainAppRoot(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appViewModel?.onActivityStarted()
    }

    override fun onResume() {
        super.onResume()
        appViewModel?.onActivityResumed()
    }

    override fun onPause() {
        appViewModel?.onActivityPaused()
        super.onPause()
    }

    override fun onStop() {
        appViewModel?.onActivityStopped()
        super.onStop()
    }

    override fun onDestroy() {
        networkCallback?.let { callback ->
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        connectivityManager = null
        appViewModel = null
        super.onDestroy()
    }

    private fun maybeRequestNotificationPermission(
        lifecycleState: Lifecycle.State,
        mode: DataMode,
        keepaliveEnabled: Boolean
    ) {
        if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED).not()) return
        if (mode != DataMode.LIVE || !keepaliveEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return

        if (appViewModel?.claimNotificationPermissionRequest() == true) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun installNetworkCallback(viewModel: AppViewModel) {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = manager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { viewModel.onNetworkAvailable() }
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppRoot(viewModel: AppViewModel) {
    val colors = MagpieTheme.colors
    val preferences = MagpieTheme.preferences
    val scope = rememberCoroutineScope()

    val connectionConfig by viewModel.connectionConfig.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val trace by viewModel.trace.collectAsState()
    val overview by viewModel.overview.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val selectedGroupId by viewModel.selectedGroupId.collectAsState()
    val usage by viewModel.usage.collectAsState()
    val quotas by viewModel.quotas.collectAsState()
    val requestsPage by viewModel.requestsPage.collectAsState()
    val runningRequest by viewModel.runningRequest.collectAsState()
    val selectedRequest by viewModel.selectedRequest.collectAsState()
    val routingRequest by viewModel.routingRequest.collectAsState()
    val routingDetail by viewModel.routingDetail.collectAsState()
    val isFollowingLive by viewModel.isFollowingLive.collectAsState()
    val routeSettle by viewModel.routeSettle.collectAsState()
    val selectedRequestDetail by viewModel.selectedRequestDetail.collectAsState()
    val isRequestDetailOpen by viewModel.isRequestDetailOpen.collectAsState()
    val selectedPeriod by viewModel.selectedPeriod.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val selectedDay by viewModel.selectedDay.collectAsState()
    val capabilities by viewModel.capabilities.collectAsState()

    var showConnectSheet by remember { mutableStateOf(false) }
    var showGatewayDetailSheet by remember { mutableStateOf(false) }
    var showPreferencesSheet by remember { mutableStateOf(false) }

    val connectSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val gatewaySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val preferencesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val density = LocalDensity.current
    var chromeHeight by remember { mutableStateOf(MagpieDimensions.ChromeHeight) }
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val contentBottomPadding = MagpieDimensions.NavigationHeight +
        MagpieDimensions.NavigationBottomGapAboveInset +
        navBarPadding +
        MagpieDimensions.ContentExtraBottomPadding

    val screenPaddingValues = PaddingValues(
        top = 8.dp,
        bottom = contentBottomPadding
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        AmbientBackdrop(isDark = colors.isDark)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding + chromeHeight)
                .clipToBounds()
        ) {
        // Tab content keeps each LazyColumn's scroll state while switching tabs.
        val tabStateHolder = rememberSaveableStateHolder()
        tabStateHolder.SaveableStateProvider(activeTab.name) {
        when (activeTab) {
            AppTab.OVERVIEW -> {
                OverviewScreen(
                    overview = overview,
                    trace = trace,
                    runningRequest = runningRequest,
                    quotas = quotas,
                    isLoading = isLoading,
                    onNavigateToRouting = { _ ->
                        viewModel.resetToLatest()
                        viewModel.selectTab(AppTab.ROUTING)
                    },
                    onNavigateToErrors = {
                        viewModel.selectTab(AppTab.REQUESTS)
                        viewModel.setStatusFilter("ERROR")
                    },
                    onNavigateToQuotas = {
                        viewModel.selectTab(AppTab.USAGE)
                    },
                    contentPadding = screenPaddingValues
                )
            }
            AppTab.ROUTING -> {
                RoutingScreen(
                    trace = trace,
                    groups = groups,
                    accounts = accounts,
                    selectedGroupId = selectedGroupId,
                    selectedRequest = routingRequest,
                    requestDetail = routingDetail,
                    onSelectRequest = { viewModel.selectRoutingRequest(it) },
                    onResetToLatest = { viewModel.resetToLatest() },
                    onOpenRequestDetail = { viewModel.selectRequest(it) },
                    isDemo = connectionConfig.mode == DataMode.DEMO,
                    isLoading = isLoading,
                    isOnline = isOnline,
                    isFollowingLive = isFollowingLive,
                    settle = routeSettle,
                    onConsumeSettle = { viewModel.consumeRouteSettle(it) },
                    contentPadding = screenPaddingValues
                )
            }
            AppTab.USAGE -> {
                UsageScreen(
                    usage = usage,
                    quotas = quotas,
                    selectedPeriod = selectedPeriod,
                    onPeriodChange = { viewModel.setPeriod(it) },
                    isLoading = isLoading,
                    contentPadding = screenPaddingValues
                )
            }
            AppTab.REQUESTS -> {
                RequestsScreen(
                    requestPage = requestsPage,
                    isLoading = isLoading,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                    statusFilter = statusFilter,
                    onStatusFilterChange = { viewModel.setStatusFilter(it) },
                    selectedDay = selectedDay,
                    onSelectDay = { viewModel.setSelectedDay(it) },
                    onRefresh = { viewModel.loadRequests(append = false) },
                    onLoadMore = { viewModel.loadRequests(append = true) },
                    onRequestClick = { viewModel.selectRequest(it) },
                    accounts = accounts,
                    contentPadding = screenPaddingValues
                )
            }
        }
        }
        }

        // Top AppChrome (fixed on top with status bar padding)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusBarPadding)
                .align(Alignment.TopCenter)
        ) {
            AppChrome(
                connectionName = connectionConfig.name,
                mode = connectionConfig.mode,
                isOnline = isOnline,
                status = connectionStatus,
                onGatewayClick = { showGatewayDetailSheet = true },
                onToggleTheme = {
                    val nextTheme = if (colors.isDark) ThemeMode.LIGHT else ThemeMode.DARK
                    viewModel.updateTheme(nextTheme)
                },
                modifier = Modifier.onSizeChanged { size ->
                    chromeHeight = with(density) { size.height.toDp() }
                }
            )
        }

        // Floating Bottom Navigation (anchored above system bottom inset)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MagpieDimensions.NavigationHorizontalMargin)
                .padding(bottom = navBarPadding + MagpieDimensions.NavigationBottomGapAboveInset)
                .align(Alignment.BottomCenter)
        ) {
            FloatingBottomNav(
                selectedTab = activeTab,
                onTabSelected = { viewModel.selectTab(it) }
            )
        }
    }

    // Modal Sheet: Gateway Details
    if (showGatewayDetailSheet) {
        GatewayDetailSheet(
            config = connectionConfig,
            capabilities = capabilities,
            status = connectionStatus,
            sheetState = gatewaySheetState,
            onDismiss = { showGatewayDetailSheet = false },
            onOpenPreferences = {
                showGatewayDetailSheet = false
                showPreferencesSheet = true
            },
            onEditConnection = {
                showGatewayDetailSheet = false
                showConnectSheet = true
            },
            onRetry = { viewModel.refreshAll() },
            onDisconnect = {
                showGatewayDetailSheet = false
                viewModel.disconnect()
            }
        )
    }

    // Modal Sheet: Connect / Edit Gateway
    if (showConnectSheet) {
        ConnectBottomSheet(
            currentConfig = connectionConfig,
            sheetState = connectSheetState,
            onDismiss = { showConnectSheet = false },
            onConnectSuccess = { newCfg ->
                viewModel.saveConnection(newCfg)
                showConnectSheet = false
            },
            onSwitchToDemo = {
                viewModel.disconnect()
                showConnectSheet = false
            }
        )
    }

    // Modal Sheet: Display Preferences
    if (showPreferencesSheet) {
        DisplayPreferencesSheet(
            preferences = preferences,
            sheetState = preferencesSheetState,
            onDismiss = { showPreferencesSheet = false },
            onUpdateTheme = { viewModel.updateTheme(it) },
            onUpdateGlass = { viewModel.updateGlassTier(it) },
            onUpdateReduceMotion = { viewModel.updateReduceMotion(it) },
            onUpdateMaskAccounts = { viewModel.updateMaskAccounts(it) },
            onUpdateKeepLiveConnection = { viewModel.updateKeepLiveConnectionInBackground(it) }
        )
    }

    // Single request-detail sheet host. It is independent of the active tab so a
    // routing decision can open it immediately without switching pages.
    if (isRequestDetailOpen) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeRequestDetail() },
            containerColor = colors.surface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.fillMaxHeight(0.9f)
        ) {
            val detail = selectedRequestDetail
            val selected = selectedRequest
            if (detail != null && selected != null && detail.summary.key == selected.key) {
                RequestDetailSheetContent(
                    detail = detail,
                    maskAccount = preferences.maskAccountNames,
                    accounts = accounts,
                    onDismiss = { viewModel.closeRequestDetail() }
                )
            } else {
                RequestDetailLoadingContent(
                    selected = selected,
                    onDismiss = { viewModel.closeRequestDetail() }
                )
            }
        }
    }
}

@Composable
private fun RequestDetailLoadingContent(selected: com.magpie.pocket.core.model.RequestSummary?, onDismiss: () -> Unit) {
    val colors = MagpieTheme.colors
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MagpieDimensions.PagePadding)
            .padding(bottom = 40.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Text(
                text = "请求详情",
                fontSize = com.magpie.pocket.core.design.MagpieTypography.SheetTitleStyle.fontSize,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = colors.textPrimary
            )
            androidx.compose.material3.IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = colors.textSecondary
                )
            }
        }
        Text(
            text = selected?.let { "#${it.key.routeId} · 正在读取这次调用" } ?: "请选择一次请求",
            fontSize = com.magpie.pocket.core.design.MagpieTypography.LabelStyle.fontSize,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
    }
}

@Composable
private fun AmbientBackdrop(isDark: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (isDark) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(MagpieColors.DarkAccent.copy(alpha = 0.10f), androidx.compose.ui.graphics.Color.Transparent),
                    center = Offset(size.width * 0.85f, size.height * 0.04f),
                    radius = size.width * 0.70f
                ),
                radius = size.width * 0.70f,
                center = Offset(size.width * 0.85f, size.height * 0.04f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(androidx.compose.ui.graphics.Color(0xFF23414A).copy(alpha = 0.10f), androidx.compose.ui.graphics.Color.Transparent),
                    center = Offset(0f, size.height * 0.46f),
                    radius = size.width * 0.60f
                ),
                radius = size.width * 0.60f,
                center = Offset(0f, size.height * 0.46f)
            )
        } else {
            // Reference ambient field: lavender upper-right, cool pale blue at left-middle.
            // Restrained radial falloff keeps the base #F6F7FB visible across data areas.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        androidx.compose.ui.graphics.Color(0xFFE9E5FC).copy(alpha = 0.88f),
                        androidx.compose.ui.graphics.Color(0xFFE9E5FC).copy(alpha = 0.30f),
                        androidx.compose.ui.graphics.Color.Transparent
                    ),
                    center = Offset(size.width * 0.90f, size.height * 0.01f),
                    radius = size.width * 0.86f
                ),
                radius = size.width * 0.86f,
                center = Offset(size.width * 0.90f, size.height * 0.01f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        androidx.compose.ui.graphics.Color(0xFFEAF2F7).copy(alpha = 0.92f),
                        androidx.compose.ui.graphics.Color(0xFFEAF2F7).copy(alpha = 0.34f),
                        androidx.compose.ui.graphics.Color.Transparent
                    ),
                    center = Offset(0f, size.height * 0.46f),
                    radius = size.width * 0.72f
                ),
                radius = size.width * 0.72f,
                center = Offset(0f, size.height * 0.46f)
            )
        }
    }
}
