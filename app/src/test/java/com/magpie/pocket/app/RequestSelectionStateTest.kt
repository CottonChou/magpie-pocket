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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
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

    @get:org.junit.Rule
    val testTimeout = org.junit.rules.Timeout.seconds(10)

    private val testDispatcher = UnconfinedTestDispatcher()
    private val createdViewModels = mutableListOf<AppViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        runBlocking {
            createdViewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        }
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
        val historical = viewModel.requestsPage.first { it != null }!!.items.first()
        viewModel.selectRoutingRequest(historical)
        assertFalse(viewModel.isFollowingLive.first())

        // Manual group browsing no longer exists: resuming live auto-follow must
        // reconcile against actual request evidence, not a previously pinned group.
        viewModel.resetToLatest()
        val live = request("g-1", "free-deepseek4.1", FinalState.IN_PROGRESS, "2026-10-22T12:00:09Z")
        viewModel.applyTraceSnapshot(trace(listOf("g-1"), listOf(live)))

        assertEquals("g-1", viewModel.routingRequest.first()?.key?.routeId)
        assertEquals("free-deepseek4.1", viewModel.selectedGroupId.first())
        assertTrue(viewModel.isFollowingLive.first())
    }

    @Test
    fun changingGroupSelectsLatestMatchingRequest() = runBlocking {
        val viewModel = newViewModel()
        val older = request("daily-old", "daily-coding", FinalState.IN_PROGRESS, "2026-10-22T12:00:01Z")
        val newer = request("daily-new", "daily-coding", FinalState.IN_PROGRESS, "2026-10-22T12:00:05Z")
        viewModel.applyTraceSnapshot(trace(listOf("daily-old", "daily-new"), listOf(older, newer)))

        // Focus follows the actual newest live request without any group selection call.
        assertEquals("daily-new", viewModel.routingRequest.first()?.key?.routeId)
        assertEquals("daily-coding", viewModel.selectedGroupId.first())
        assertFalse(viewModel.isRequestDetailOpen.first())
    }

    @Test
    fun liveTraceAdvancesFocusAndCompletionSettlesOnce() = runBlocking {
        val viewModel = newViewModel()
        val page = viewModel.requestsPage.first { it != null }!!
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
        val other = request("902", "daily-coding", FinalState.IN_PROGRESS, "2026-10-22T12:00:09Z")
        viewModel.applyTraceSnapshot(trace(listOf("902"), listOf(other)))
        // Automatic focus is global: the actual running request in any group wins.
        assertEquals("902", viewModel.routingRequest.first()?.key?.routeId)
        assertEquals("daily-coding", viewModel.selectedGroupId.first())

        val own = request("908", "free-deepseek4.1", FinalState.IN_PROGRESS, "2026-10-22T12:00:10Z")
        viewModel.applyTraceSnapshot(trace(listOf("908"), listOf(own)))
        assertEquals("908", viewModel.routingRequest.first()?.key?.routeId)

        viewModel.disconnect()
        // A new demo connection may immediately select its own live route, but the
        // previous connection's route must never survive the reset.
        assertTrue(viewModel.routingRequest.first()?.key?.routeId != "902")
        assertTrue(viewModel.isFollowingLive.first())
    }

    @Test
    fun runningRequestIsGlobalWhileRoutingFocusStaysInSelectedGroup() = runBlocking {
        val viewModel = newViewModel()
        val other = request("905", "daily-coding", FinalState.IN_PROGRESS, "2026-10-22T12:00:09Z")

        viewModel.applyTraceSnapshot(trace(listOf("905"), listOf(other)))

        assertEquals("905", viewModel.runningRequest.first()?.key?.routeId)
        assertEquals("905", viewModel.routingRequest.first()?.key?.routeId)
        assertEquals("daily-coding", viewModel.selectedGroupId.first())
    }

    @Test
    fun restartedTraceWithOnlyOtherGroupClearsLiveRoutingFocus() = runBlocking {
        val viewModel = newViewModel()
        val running = request("906", "free-deepseek4.1", FinalState.IN_PROGRESS, "2026-10-22T12:00:09Z")
        viewModel.applyTraceSnapshot(trace(listOf("906"), listOf(running)))
        assertEquals("906", viewModel.routingRequest.first()?.key?.routeId)

        val other = request("907", "daily-coding", FinalState.IN_PROGRESS, "2026-10-22T12:00:10Z")
        viewModel.applyTraceSnapshot(
            trace(listOf("907"), listOf(other)).copy(epoch = "e2", restarted = true)
        )

        // Restart must auto-follow the current epoch, never the previous group's route.
        assertEquals("907", viewModel.routingRequest.first()?.key?.routeId)
        assertEquals("daily-coding", viewModel.selectedGroupId.first())
        assertTrue(viewModel.isFollowingLive.first())
    }

    @Test
    fun retryMovesFocusToActualNextAccount() = runBlocking {
        val viewModel = newViewModel()
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

    @Test
    fun directHistoricalSelectionClearsThePreviousGroup() = runBlocking {
        val viewModel = newViewModel()
        val direct = request("direct-1", null, FinalState.SUCCEEDED, "2026-10-22T12:00:00Z")

        viewModel.selectRoutingRequest(direct)
        assertNull(viewModel.selectedGroupId.first())

        val grouped = request("grouped-1", "daily-coding", FinalState.SUCCEEDED, "2026-10-22T12:00:01Z")
        viewModel.selectRoutingRequest(grouped)
        assertEquals("daily-coding", viewModel.selectedGroupId.first())
        viewModel.selectRequest(direct)
        assertNull(viewModel.selectedGroupId.first())
    }

    @Test
    fun legacyRestartDetachesHistoricalDetailWithoutRebindingOnLaterSnapshots() = runBlocking {
        val viewModel = newViewModel()
        val old = request("reused", "old-group", FinalState.SUCCEEDED, "2026-10-22T12:00:00Z")
            .copy(key = RequestKey("demo-main", "", "reused"), servedModel = "historical-model")
        val oldDetail = detailFor(old, contextTokens = 10L, evidence = "old evidence")
        viewModel.applyTraceSnapshot(
            trace(emptyList(), listOf(old), details = listOf(oldDetail)).copy(epoch = "")
        )
        viewModel.selectRoutingRequest(old)
        assertEquals("old evidence", viewModel.routingDetail.first()?.decisionEvidence?.single())

        val replacement = old.copy(groupId = "new-group", servedModel = "different-model")
        val replacementDetail = detailFor(replacement, contextTokens = 20L, evidence = "new evidence")
        viewModel.applyTraceSnapshot(
            TraceSnapshot(
                epoch = "",
                seq = 1,
                restarted = true,
                recentRoutes = listOf(replacement),
                recentDetails = listOf(replacementDetail)
            )
        )

        assertEquals("historical-model", viewModel.routingRequest.first()?.servedModel)
        assertEquals("old-group", viewModel.selectedGroupId.first())
        assertNull(viewModel.routingDetail.first())

        viewModel.applyTraceSnapshot(
            TraceSnapshot(
                epoch = "",
                seq = 2,
                recentRoutes = listOf(replacement),
                recentDetails = listOf(replacementDetail)
            )
        )

        assertEquals("historical-model", viewModel.routingRequest.first()?.servedModel)
        assertEquals("old-group", viewModel.selectedGroupId.first())
        assertNull(viewModel.routingDetail.first())
    }

    private fun request(
        id: String,
        groupId: String?,
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

    override suspend fun updateKeepLiveConnectionInBackground(enabled: Boolean) {
        state.value = state.value.copy(keepLiveConnectionInBackground = enabled)
    }
}
