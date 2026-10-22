package com.magpie.pocket.core.data

import android.content.Context
import com.magpie.pocket.core.model.Account
import com.magpie.pocket.core.model.AccountQuota
import com.magpie.pocket.core.model.Capabilities
import com.magpie.pocket.core.model.ContextBreakdown
import com.magpie.pocket.core.model.Coverage
import com.magpie.pocket.core.model.DataMode
import com.magpie.pocket.core.model.FinalState
import com.magpie.pocket.core.model.HourlyTrendPoint
import com.magpie.pocket.core.model.OverviewSnapshot
import com.magpie.pocket.core.model.Period
import com.magpie.pocket.core.model.QuotaWindow
import com.magpie.pocket.core.model.RequestDetail
import com.magpie.pocket.core.model.RequestKey
import com.magpie.pocket.core.model.RequestPage
import com.magpie.pocket.core.model.RequestSummary
import com.magpie.pocket.core.model.RouteGroup
import com.magpie.pocket.core.model.SourceMeta
import com.magpie.pocket.core.model.TokenUsage
import com.magpie.pocket.core.model.TraceSnapshot
import com.magpie.pocket.core.model.TraceTotals
import com.magpie.pocket.core.model.UsageModelItem
import com.magpie.pocket.core.model.UsageSnapshot
import com.magpie.pocket.core.network.AuthenticationRequiredException
import com.magpie.pocket.core.network.ResourceNotFoundException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

class DemoRepository(private val context: Context? = null, initialJsonString: String? = null) : MagpieRepository {

    private val jsonContent: String = initialJsonString ?: run {
        context?.assets?.open("normalized-demo.json")?.bufferedReader().use { it?.readText() }
            ?: FALLBACK_DEMO_JSON
    }

    private var activeScenario: String = "normal"

    fun setScenario(scenario: String) {
        activeScenario = scenario
    }

    override suspend fun probe(): Capabilities {
        if (activeScenario == "authenticationExpired") {
            throw AuthenticationRequiredException("演示场景：认证过期 (401)")
        }
        val root = JSONObject(jsonContent)
        val cap = root.optJSONObject("capabilities") ?: JSONObject()
        return Capabilities(
            trace = if (activeScenario == "unsupportedTrace") false else cap.optBoolean("trace", true),
            history = cap.optBoolean("history", true),
            requestDetail = cap.optBoolean("requestDetail", true),
            usage = cap.optBoolean("usage", true),
            ledger = cap.optBoolean("ledger", true),
            quotas = cap.optBoolean("quotas", true),
            groups = cap.optBoolean("groups", true),
            statisticsTimeZone = cap.optString("statisticsTimeZone", "Asia/Shanghai"),
            verifiedDeploymentVersion = "Magpie 2.4-preview"
        )
    }

    override suspend fun overview(period: Period): OverviewSnapshot {
        if (activeScenario == "empty") {
            return OverviewSnapshot(
                period = period,
                coverage = Coverage.COMPLETE,
                completedRequests = 0L,
                finalErrors = 0L,
                ttftSumMs = null,
                ttftSampleCount = null,
                sameTimeYesterdayCompletedRequests = null,
                hourlyTrends = emptyList()
            )
        }

        val root = JSONObject(jsonContent)
        val ov = root.optJSONObject("overview") ?: JSONObject()

        // Hourly trends for a realistic today view
        val hourly = listOf(
            HourlyTrendPoint("00:00", 45),
            HourlyTrendPoint("02:00", 20),
            HourlyTrendPoint("04:00", 12),
            HourlyTrendPoint("06:00", 35),
            HourlyTrendPoint("08:00", 180),
            HourlyTrendPoint("10:00", 340),
            HourlyTrendPoint("12:00", 290),
            HourlyTrendPoint("14:00", 420),
            HourlyTrendPoint("16:00", 386)
        )

        return OverviewSnapshot(
            period = period,
            rangeStart = ov.optString("rangeStart", "2026-10-21T16:00:00Z"),
            rangeEnd = ov.optString("rangeEnd", "2026-10-22T01:41:12Z"),
            coverage = Coverage.COMPLETE,
            completedRequests = ov.optLong("completedRequests", 1728L),
            finalErrors = ov.optLong("finalErrors", 4L),
            ttftSumMs = ov.optLong("ttftSumMs", 1800000L),
            ttftSampleCount = ov.optLong("ttftSampleCount", 1000L),
            sameTimeYesterdayCompletedRequests = null,
            hourlyTrends = hourly
        )
    }

    override fun observeTrace(): Flow<TraceSnapshot> = flow {
        val root = JSONObject(jsonContent)
        val tr = root.optJSONObject("trace") ?: JSONObject()
        val totalsObj = tr.optJSONObject("totals") ?: JSONObject()

        var currentSeq = tr.optLong("seq", 480L)
        var completed = totalsObj.optLong("completedRequests", 6240L)
        val rerouted = totalsObj.optLong("rerouteHandoffs", 324L)
        val errors = totalsObj.optLong("finalErrors", 101L)

        val routeSummaries = requests(RequestQuery(limit = 100)).items
        val routeDetails = routeSummaries.map { requestDetail(it.key) }
        while (true) {
            val isObservable = activeScenario != "unsupportedTrace"
            emit(
                TraceSnapshot(
                    epoch = tr.optString("epoch", "demo-epoch-1"),
                    seq = currentSeq,
                    serverNow = tr.optString("serverNow", "2026-10-22T01:41:12Z"),
                    observable = isObservable,
                    retainedRouteIds = routeSummaries.map { it.key.routeId },
                    runningRouteIds = if (activeScenario == "empty") emptyList() else listOf("101"),
                    recentRoutes = routeSummaries,
                    recentDetails = routeDetails,
                    totals = TraceTotals(
                        completedRequests = completed,
                        rerouteHandoffs = rerouted,
                        finalErrors = errors
                    )
                )
            )
            delay(5000)
            currentSeq += 1
            completed += 1
        }
    }

    override suspend fun groups(): List<RouteGroup> {
        val root = JSONObject(jsonContent)
        val arr = root.optJSONArray("groups") ?: return emptyList()
        val list = mutableListOf<RouteGroup>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val pArr = item.optJSONArray("providerIds")
            val pList = mutableListOf<String>()
            if (pArr != null) {
                for (j in 0 until pArr.length()) {
                    pList.add(pArr.getString(j))
                }
            }
            list.add(
                RouteGroup(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    strategy = item.optString("strategy"),
                    affinity = item.optString("affinity"),
                    enabledMemberCount = item.optInt("enabledMemberCount"),
                    providerIds = pList,
                    detailsComplete = item.optBoolean("detailsComplete", true)
                )
            )
        }
        return list
    }

    override suspend fun accounts(): List<Account> {
        val root = JSONObject(jsonContent)
        val arr = root.optJSONArray("accounts") ?: return emptyList()
        val list = mutableListOf<Account>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            list.add(
                Account(
                    id = item.optString("id"),
                    providerId = item.optString("providerId"),
                    alias = item.optString("alias"),
                    availability = item.optString("availability", "AVAILABLE"),
                    cooldownUntil = item.optString("cooldownUntil", null)
                )
            )
        }
        return list
    }

    override suspend fun usage(period: Period): UsageSnapshot {
        val root = JSONObject(jsonContent)
        val us = root.optJSONObject("usage") ?: JSONObject()
        val tok = us.optJSONObject("tokens") ?: JSONObject()
        val mArr = us.optJSONArray("models")

        val modelList = mutableListOf<UsageModelItem>()
        if (mArr != null) {
            for (i in 0 until mArr.length()) {
                val item = mArr.getJSONObject(i)
                modelList.add(
                    UsageModelItem(
                        providerId = item.optString("providerId"),
                        modelId = item.optString("modelId"),
                        totalTokens = item.optLong("totalTokens")
                    )
                )
            }
        }

        return UsageSnapshot(
            period = period,
            coverage = Coverage.COMPLETE,
            tokens = TokenUsage(
                inputUncached = tok.optLong("inputUncached", 500000L),
                output = tok.optLong("output", 800000L),
                cacheRead = tok.optLong("cacheRead", 23038400L),
                cacheWrite = tok.optLong("cacheWrite", 261600L),
                reasoningIncludedInOutput = tok.optLong("reasoningIncludedInOutput", 100000L)
            ),
            models = modelList
        )
    }

    override suspend fun quotas(): List<AccountQuota> {
        val root = JSONObject(jsonContent)
        val arr = root.optJSONArray("quotas") ?: return emptyList()
        val list = mutableListOf<AccountQuota>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val wArr = item.optJSONArray("windows")
            val wList = mutableListOf<QuotaWindow>()
            if (wArr != null) {
                for (j in 0 until wArr.length()) {
                    val w = wArr.getJSONObject(j)
                    wList.add(
                        QuotaWindow(
                            id = w.optString("id"),
                            unit = w.optString("unit"),
                            used = w.optLong("used"),
                            limit = w.optLong("limit"),
                            resetsAt = w.optString("resetsAt", null),
                            usedPercent = if (w.has("used") && !w.has("limit")) w.optDouble("used") else null,
                            amount = if (w.has("amount")) w.optDouble("amount") else null,
                            allowanceLimit = if (w.has("limit")) w.optDouble("limit") else null,
                            display = w.optString("display", null),
                            unlimited = w.optBoolean("unlimited", false)
                        )
                    )
                }
            }
            list.add(
                AccountQuota(
                    accountId = item.optString("accountId"),
                    known = item.optBoolean("known", true),
                    observedAt = item.optString("observedAt", null),
                    windows = wList
                )
            )
        }
        return list
    }

    override suspend fun requests(query: RequestQuery): RequestPage {
        if (activeScenario == "empty") {
            return RequestPage(
                items = emptyList(),
                offset = query.offset,
                total = 0,
                hasMore = false,
                source = "gateway",
                coverage = Coverage.COMPLETE
            )
        }

        val root = JSONObject(jsonContent)
        val arr = root.optJSONArray("requests") ?: return RequestPage(emptyList(), 0, 0, false)
        val allRequests = mutableListOf<RequestSummary>()

        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val keyObj = r.optJSONObject("key") ?: JSONObject()
            val usageObj = r.optJSONObject("usage")
            val metaObj = r.optJSONObject("meta") ?: JSONObject()

            val tokenUsage = if (usageObj != null) {
                TokenUsage(
                    inputUncached = usageObj.optLong("inputUncached"),
                    output = usageObj.optLong("output"),
                    cacheRead = usageObj.optLong("cacheRead"),
                    cacheWrite = usageObj.optLong("cacheWrite"),
                    reasoningIncludedInOutput = usageObj.optLong("reasoningIncludedInOutput")
                )
            } else null

            val meta = SourceMeta(
                connectionId = metaObj.optString("connectionId", "demo-main"),
                mode = DataMode.DEMO,
                observedAt = metaObj.optString("observedAt", ""),
                fetchedAt = metaObj.optString("fetchedAt", ""),
                statisticsTimeZone = metaObj.optString("statisticsTimeZone", "Asia/Shanghai"),
                coverage = Coverage.RECENT_ONLY,
                stale = activeScenario == "offlineWithCache"
            )

            allRequests.add(
                RequestSummary(
                    key = RequestKey(
                        connectionId = keyObj.optString("connectionId", "demo-main"),
                        epoch = keyObj.optString("epoch", "demo-epoch-1"),
                        routeId = keyObj.optString("routeId", ""),
                        sourceDay = keyObj.optString("sourceDay", null)
                    ),
                    startedAt = r.optString("startedAt", ""),
                    agentId = r.optString("agentId", null),
                    groupId = r.optString("groupId", null),
                    requestedModel = r.optString("requestedModel", null),
                    servedModel = r.optString("servedModel", null),
                    providerId = r.optString("providerId", null),
                    accountId = r.optString("accountId", null),
                    finalState = try {
                        FinalState.valueOf(r.optString("finalState", "SUCCEEDED"))
                    } catch (_: Exception) {
                        FinalState.SUCCEEDED
                    },
                    rerouteCount = r.optInt("rerouteCount", 0),
                    ttftMs = if (r.has("ttftMs") && !r.isNull("ttftMs")) r.optLong("ttftMs") else null,
                    durationMs = if (r.has("durationMs") && !r.isNull("durationMs")) r.optLong("durationMs") else null,
                    usage = tokenUsage,
                    meta = meta
                )
            )
        }

        // Apply search filter
        var filtered = allRequests.toList()
        if (!query.query.isNullOrBlank()) {
            val q = query.query.lowercase()
            filtered = filtered.filter {
                (it.servedModel?.lowercase()?.contains(q) == true) ||
                (it.agentId?.lowercase()?.contains(q) == true) ||
                (it.providerId?.lowercase()?.contains(q) == true) ||
                (it.groupId?.lowercase()?.contains(q) == true)
            }
        }

        // Apply status filter: "ALL", "RUNNING", "REROUTED", "ERROR"
        if (!query.statusFilter.isNullOrBlank() && query.statusFilter != "ALL") {
            filtered = when (query.statusFilter) {
                "RUNNING" -> filtered.filter { it.finalState == FinalState.IN_PROGRESS }
                "REROUTED" -> filtered.filter { it.rerouteCount > 0 }
                "ERROR" -> filtered.filter { it.finalState == FinalState.FAILED }
                else -> filtered
            }
        }

        val pagedItems = filtered.drop(query.offset).take(query.limit)
        val hasMore = (query.offset + pagedItems.size) < filtered.size

        return RequestPage(
            items = pagedItems,
            offset = query.offset,
            total = filtered.size,
            hasMore = hasMore,
            source = "gateway",
            coverage = if (activeScenario == "historyTruncated") Coverage.PARTIAL else Coverage.RECENT_ONLY
        )
    }

    override suspend fun requestDetail(key: RequestKey): RequestDetail {
        if (activeScenario == "detailExpired") {
            throw ResourceNotFoundException("该请求详情已过保留期 (404)")
        }

        val all = requests(RequestQuery(limit = 100)).items
        val found = all.find { it.key.routeId == key.routeId }
            ?: throw ResourceNotFoundException("未找到路由 ID: ${key.routeId}")

        val root = JSONObject(jsonContent)
        val ctxObj = root.optJSONObject("selectedContext")

        val contextBreakdown = if (ctxObj != null && key.routeId == "101") {
            val pArr = ctxObj.optJSONArray("parts")
            val pList = mutableListOf<com.magpie.pocket.core.model.ContextPart>()
            if (pArr != null) {
                for (i in 0 until pArr.length()) {
                    val p = pArr.getJSONObject(i)
                    pList.add(
                        com.magpie.pocket.core.model.ContextPart(
                            kind = p.optString("kind"),
                            tokens = p.optLong("tokens")
                        )
                    )
                }
            }
            ContextBreakdown(
                requestKey = key,
                totalTokens = ctxObj.optLong("totalTokens", 214000L),
                windowLimit = if (ctxObj.has("windowLimit") && !ctxObj.isNull("windowLimit")) ctxObj.optLong("windowLimit") else null,
                estimated = ctxObj.optBoolean("estimated", true),
                parts = pList
            )
        } else null

        val decisionEvidence = when (key.routeId) {
            "103" -> listOf("首选账号 wb-01 触发 429 速率限制", "自动改道至备选供应商 workbuddy-ai 账号 wba-03", "第 2 次尝试成功应答")
            "105" -> listOf("供应商 moonshot 超时 (504)", "重试次数已达组策略上限", "向 Agent 返回最终超时错误")
            else -> listOf("按组策略轮询到当前账号", "账号健康检查通过，正常分配")
        }

        return RequestDetail(
            summary = found,
            attempts = found.attempts,
            decisionEvidence = decisionEvidence,
            context = contextBreakdown,
            sanitizedError = if (found.finalState == FinalState.FAILED) "供应商连接超时 (504 Gateway Timeout)" else null,
            sourceMeta = found.meta
        )
    }

    companion object {
        const val FALLBACK_DEMO_JSON = """
        {
          "trace": { "epoch": "demo", "seq": 100, "totals": { "completedRequests": 100, "rerouteHandoffs": 5, "finalErrors": 1 } },
          "overview": { "completedRequests": 50, "finalErrors": 1, "ttftSumMs": 50000, "ttftSampleCount": 25 },
          "groups": [],
          "accounts": [],
          "quotas": [],
          "requests": []
        }
        """
    }
}
