package com.magpie.pocket.core.data

import com.magpie.pocket.core.model.FinalState
import com.magpie.pocket.core.model.Period
import com.magpie.pocket.core.model.RequestKey
import com.magpie.pocket.core.network.AuthenticationRequiredException
import com.magpie.pocket.core.network.ResourceNotFoundException
import com.magpie.pocket.core.util.MetricCalculators
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DemoRepositoryTest {

    private lateinit var repo: DemoRepository

    @Before
    fun setup() {
        // Load fixture from file directly in unit test
        val fixtureFile = File("src/main/assets/normalized-demo.json")
        val json = if (fixtureFile.exists()) fixtureFile.readText() else DemoRepository.FALLBACK_DEMO_JSON
        repo = DemoRepository(initialJsonString = json)
    }

    @Test
    fun testProbeCapabilities() = runBlocking {
        val cap = repo.probe()
        assertTrue(cap.trace)
        assertTrue(cap.history)
        assertTrue(cap.usage)
        assertEquals("Asia/Shanghai", cap.statisticsTimeZone)
    }

    @Test
    fun testOverviewAndCalculations() = runBlocking {
        val overview = repo.overview(Period.TODAY)
        assertEquals(1728L, overview.completedRequests)
        assertEquals(4L, overview.finalErrors)
        assertEquals(1800000L, overview.ttftSumMs)
        assertEquals(1000L, overview.ttftSampleCount)

        val successRate = MetricCalculators.calculateSuccessRate(overview.completedRequests, overview.finalErrors)
        assertEquals(0.997685, successRate ?: 0.0, 0.0001)

        val ttftMean = MetricCalculators.calculateTtftMean(overview.ttftSumMs, overview.ttftSampleCount)
        assertEquals(1800.0, ttftMean ?: 0.0, 0.001)
    }

    @Test
    fun testGroupsAndAccounts() = runBlocking {
        val groups = repo.groups()
        assertTrue(groups.isNotEmpty())
        val firstGroup = groups.first()
        assertEquals("free-deepseek4.1", firstGroup.id)
        assertEquals("rotate", firstGroup.strategy)

        val accounts = repo.accounts()
        assertTrue(accounts.isNotEmpty())
        val wb01 = accounts.find { it.id == "wb-01" }
        assertNotNull(wb01)
        assertEquals("workbuddy", wb01?.providerId)
    }

    @Test
    fun testQuotasWarning() = runBlocking {
        val quotas = repo.quotas()
        val wba03 = quotas.find { it.accountId == "wba-03" }
        assertNotNull(wba03)
        val daily = wba03!!.windows.first()
        assertEquals(301L, daily.used)
        assertEquals(350L, daily.limit)
        assertTrue(MetricCalculators.isQuotaWarning(daily.used, daily.limit))
    }

    @Test
    fun testRequestsAndFiltering() = runBlocking {
        val all = repo.requests(RequestQuery(limit = 10))
        assertEquals(5, all.items.size)

        // Filter running
        val running = repo.requests(RequestQuery(statusFilter = "RUNNING"))
        assertEquals(1, running.items.size)
        assertEquals("101", running.items.first().key.routeId)
        assertEquals(FinalState.IN_PROGRESS, running.items.first().finalState)

        // Filter rerouted (handoff > 0)
        val rerouted = repo.requests(RequestQuery(statusFilter = "REROUTED"))
        assertEquals(1, rerouted.items.size)
        assertEquals("103", rerouted.items.first().key.routeId)
        assertEquals(1, rerouted.items.first().rerouteCount)

        // Filter error
        val errors = repo.requests(RequestQuery(statusFilter = "ERROR"))
        assertEquals(1, errors.items.size)
        assertEquals("105", errors.items.first().key.routeId)
        assertEquals(FinalState.FAILED, errors.items.first().finalState)
    }

    @Test
    fun testRequestDetailWithContext() = runBlocking {
        val key = RequestKey("demo-main", "demo-epoch-1", "101", "2026-10-22")
        val detail = repo.requestDetail(key)
        assertNotNull(detail)
        assertNotNull(detail.context)
        assertEquals(214000L, detail.context?.totalTokens)
        assertNull(detail.context?.windowLimit) // Window limit unknown
        assertEquals(3, detail.context?.parts?.size)
    }

    @Test(expected = AuthenticationRequiredException::class)
    fun testScenarioAuthenticationExpired(): Unit = runBlocking {
        repo.setScenario("authenticationExpired")
        repo.probe()
    }

    @Test
    fun testScenarioEmpty(): Unit = runBlocking {
        repo.setScenario("empty")
        val ov = repo.overview(Period.TODAY)
        assertEquals(0L, ov.completedRequests)
        val reqs = repo.requests(RequestQuery())
        assertEquals(0, reqs.items.size)
    }

    @Test
    fun testScenarioUnsupportedTrace() = runBlocking {
        repo.setScenario("unsupportedTrace")
        val cap = repo.probe()
        assertFalse(cap.trace)
        val snapshot = repo.observeTrace().first()
        assertFalse(snapshot.observable)
    }

    @Test(expected = ResourceNotFoundException::class)
    fun testScenarioDetailExpired(): Unit = runBlocking {
        repo.setScenario("detailExpired")
        repo.requestDetail(RequestKey("demo-main", "demo-epoch-1", "101"))
    }
}
