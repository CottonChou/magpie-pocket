package com.magpie.pocket.core.data

import com.magpie.pocket.core.model.FinalState
import com.magpie.pocket.core.model.Period
import com.magpie.pocket.core.model.RequestKey
import com.magpie.pocket.core.network.MagpieApiClient
import com.magpie.pocket.core.network.MagpieSession
import com.magpie.pocket.core.util.MetricCalculators
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LiveMagpieRepositoryCompatTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: LiveMagpieRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/state" -> json("""{"version":"Magpie current","timezone":"Asia/Shanghai"}""")
                request.path == "/api/groups" -> fixture("groups-current.json")
                request.path?.startsWith("/api/gateway/trace") == true -> fixture("trace-current.json")
                request.path?.startsWith("/api/gateway/route") == true -> {
                    val root = org.json.JSONObject(fixtureText("trace-current.json"))
                    json(root.getJSONArray("routes").getJSONObject(0).toString())
                }
                request.path?.startsWith("/api/usage/requests") == true -> fixture("ledger-current.json")
                request.path == "/api/usage/quotas" -> fixture("quotas-current.json")
                request.path?.startsWith("/api/usage") == true -> fixture("usage-current.json")
                else -> MockResponse().setResponseCode(404)
            }
        }
        repo = LiveMagpieRepository(server.url("/").toString(), "compat", MagpieApiClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun currentGroupsUseModelMembersAndMemberInfoProviders() = runBlocking {
        val cap = repo.probe()
        assertTrue(cap.groups)
        assertTrue(cap.trace)

        val groups = repo.groups()
        assertEquals("coding", groups.single().id)
        assertEquals("round-robin", groups.single().strategy)
        assertEquals(listOf("vendor"), groups.single().providerIds)
        assertEquals(1, groups.single().enabledMemberCount)

        // A string member is a model id, never an account id.
        assertTrue(repo.accounts().isEmpty())
    }

    @Test
    fun currentTraceMapsNumericIdsOrderEvidenceAndUsageArray() = runBlocking {
        repo.probe()
        val trace = repo.observeTrace().first()
        assertEquals("101", trace.retainedRouteIds.single())
        assertTrue(trace.runningRouteIds.isEmpty())

        val detail = repo.requestDetail(RequestKey("compat", "", "101"))
        assertEquals("2026-10-22T09:59:55Z", detail.summary.startedAt)
        assertEquals("coding", detail.summary.groupId)
        assertEquals("vendor", detail.summary.providerId)
        assertEquals("account-a", detail.summary.accountId)
        assertEquals("model-a", detail.summary.servedModel)
        assertEquals(3000L, detail.summary.durationMs)
        assertEquals(800L, detail.summary.ttftMs)
        assertEquals(FinalState.SUCCEEDED, detail.summary.finalState)
        assertEquals(100L, detail.summary.usage?.inputUncached)
        assertEquals(50L, detail.summary.usage?.output)
        assertEquals(200L, detail.summary.usage?.cacheRead)
        // Reasoning is already part of output and is not counted twice.
        assertEquals(10L, detail.summary.usage?.reasoningIncludedInOutput)
        assertEquals(350L, MetricCalculators.calculateTotalTokens(detail.summary.usage))
        assertEquals("tool_results", detail.context?.parts?.first()?.kind)
        assertEquals("turn · same turn", detail.decisionEvidence.first().substringAfter(": "))
    }

    @Test
    fun currentLedgerRowsAndUsageSeriesMapWithoutInventedTotals() = runBlocking {
        repo.probe()
        val page = repo.requests(RequestQuery())
        assertEquals(1, page.total)
        val row = page.items.single()
        assertEquals("101", row.key.routeId)
        assertEquals("coding", row.groupId)
        assertEquals("group/coding", "group/${row.groupId}")
        assertEquals("model-a", row.requestedModel)
        assertEquals("model-a", row.servedModel)
        assertEquals("account-a", row.accountId)
        assertEquals(3000L, row.durationMs)
        assertEquals(800L, row.ttftMs)
        assertEquals(FinalState.SUCCEEDED, row.finalState)

        val overview = repo.overview(Period.TODAY)
        assertEquals("2026-10-22T00:00:00Z", overview.rangeStart)
        assertEquals(10L, overview.hourlyTrends.single().completedRequests)
        assertEquals("09", overview.hourlyTrends.single().hourLabel)

        val usage = repo.usage(Period.TODAY)
        assertEquals(3500L, MetricCalculators.calculateTotalTokens(usage.tokens))
        assertEquals(3500L, usage.models.single().totalTokens)
    }

    @Test
    fun currentQuotasRootArrayKeepsPercentAndDecimals() = runBlocking {
        repo.probe()
        val quotas = repo.quotas()
        val account = quotas.first { it.accountId == "account-a" }
        assertTrue(account.known)
        assertEquals(3, account.windows.size)

        val credits = account.windows.first()
        assertEquals("credits", credits.id)
        assertEquals(52.5, credits.usedPercent ?: 0.0, 0.0001)
        assertEquals(10.5, credits.amount ?: 0.0, 0.0001)
        assertEquals(20.0, credits.allowanceLimit ?: 0.0, 0.0001)
        assertEquals(0.525, MetricCalculators.calculateQuotaUsedRatio(credits) ?: 0.0, 0.0001)

        val percentageOnly = account.windows[1]
        assertTrue(percentageOnly.known)
        assertEquals(0.525, MetricCalculators.calculateQuotaUsedRatio(percentageOnly) ?: 0.0, 0.0001)
        assertNull(percentageOnly.amount)
        assertNull(percentageOnly.allowanceLimit)

        val unknown = account.windows[2]
        assertFalse(unknown.known)
        assertNull(MetricCalculators.calculateQuotaUsedRatio(unknown))

        val unlimited = quotas.first { it.accountId == "unlimited-account" }
        assertFalse(unlimited.known)
        assertNull(MetricCalculators.calculateQuotaUsedRatio(unlimited.windows.single()))
    }

    @Test
    fun traceCachePrunesRetainedIdsAndResetsOnEpoch() = runBlocking {
        val requests = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/state") {
                    return json("""{"version":"Magpie current","timezone":"Asia/Shanghai"}""")
                }
                if (request.path?.startsWith("/api/gateway/trace") == true) {
                    return when (requests.incrementAndGet()) {
                        1 -> json("""{"epoch":"e1","seq":1,"mine":true,"ids":["1"],"routes":[{"id":1,"time":"2026-10-22T09:59:55Z","group":{"id":"g1"},"done":false,"tries":[{"id":"a","provider":"p1","account":"a1"}]}]}""")
                        2 -> json("""{"epoch":"e1","seq":2,"mine":true,"ids":["2"],"routes":[{"id":2,"time":"2026-10-22T10:00:00Z","group":{"id":"g1"},"done":true,"status":200,"tries":[{"id":"b","provider":"p2","account":"a2","done":true,"status":200}]}]}""")
                        else -> json("""{"epoch":"e2","seq":1,"mine":true,"ids":["3"],"routes":[{"id":3,"time":"2026-10-22T10:00:01Z","group":{"id":"g1"},"done":false}]}""")
                    }
                }
                return MockResponse().setResponseCode(404)
            }
        }
        repo.probe()
        val first = repo.observeTrace().first()
        assertEquals(listOf("2"), first.recentRoutes.map { it.key.routeId })

        val second = repo.observeTrace().first()
        assertEquals(listOf("3"), second.recentRoutes.map { it.key.routeId })

        val third = repo.observeTrace().first()
        assertEquals(listOf("3"), third.recentRoutes.map { it.key.routeId })
        assertEquals("e2", third.epoch)
    }

    @Test
    fun unchangedTraceSequencePausesInsteadOfBusyLooping() {
        assertEquals(5000L, repo.traceIdleDelayMs(0L, 0L))
        assertEquals(1500L, repo.traceIdleDelayMs(7L, 7L))
        assertNull(repo.traceIdleDelayMs(7L, 8L))
    }

    @Test
    fun deployedTraceWithoutIdsResetsOnSeqRollbackAndCapsAtSixty() = runBlocking {
        val requests = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/state") {
                    return json("""{"version":"Magpie current","timezone":"Asia/Shanghai"}""")
                }
                if (request.path?.startsWith("/api/gateway/trace") == true) {
                    val n = requests.incrementAndGet()
                    return when (n) {
                        1 -> json("""{"seq":10,"mine":true,"routes":[{"id":"old","time":"2026-10-22T09:00:00Z","group":{"id":"g1"},"done":true,"status":200}]}""")
                        2 -> json("""{"seq":10,"mine":true,"routes":[{"id":"old","time":"2026-10-22T09:00:00Z","group":{"id":"g1"},"done":true,"status":200}]}""")
                        3 -> json("""{"seq":11,"mine":true,"routes":[{"id":"new","time":"2026-10-22T10:00:00Z","group":{"id":"g1"},"done":false}]}""")
                        4 -> json("""{"seq":2,"mine":true,"routes":[{"id":"restart","time":"2026-10-22T11:00:00Z","group":{"id":"g1"},"done":false}]}""")
                        else -> {
                            val routes = (1..65).joinToString(",") { i ->
                                """{"id":"$i","time":"2026-10-22T12:00:${(i % 60).toString().padStart(2, '0')}Z","group":{"id":"g1"},"done":true,"status":200}"""
                            }
                            json("""{"seq":${n + 2},"mine":true,"routes":[$routes]}""")
                        }
                    }
                }
                return MockResponse().setResponseCode(404)
            }
        }
        repo.probe()

        val first = repo.observeTrace().first()
        assertEquals(listOf("old"), first.recentRoutes.map { it.key.routeId })
        assertFalse(first.restarted)

        val second = repo.observeTrace().first()
        assertEquals(setOf("old", "new"), second.recentRoutes.map { it.key.routeId }.toSet())

        val restart = repo.observeTrace().first()
        assertTrue(restart.restarted)
        assertEquals(listOf("restart"), restart.recentRoutes.map { it.key.routeId })

        val capped = repo.observeTrace().first()
        assertEquals(60, capped.recentRoutes.size)
        assertEquals(60, capped.recentDetails.size)
        assertEquals(60, capped.retainedRouteIds.size)
    }

    @Test
    fun currentSessionProbeClaimsOnlyReachedCapabilities() = runBlocking {
        val result = MagpieSession.connect(MagpieApiClient(), server.url("/").toString(), null)
        assertTrue(result.capabilities.groups)
        assertTrue(result.capabilities.trace)
        assertTrue(result.capabilities.usage)
        assertTrue(result.capabilities.ledger)
        assertTrue(result.capabilities.quotas)
        assertFalse(result.capabilities.history)
        assertFalse(result.capabilities.requestDetail)
    }

    private fun fixture(name: String) = json(fixtureText(name))

    private fun fixtureText(name: String): String =
        javaClass.classLoader!!.getResource("upstream/$name")!!.readText()

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
