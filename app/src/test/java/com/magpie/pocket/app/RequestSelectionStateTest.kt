package com.magpie.pocket.app

import androidx.lifecycle.viewModelScope
import com.magpie.pocket.core.data.DemoRepository
import com.magpie.pocket.core.model.Attempt
import com.magpie.pocket.core.model.ConnectionConfig
import com.magpie.pocket.core.model.DisplayPreferences
import com.magpie.pocket.core.model.FinalState
import com.magpie.pocket.core.model.RequestKey
import com.magpie.pocket.core.model.RequestSummary
import com.magpie.pocket.core.model.SourceMeta
import com.magpie.pocket.core.model.TraceSnapshot
import com.magpie.pocket.core.model.DataMode
import com.magpie.pocket.core.model.Coverage
import com.magpie.pocket.core.model.ContextBreakdown
import com.magpie.pocket.core.model.RequestDetail
import com.magpie.pocket.core.model.GlassTier
import com.magpie.pocket.core.model.ThemeMode
import com.magpie.pocket.core.network.MagpieApiClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RequestSelectionStateTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val createdViewModels = mutableListOf<AppViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun preselectedRoutingRequestDoesNotOpenDetailModal() = runBlocking {
        val viewModel = newViewModel()
        val page = viewModel.requestsPage.first { it != null }!!
        val first = page.items.first()

        viewModel.selectRoutingRequest(first)

        assertEquals(first.key.routeId, viewModel.selectedRequest.first()?.key?.routeId)
        assertFalse(viewModel.isRequestDetailOpen.first())
        assertTrue(viewModel.selectedRequestDetail.first() != null)
    }

    @Test
    fun closingRequestDetailKeepsRoutingSelection() = runBlocking {
        val viewModel = newViewModel()
        val first = viewModel.requestsPage.first { it != null }!!.items.first()

        viewModel.selectRequest(first)
        assertTrue(viewModel.isRequestDetailOpen.first())

        viewModel.closeRequestDetail()

        assertFalse(viewModel.isRequestDetailOpen.first())
        assertEquals(first.key.routeId, viewModel.selectedRequest.first()?.key?.routeId)
        assertTrue(viewModel.selectedRequestDetail.first() != null)
    }

    @Test
    fun changingGroupClearsMismatchedRoutingSelection() = runBlocking {
        val viewModel = newViewModel()
        val first = viewModel.requestsPage.first { it != null }!!.items.first()
        viewModel.selectRoutingRequest(first)

        val emptyGroup = com.magpie.pocket.core.model.RouteGroup(
            id = "no-request-group",
            name = "No request group"
        )
        val matchingPage = viewModel.requestsPage.first()!!
        val groups = viewModel.groups.first()
        assertTrue(groups.isNotEmpty())
        viewModel.selectGroupId(emptyGroup.id)

        assertNull(viewModel.selectedRequest.first())
        assertNull(viewModel.selectedRequestDetail.first())
        assertTrue(matchingPage.items.isNotEmpty())
    }

    @Test
    fun changingGroupSelectsLatestMatchingRequest() = runBlocking {
        val viewModel = newViewModel()
        val page = viewModel.requestsPage.first { it != null }!!
        val dailyRequest = page.items.first { it.groupId == "daily-coding" }
        viewModel.selectRoutingRequest(page.items.first())

        viewModel.selectGroupId("daily-coding")

        assertEquals(dailyRequest.key.routeId, viewModel.selectedRequest.first()?.key?.routeId)
        assertFalse(viewModel.isRequestDetailOpen.first())
    }

    @Test
    fun liveTraceAdvancesFocusAndCompletionSettlesOnce() = runBlocking {
        val viewModel = newViewModel()
        val page = viewModel.requestsPage.first { it != null }!!
        viewModel.selectGroupId("free-deepseek4.1")
        viewModel.resetToLatest()

        val running = request("900", "free-deepseek4.1", FinalState.IN_PROGRESS, "2026-10-22T12:00:09Z")
        viewModel.applyTraceSnapshot(trace(listOf("900"), listOf(running)))
        assertEquals("900", viewModel.routingRequest.first()?.key?.routeId)

        val done = running.copy(finalState = FinalState.SUCCEEDED)
        viewModel.applyTraceSnapshot(trace(emptyList(), listOf(done)))
        assertEquals("900", viewModel.routingRequest.first()?.key?.routeId)
        assertEquals(FinalState.SUCCEEDED, viewModel.routeSettle.first()?.state)
        val token = viewModel.routeSettle.first()?.token
        viewModel.applyTraceSnapshot(trace(emptyList(), listOf(done)))
        assertEquals(token, viewModel.routeSettle.first()?.token)
        assertTrue(page.items.isNotEmpty())
    }

    @Test
    fun historyPinAndOpenModalAreNotOverwrittenByLiveTrace() = runBlocking {
        val viewModel = newViewModel()
        val page = viewModel.requestsPage.first { it != null }!!
        val historical = page.items.first { it.key.routeId == "102" }
        viewModel.selectGroupId("free-deepseek4.1")
        viewModel.selectRoutingRequest(historical)
        assertFalse(viewModel.isFollowingLive.first())

        val running = request("901", "free-deepseek4.1", FinalState.IN_PROGRESS, "2026-10-22T12:00:09Z")
        viewModel.applyTraceSnapshot(trace(listOf("901"), listOf(running)))
        assertEquals("102", viewModel.routingRequest.first()?.key?.routeId)

        viewModel.selectRequest(historical)
        viewModel.applyTraceSnapshot(trace(listOf("901"), listOf(running.copy(startedAt = "2026-10-22T12:00:10Z"))))
        assertEquals("102", viewModel.selectedRequest.first()?.key?.routeId)
        assertTrue(viewModel.isRequestDetailOpen.first())

        viewModel.closeRequestDetail()
        viewModel.resetToLatest()
        assertTrue(viewModel.isFollowingLive.first())
        assertEquals("901", viewModel.routingRequest.first()?.key?.routeId)
    }

    @Test
    fun otherGroupAndConnectionSwitchCannotLeakRoute() = runBlocking {
        val viewModel = newViewModel()
        viewModel.selectGroupId("free-deepseek4.1")
        val other = request("902", "daily-coding", FinalState.IN_PROGRESS, "2026-10-22T12:00:09Z")
        viewModel.applyTraceSnapshot(trace(listOf("902"), listOf(other)))
        assertTrue(viewModel.routingRequest.first()?.groupId != "daily-coding")

        viewModel.selectGroupId("daily-coding")
        viewModel.applyTraceSnapshot(trace(listOf("902"), listOf(other)))
        assertEquals("902", viewModel.routingRequest.first()?.key?.routeId)

        viewModel.disconnect()
        // A new demo connection may immediately select its own live route, but the
        // previous connection's route must never survive the reset.
        assertTrue(viewModel.routingRequest.first()?.key?.routeId != "902")
        assertTrue(viewModel.isFollowingLive.first())
    }

    @Test
    fun runningRequestIsGlobalWhileRoutingFocusStaysInSelectedGroup() = runBlocking {
        val viewModel = newViewModel()
        viewModel.selectGroupId("free-deepseek4.1")
        val other = request("905", "daily-coding", FinalState.IN_PROGRESS, "2026-10-22T12:00:09Z")

        viewModel.applyTraceSnapshot(trace(listOf("905"), listOf(other)))

        assertEquals("905", viewModel.runningRequest.first()?.key?.routeId)
        assertTrue(viewModel.routingRequest.first()?.key?.routeId != "905")
    }

    @Test
    fun restartedTraceWithOnlyOtherGroupClearsLiveRoutingFocus() = runBlocking {
        val viewModel = newViewModel()
        viewModel.selectGroupId("free-deepseek4.1")
        val running = request("906", "free-deepseek4.1", FinalState.IN_PROGRESS, "2026-10-22T12:00:09Z")
        viewModel.applyTraceSnapshot(trace(listOf("906"), listOf(running)))
        assertEquals("906", viewModel.routingRequest.first()?.key?.routeId)

        val other = request("907", "daily-coding", FinalState.IN_PROGRESS, "2026-10-22T12:00:10Z")
        viewModel.applyTraceSnapshot(
            trace(listOf("907"), listOf(other)).copy(epoch = "e2", restarted = true)
        )

        assertNull(viewModel.routingRequest.first())
        assertNull(viewModel.routingDetail.first())
        assertNull(viewModel.selectedRequest.first())
        assertNull(viewModel.selectedRequestDetail.first())
    }

    @Test
    fun retryMovesFocusToActualNextAccount() = runBlocking {
        val viewModel = newViewModel()
        viewModel.selectGroupId("free-deepseek4.1")
        val retried = request(
            "903", "free-deepseek4.1", FinalState.IN_PROGRESS, "2026-10-22T12:00:09Z",
            attempts = listOf(
                Attempt(1, providerId = "workbuddy", accountId = "wb-01", httpStatus = 429, handedOn = true),
                Attempt(2, providerId = "workbuddy-ai", accountId = "wba-03")
            )
        )
        viewModel.applyTraceSnapshot(trace(listOf("903"), listOf(retried)))
        assertEquals("workbuddy-ai", viewModel.routingRequest.first()?.providerId)
        assertEquals("wba-03", viewModel.routingRequest.first()?.accountId)
    }

    @Test
    fun liveTraceUpdatesRoutingDetailButLeavesOpenHistoricalModalPinned() = runBlocking {
        val viewModel = newViewModel()
        val page = viewModel.requestsPage.first { it != null }!!
        val historical = page.items.first { it.key.routeId == "102" }
        viewModel.selectGroupId("free-deepseek4.1")
        viewModel.selectRequest(historical)
        val modalBefore = viewModel.selectedRequestDetail.first()
        assertNotNull(modalBefore)
        assertTrue(viewModel.isRequestDetailOpen.first())
        assertFalse(viewModel.isFollowingLive.first())

        val running = request("904", "free-deepseek4.1", FinalState.IN_PROGRESS, "2026-10-22T12:00:09Z")
        val detail = detailFor(running, contextTokens = 111L, evidence = "trace evidence")
        viewModel.applyTraceSnapshot(trace(listOf("904"), listOf(running), details = listOf(detail)))

        assertEquals(historical.key, viewModel.selectedRequest.first()?.key)
        assertEquals(modalBefore, viewModel.selectedRequestDetail.first())
        assertEquals("102", viewModel.routingRequest.first()?.key?.routeId)

        viewModel.resetToLatest()
        assertEquals("904", viewModel.routingRequest.first()?.key?.routeId)
        assertEquals("trace evidence", viewModel.routingDetail.first()?.decisionEvidence?.single())
        assertEquals(111L, viewModel.routingDetail.first()?.context?.totalTokens)

        val retried = running.copy(
            attempts = listOf(
                Attempt(1, providerId = "workbuddy", accountId = "wb-01", httpStatus = 429, handedOn = true),
                Attempt(2, providerId = "workbuddy-ai", accountId = "wba-03")
            ),
            providerId = "workbuddy-ai",
            accountId = "wba-03"
        )
        val retriedDetail = detailFor(retried, contextTokens = 222L, evidence = "retry evidence")
        viewModel.applyTraceSnapshot(trace(listOf("904"), listOf(retried), details = listOf(retriedDetail)))

        assertEquals(historical.key, viewModel.selectedRequest.first()?.key)
        assertEquals(modalBefore, viewModel.selectedRequestDetail.first())
        assertEquals("wba-03", viewModel.routingRequest.first()?.accountId)
        assertEquals("retry evidence", viewModel.routingDetail.first()?.decisionEvidence?.single())
        assertEquals(222L, viewModel.routingDetail.first()?.context?.totalTokens)
    }

    @Test
    fun demoTraceSuppliesCachedRoutingDetails() = runBlocking {
        val viewModel = newViewModel()
        val trace = viewModel.trace.first { it != null && it.recentDetails.isNotEmpty() }!!
        assertTrue(trace.recentRoutes.isNotEmpty())
        assertTrue(trace.recentDetails.any { detail ->
            trace.recentRoutes.any { it.key == detail.summary.key }
        })
        assertEquals(trace.recentRoutes.first().key, trace.recentDetails.first().summary.key)
    }

    private fun request(
        id: String,
        groupId: String,
        state: FinalState,
        startedAt: String,
        attempts: List<Attempt> = emptyList()
    ) = RequestSummary(
        key = RequestKey("demo-main", "e", id),
        startedAt = startedAt,
        groupId = groupId,
        providerId = attempts.lastOrNull()?.providerId,
        accountId = attempts.lastOrNull()?.accountId,
        finalState = state,
        attempts = attempts,
        meta = SourceMeta("demo-main", DataMode.DEMO, startedAt, startedAt, coverage = Coverage.RECENT_ONLY)
    )

    private fun detailFor(
        summary: RequestSummary,
        contextTokens: Long,
        evidence: String
    ) = RequestDetail(
        summary = summary,
        attempts = summary.attempts,
        decisionEvidence = listOf(evidence),
        context = ContextBreakdown(summary.key, totalTokens = contextTokens),
        sourceMeta = summary.meta
    )

    private fun trace(
        running: List<String>,
        routes: List<RequestSummary>,
        details: List<RequestDetail> = emptyList()
    ) = TraceSnapshot(
        epoch = "e",
        seq = 1,
        retainedRouteIds = routes.map { it.key.routeId },
        runningRouteIds = running,
        recentRoutes = routes,
        recentDetails = details
    )

    private fun newViewModel(): AppViewModel {
        val fixture = File("src/main/assets/normalized-demo.json")
        val json = if (fixture.exists()) fixture.readText() else DemoRepository.FALLBACK_DEMO_JSON
        return AppViewModel(
            credentialStore = FakeCredentialStore(),
            preferencesManager = FakePreferencesManager(),
            demoRepo = DemoRepository(initialJsonString = json),
            apiClient = MagpieApiClient()
        ).also { createdViewModels += it }
    }
}

private class FakeCredentialStore : ConnectionStateSource {
    private var config = ConnectionConfig(id = "demo-main", name = "主网关", mode = DataMode.DEMO)

    override fun getConnection(): ConnectionConfig = config

    override fun saveConnection(config: ConnectionConfig) {
        this.config = config
    }

    override fun clearCredentials() {
        config = ConnectionConfig(id = "demo-main", name = "主网关", mode = DataMode.DEMO)
    }
}

private class FakePreferencesManager : DisplayPreferencesSource {
    private val state = MutableStateFlow(DisplayPreferences())
    override val displayPreferencesFlow = state

    override suspend fun updateThemeMode(mode: ThemeMode) {
        state.value = state.value.copy(themeMode = mode)
    }

    override suspend fun updateGlassTier(tier: GlassTier) {
        state.value = state.value.copy(glassTier = tier)
    }

    override suspend fun updateReduceMotion(enabled: Boolean) {
        state.value = state.value.copy(reduceMotion = enabled)
    }

    override suspend fun updateMaskAccounts(enabled: Boolean) {
        state.value = state.value.copy(maskAccountNames = enabled)
    }
}
