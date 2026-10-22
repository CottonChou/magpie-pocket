package com.magpie.pocket.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magpie.pocket.core.data.DemoRepository
import com.magpie.pocket.core.data.LiveMagpieRepository
import com.magpie.pocket.core.data.MagpieRepository
import com.magpie.pocket.core.data.RequestQuery
import com.magpie.pocket.core.design.AppTab
import com.magpie.pocket.core.model.Account
import com.magpie.pocket.core.model.AccountQuota
import com.magpie.pocket.core.model.Capabilities
import com.magpie.pocket.core.model.ConnectionConfig
import com.magpie.pocket.core.model.ConnectionHealth
import com.magpie.pocket.core.model.ConnectionStatus
import com.magpie.pocket.core.model.DataMode
import com.magpie.pocket.core.model.DisplayPreferences
import com.magpie.pocket.core.model.GlassTier
import com.magpie.pocket.core.model.OverviewSnapshot
import com.magpie.pocket.core.model.Period
import com.magpie.pocket.core.model.RequestDetail
import com.magpie.pocket.core.model.RequestPage
import com.magpie.pocket.core.model.RequestSummary
import com.magpie.pocket.core.model.RouteGroup
import com.magpie.pocket.core.model.ThemeMode
import com.magpie.pocket.core.model.TraceSnapshot
import com.magpie.pocket.core.model.UsageSnapshot
import com.magpie.pocket.core.routing.RouteSettle
import com.magpie.pocket.core.routing.TraceRoutingState
import com.magpie.pocket.core.routing.chooseLiveRoute
import com.magpie.pocket.core.routing.chooseRouteForGroup
import com.magpie.pocket.core.routing.detailFor
import com.magpie.pocket.core.routing.reduceTraceRouting
import com.magpie.pocket.core.network.AuthenticationRequiredException
import com.magpie.pocket.core.network.CloudflareAccessRequiredException
import com.magpie.pocket.core.network.MagpieApiClient
import com.magpie.pocket.core.network.MagpieApiException
import com.magpie.pocket.core.network.NetworkUnavailableException
import com.magpie.pocket.core.network.PermissionDeniedException
import com.magpie.pocket.core.network.UnexpectedResponseException
import com.magpie.pocket.core.security.CredentialStore
import com.magpie.pocket.core.security.CredentialStorageException
import com.magpie.pocket.core.security.PreferencesManager
import com.magpie.pocket.core.util.RequestTimestamps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface ConnectionStateSource {
    fun getConnection(): ConnectionConfig
    fun saveConnection(config: ConnectionConfig)
    fun clearCredentials()
}

interface DisplayPreferencesSource {
    val displayPreferencesFlow: kotlinx.coroutines.flow.Flow<DisplayPreferences>
    suspend fun updateThemeMode(mode: ThemeMode)
    suspend fun updateGlassTier(tier: GlassTier)
    suspend fun updateReduceMotion(enabled: Boolean)
    suspend fun updateMaskAccounts(enabled: Boolean)
}

class CredentialStoreConnectionSource(private val store: CredentialStore) : ConnectionStateSource {
    override fun getConnection(): ConnectionConfig = store.getConnection()
    override fun saveConnection(config: ConnectionConfig) = store.saveConnection(config)
    override fun clearCredentials() = store.clearCredentials()
}

class PreferencesManagerDisplaySource(private val manager: PreferencesManager) : DisplayPreferencesSource {
    override val displayPreferencesFlow = manager.displayPreferencesFlow
    override suspend fun updateThemeMode(mode: ThemeMode) = manager.updateThemeMode(mode)
    override suspend fun updateGlassTier(tier: GlassTier) = manager.updateGlassTier(tier)
    override suspend fun updateReduceMotion(enabled: Boolean) = manager.updateReduceMotion(enabled)
    override suspend fun updateMaskAccounts(enabled: Boolean) = manager.updateMaskAccounts(enabled)
}

class AppViewModel(
    private val credentialStore: ConnectionStateSource,
    private val preferencesManager: DisplayPreferencesSource,
    private val demoRepo: DemoRepository,
    private val apiClient: MagpieApiClient,
    private val clientFactory: (ConnectionConfig) -> MagpieApiClient = { config ->
        MagpieApiClient(
            cfAccessClientId = config.cfAccessClientId,
            cfAccessClientSecret = config.cfAccessClientSecret
        )
    }
) : ViewModel() {

    private val _connectionConfig = MutableStateFlow(credentialStore.getConnection())
    val connectionConfig: StateFlow<ConnectionConfig> = _connectionConfig.asStateFlow()

    private val _displayPreferences = MutableStateFlow(DisplayPreferences())
    val displayPreferences: StateFlow<DisplayPreferences> = _displayPreferences.asStateFlow()

    private val _activeTab = MutableStateFlow(AppTab.OVERVIEW)
    val activeTab: StateFlow<AppTab> = _activeTab.asStateFlow()

    private val _trace = MutableStateFlow<TraceSnapshot?>(null)
    val trace: StateFlow<TraceSnapshot?> = _trace.asStateFlow()

    private val _overview = MutableStateFlow<OverviewSnapshot?>(null)
    val overview: StateFlow<OverviewSnapshot?> = _overview.asStateFlow()

    private val _groups = MutableStateFlow<List<RouteGroup>>(emptyList())
    val groups: StateFlow<List<RouteGroup>> = _groups.asStateFlow()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    private val _usage = MutableStateFlow<UsageSnapshot?>(null)
    val usage: StateFlow<UsageSnapshot?> = _usage.asStateFlow()

    private val _quotas = MutableStateFlow<List<AccountQuota>>(emptyList())
    val quotas: StateFlow<List<AccountQuota>> = _quotas.asStateFlow()

    private val _requestsPage = MutableStateFlow<RequestPage?>(null)
    val requestsPage: StateFlow<RequestPage?> = _requestsPage.asStateFlow()

    private val _runningRequest = MutableStateFlow<RequestSummary?>(null)
    val runningRequest: StateFlow<RequestSummary?> = _runningRequest.asStateFlow()

    private val _selectedRequest = MutableStateFlow<RequestSummary?>(null)
    val selectedRequest: StateFlow<RequestSummary?> = _selectedRequest.asStateFlow()

    /** The route canvas focus. Unlike selectedRequest, live trace updates may advance it. */
    private val _routingRequest = MutableStateFlow<RequestSummary?>(null)
    val routingRequest: StateFlow<RequestSummary?> = _routingRequest.asStateFlow()

    /** Full context/decision evidence for the route canvas, independent of the modal. */
    private val _routingDetail = MutableStateFlow<RequestDetail?>(null)
    val routingDetail: StateFlow<RequestDetail?> = _routingDetail.asStateFlow()

    private val _isFollowingLive = MutableStateFlow(true)
    val isFollowingLive: StateFlow<Boolean> = _isFollowingLive.asStateFlow()

    private val _routeSettle = MutableStateFlow<RouteSettle?>(null)
    val routeSettle: StateFlow<RouteSettle?> = _routeSettle.asStateFlow()

    private var routingTraceState = TraceRoutingState()
    private var consumedSettleToken: Long = 0L

    private val _selectedRequestDetail = MutableStateFlow<RequestDetail?>(null)
    val selectedRequestDetail: StateFlow<RequestDetail?> = _selectedRequestDetail.asStateFlow()

    private val _isRequestDetailOpen = MutableStateFlow(false)
    val isRequestDetailOpen: StateFlow<Boolean> = _isRequestDetailOpen.asStateFlow()

    private val _selectedPeriod = MutableStateFlow(Period.TODAY)
    val selectedPeriod: StateFlow<Period> = _selectedPeriod.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _statusFilter = MutableStateFlow("ALL")
    val statusFilter: StateFlow<String> = _statusFilter.asStateFlow()

    private val _selectedDay = MutableStateFlow<String?>(null)
    val selectedDay: StateFlow<String?> = _selectedDay.asStateFlow()

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus(ConnectionHealth.CONNECTING))
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _capabilities = MutableStateFlow<Capabilities?>(null)
    val capabilities: StateFlow<Capabilities?> = _capabilities.asStateFlow()

    private var activeRepo: MagpieRepository = demoRepo
    private var repoGeneration: Long = 0L
    private var activeLiveClient: MagpieApiClient? = null
    private var refreshJob: Job? = null
    private var traceJob: Job? = null
    private var detailJob: Job? = null
    private var requestJob: Job? = null
    private var periodJob: Job? = null
    private var selectionGeneration: Long = 0L

    init {
        // Collect display preferences
        viewModelScope.launch {
            preferencesManager.displayPreferencesFlow.collect {
                _displayPreferences.value = it
            }
        }

        // Initialize active repository
        setupRepository(_connectionConfig.value)
    }

    private fun setupRepository(config: ConnectionConfig) {
        repoGeneration += 1
        val generation = repoGeneration
        refreshJob?.cancel()
        traceJob?.cancel()
        detailJob?.cancel()
        requestJob?.cancel()
        periodJob?.cancel()
        selectionGeneration += 1

        // Never let a previous connection's in-memory Magpie session leak into a new one.
        activeLiveClient?.cookieJar?.clear()
        activeLiveClient = null

        _trace.value = null
        _overview.value = null
        _groups.value = emptyList()
        _accounts.value = emptyList()
        _usage.value = null
        _quotas.value = emptyList()
        _requestsPage.value = null
        _runningRequest.value = null
        _routingRequest.value = null
        _routingDetail.value = null
        _isFollowingLive.value = true
        _routeSettle.value = null
        routingTraceState = TraceRoutingState()
        consumedSettleToken = 0L
        _capabilities.value = null
        _errorMessage.value = null
        _selectedGroupId.value = null
        _selectedRequest.value = null
        _selectedRequestDetail.value = null
        _isRequestDetailOpen.value = false

        _isOnline.value = config.mode == DataMode.DEMO
        _connectionStatus.value = ConnectionStatus(
            if (config.mode == DataMode.DEMO) ConnectionHealth.CONNECTED
            else ConnectionHealth.CONNECTING
        )

        activeRepo = if (config.mode == DataMode.LIVE && !config.baseUrl.isNullOrBlank()) {
            val client = clientFactory(config)
            activeLiveClient = client
            LiveMagpieRepository(
                baseUrl = config.baseUrl,
                connectionId = config.id,
                apiClient = client,
                webKey = config.webKey
            )
        } else {
            demoRepo
        }

        refreshAll(generation)
    }

    fun refreshAll() {
        refreshAll(repoGeneration)
    }

    private fun refreshAll(generation: Long) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            if (_connectionConfig.value.mode == DataMode.LIVE && !isAuthFailureState()) {
                _connectionStatus.value = ConnectionStatus(ConnectionHealth.CONNECTING)
            }

            try {
                // Probe first: for live connections this establishes the Magpie session
                // (using the encrypted Web Key) before any protected API load or trace.
                val cap = activeRepo.probe()
                if (generation != repoGeneration) return@launch
                _capabilities.value = cap

                // Load overview
                if (cap.usage) {
                    val ov = activeRepo.overview(_selectedPeriod.value)
                    if (generation != repoGeneration) return@launch
                    _overview.value = ov
                }

                // Optional groups/accounts are only requested when the probe
                // verified the endpoint.
                if (cap.groups) {
                    val grps = activeRepo.groups()
                    if (generation != repoGeneration) return@launch
                    _groups.value = grps
                    if (_selectedGroupId.value !in grps.map { it.id } && grps.isNotEmpty()) {
                        _selectedGroupId.value = grps.first().id
                    }

                    val accs = activeRepo.accounts()
                    if (generation != repoGeneration) return@launch
                    _accounts.value = accs
                }

                // Load usage
                if (cap.usage) {
                    val us = activeRepo.usage(_selectedPeriod.value)
                    if (generation != repoGeneration) return@launch
                    _usage.value = us
                }

                // Load quotas
                if (cap.quotas) {
                    val qts = activeRepo.quotas()
                    if (generation != repoGeneration) return@launch
                    _quotas.value = qts
                }

                // Load requests
                if (cap.ledger) loadRequests(generation = generation)

                if (generation != repoGeneration) return@launch
                // A request load may already have hit a sticky auth failure.
                if (isAuthFailureState()) return@launch
                _isOnline.value = true
                _connectionStatus.value = ConnectionStatus(ConnectionHealth.CONNECTED)
                if (cap.trace) {
                    startObservingTrace(generation)
                } else {
                    traceJob?.cancel()
                    _trace.value = TraceSnapshot(
                        epoch = "",
                        seq = 0L,
                        observable = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != repoGeneration) return@launch
                handleFailure(e, generation, clearTrace = true)
            } finally {
                if (generation == repoGeneration) {
                    _isLoading.value = false
                }
            }
        }
    }

    private fun isAuthFailure(error: Throwable): Boolean =
        error is AuthenticationRequiredException ||
            error is PermissionDeniedException ||
            error is CloudflareAccessRequiredException

    /**
     * One failure path for trace, requests, periods and detail enrichment.
     * A 401/403 on the current connection is sticky until an explicit retry
     * or connection switch; late trace emissions cannot turn it green.
     */
    private fun handleFailure(error: Throwable, generation: Long, clearTrace: Boolean = false) {
        if (generation != repoGeneration) return
        val status = statusFor(error)
        if (isAuthFailure(error)) {
            traceJob?.cancel()
            _isOnline.value = false
            _connectionStatus.value = status
            _errorMessage.value = status.message
            if (clearTrace) _trace.value = null
            return
        }
        if (isAuthFailureState()) return
        _connectionStatus.value = status
        _isOnline.value = false
        _errorMessage.value = status.message
    }

    private fun isAuthFailureState(): Boolean {
        val health = _connectionStatus.value.health
        return health == ConnectionHealth.AUTH_REQUIRED || health == ConnectionHealth.ACCESS_DENIED
    }

    private fun statusFor(error: Throwable): ConnectionStatus = when (error) {
        is AuthenticationRequiredException -> ConnectionStatus(
            ConnectionHealth.AUTH_REQUIRED,
            "需要 Magpie 登录：请在连接设置中填写有效的 Web Key（不是 Cloudflare Access 凭证）。"
        )
        is CloudflareAccessRequiredException -> ConnectionStatus(
            ConnectionHealth.ACCESS_DENIED,
            "Cloudflare Access 拒绝了访问：请检查 Service Token 或网络访问权限。"
        )
        is PermissionDeniedException -> ConnectionStatus(
            ConnectionHealth.ACCESS_DENIED,
            "访问被拒绝：请检查 Cloudflare Access Service Token 权限。"
        )
        is NetworkUnavailableException -> ConnectionStatus(
            ConnectionHealth.NETWORK_UNREACHABLE,
            "网络不可达：请检查网络连接与管理服务地址。"
        )
        is UnexpectedResponseException -> ConnectionStatus(
            ConnectionHealth.INCOMPATIBLE,
            "服务响应不兼容：该地址可能不是 Magpie 管理服务。"
        )
        is MagpieApiException -> ConnectionStatus(
            ConnectionHealth.OFFLINE,
            error.message ?: "连接服务失败，请稍后重试。"
        )
        else -> ConnectionStatus(ConnectionHealth.OFFLINE, "连接服务失败，请稍后重试。")
    }

    private fun startObservingTrace(generation: Long) {
        traceJob?.cancel()
        traceJob = viewModelScope.launch {
            try {
                activeRepo.observeTrace().collect { tr ->
                    if (generation != repoGeneration) return@collect
                    // A current-connection auth failure is sticky; an already
                    // in-flight trace emission must not clear it.
                    if (isAuthFailureState()) return@collect
                    applyTraceSnapshot(tr)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != repoGeneration) return@launch
                handleFailure(e, generation)
            }
        }
    }

    internal fun applyTraceSnapshot(tr: TraceSnapshot) {
        // The trace carries full recent routes, so live focus does not need a
        // serial requestDetail call for every emission.
        val liveBefore = chooseLiveRoute(_trace.value, _selectedGroupId.value)
        _trace.value = tr
        _isOnline.value = true
        _connectionStatus.value = ConnectionStatus(ConnectionHealth.CONNECTED)
        routingTraceState = reduceTraceRouting(
            routingTraceState,
            tr,
            selectedGroupId = _selectedGroupId.value,
            focusedRouteId = liveBefore?.key?.routeId ?: _routingRequest.value?.key?.routeId
        )
        if (tr.restarted) {
            consumedSettleToken = 0L
            _routeSettle.value = null
        }
        routingTraceState.settles
            .lastOrNull { settle ->
                settle.token > consumedSettleToken &&
                    tr.recentRoutes.find { it.key.routeId == settle.routeId }?.groupId == _selectedGroupId.value
            }
            ?.let { _routeSettle.value = it }

        val runningIds = tr.runningRouteIds.toSet()
        _runningRequest.value = tr.recentRoutes
            .filter { it.key.routeId in runningIds }
            .maxByOrNull { RequestTimestamps.parseMillis(it.startedAt) ?: Long.MIN_VALUE }

        val live = chooseLiveRoute(tr, _selectedGroupId.value)

        if (tr.restarted && _isFollowingLive.value && live == null) {
            _routingRequest.value = null
            _routingDetail.value = null
            if (!_isRequestDetailOpen.value) {
                _selectedRequest.value = null
                _selectedRequestDetail.value = null
            }
        } else if (_isFollowingLive.value && live != null) {
            _routingRequest.value = live
            _routingDetail.value = tr.detailFor(live)
            if (!_isRequestDetailOpen.value) _selectedRequest.value = live
        } else {
            val focused = _routingRequest.value ?: return
            val refreshed = tr.recentRoutes.find { it.key.routeId == focused.key.routeId }
            val detail = refreshed?.let { tr.detailFor(it) }
            if (refreshed != null) {
                _routingRequest.value = refreshed
                _routingDetail.value = detail ?: _routingDetail.value
            }
        }
    }

    fun loadRequests(append: Boolean = false) {
        loadRequests(repoGeneration, append)
    }

    private fun loadRequests(generation: Long, append: Boolean = false) {
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            val currentOffset = if (append) _requestsPage.value?.items?.size ?: 0 else 0
            val query = RequestQuery(
                offset = currentOffset,
                limit = 50,
                query = _searchQuery.value,
                statusFilter = _statusFilter.value,
                day = _selectedDay.value
            )

            try {
                val page = activeRepo.requests(query)
                if (generation != repoGeneration) return@launch
                if (append && _requestsPage.value != null) {
                    val combined = (_requestsPage.value?.items ?: emptyList()) + page.items
                    _requestsPage.value = page.copy(items = combined)
                } else {
                    _requestsPage.value = page
                    reconcileRoutingSelection(page.items)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != repoGeneration) return@launch
                handleFailure(e, generation)
            }
        }
    }

    fun selectRequest(request: RequestSummary) {
        selectionGeneration += 1
        detailJob?.cancel()
        _selectedRequest.value = request
        _routingRequest.value = request
        _isFollowingLive.value = false
        _isRequestDetailOpen.value = true
        _selectedRequestDetail.value = _trace.value?.detailFor(request)
        loadRequestDetail(request, selectionGeneration)
        _routingDetail.value = _trace.value?.detailFor(request)
    }

    /** Selects the routing focus without opening the request-detail modal. */
    fun selectRoutingRequest(request: RequestSummary?) {
        selectionGeneration += 1
        detailJob?.cancel()
        _selectedRequest.value = request
        _routingRequest.value = request
        _isFollowingLive.value = false
        _routingDetail.value = request?.let { _trace.value?.detailFor(it) }
        if (request != null) loadRequestDetail(request, selectionGeneration)
    }

    private fun loadRoutingDetail(request: RequestSummary, generation: Long) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            try {
                val detail = activeRepo.requestDetail(request.key)
                if (generation == selectionGeneration && _routingRequest.value?.key == request.key) {
                    _routingDetail.value = detail
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadRoutingDetailIfMissing(request: RequestSummary) {
        if (_routingDetail.value?.summary?.key == request.key) return
        val cached = _trace.value?.detailFor(request)
        if (cached != null) {
            _routingDetail.value = cached
            return
        }
        loadRoutingDetail(request, selectionGeneration)
    }

    private fun loadRequestDetail(request: RequestSummary, generation: Long) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            try {
                val detail = activeRepo.requestDetail(request.key)
                // Both the full key and the selection generation must still match. A late
                // response for request A can never overwrite the current request B.
                if (
                    generation == selectionGeneration &&
                    _selectedRequest.value?.key == request.key
                ) {
                    _selectedRequestDetail.value = detail
                    if (_routingRequest.value?.key == request.key) _routingDetail.value = detail
                }
            } catch (_: Exception) {}
        }
    }

    fun closeRequestDetail() {
        _isRequestDetailOpen.value = false
    }

    fun selectTab(tab: AppTab) {
        _activeTab.value = tab
    }

    fun selectGroupId(groupId: String) {
        _selectedGroupId.value = groupId
        _isFollowingLive.value = true
        consumedSettleToken = routingTraceState.settle?.token ?: consumedSettleToken
        _routeSettle.value = null
        routingTraceState = TraceRoutingState(runningIds = _trace.value?.runningRouteIds?.toSet() ?: emptySet())
        selectionGeneration += 1
        detailJob?.cancel()
        _selectedRequest.value = null
        _selectedRequestDetail.value = null
        _routingDetail.value = null
        reconcileRoutingSelection(_requestsPage.value?.items ?: emptyList(), force = true)
    }

    /**
     * Keeps routing's focused request aligned with the viewed group. A missing match clears
     * the focus instead of leaving a request from a different group on screen.
     */
    private fun reconcileRoutingSelection(items: List<RequestSummary>, force: Boolean = false) {
        val groupId = _selectedGroupId.value ?: return
        val current = _routingRequest.value
        val stillMatches = current != null && current.groupId == groupId
        if (!force && stillMatches) return
        if (!_isFollowingLive.value && current != null) return

        val latest = chooseRouteForGroup(_trace.value, groupId, items)
        if (latest == null) {
            selectionGeneration += 1
            detailJob?.cancel()
            _selectedRequest.value = null
            _routingRequest.value = null
            _selectedRequestDetail.value = null
            _routingDetail.value = null
        } else if (_isFollowingLive.value) {
            focusLiveRoute(latest)
        } else {
            selectRoutingRequest(latest)
        }
    }

    private fun focusLiveRoute(request: RequestSummary) {
        selectionGeneration += 1
        detailJob?.cancel()
        _routingRequest.value = request
        if (!_isRequestDetailOpen.value) _selectedRequest.value = request
        _routingDetail.value = _trace.value?.detailFor(request)
    }

    fun setPeriod(period: Period) {
        _selectedPeriod.value = period
        val generation = repoGeneration
        periodJob?.cancel()
        periodJob = viewModelScope.launch {
            try {
                val overview = activeRepo.overview(period)
                if (generation != repoGeneration) return@launch
                _overview.value = overview
                val usage = activeRepo.usage(period)
                if (generation != repoGeneration) return@launch
                _usage.value = usage
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != repoGeneration) return@launch
                handleFailure(e, generation)
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        loadRequests(append = false)
    }

    fun setStatusFilter(filter: String) {
        _statusFilter.value = filter
        loadRequests(append = false)
    }

    fun setSelectedDay(day: String?) {
        _selectedDay.value = day
        loadRequests(append = false)
    }

    fun resetToLatest() {
        _isFollowingLive.value = true
        consumedSettleToken = routingTraceState.settle?.token ?: consumedSettleToken
        _routeSettle.value = null
        val groupId = _selectedGroupId.value
        val latest = chooseRouteForGroup(
            _trace.value,
            groupId,
            _requestsPage.value?.items ?: emptyList()
        )
        if (latest != null) {
            focusLiveRoute(latest)
        }
    }

    fun consumeRouteSettle(token: Long) {
        consumedSettleToken = maxOf(consumedSettleToken, token)
        if (_routeSettle.value?.token == token) _routeSettle.value = null
    }

    fun saveConnection(newConfig: ConnectionConfig) {
        credentialStore.saveConnection(newConfig)
        _connectionConfig.value = newConfig
        setupRepository(newConfig)
    }

    fun disconnect() {
        val cleared = try {
            credentialStore.clearCredentials()
            true
        } catch (_: CredentialStorageException) {
            false
        }
        val demoConfig = ConnectionConfig(
            id = "demo-main",
            name = "主网关",
            mode = DataMode.DEMO
        )
        _connectionConfig.value = demoConfig
        setupRepository(demoConfig)
        if (!cleared) {
            _errorMessage.value = "已断开连接，但设备无法清除保存的凭据。请在系统设置中清除本应用数据。"
        }
    }

    fun updateTheme(mode: ThemeMode) {
        viewModelScope.launch { preferencesManager.updateThemeMode(mode) }
    }

    fun updateGlassTier(tier: GlassTier) {
        viewModelScope.launch { preferencesManager.updateGlassTier(tier) }
    }

    fun updateReduceMotion(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.updateReduceMotion(enabled) }
    }

    fun updateMaskAccounts(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.updateMaskAccounts(enabled) }
    }
}
