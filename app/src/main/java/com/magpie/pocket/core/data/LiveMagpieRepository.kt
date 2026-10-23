package com.magpie.pocket.core.data

import com.magpie.pocket.core.model.Account
import com.magpie.pocket.core.model.AccountQuota
import com.magpie.pocket.core.model.Attempt
import com.magpie.pocket.core.model.Capabilities
import com.magpie.pocket.core.model.ContextBreakdown
import com.magpie.pocket.core.model.ContextPart
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
import com.magpie.pocket.core.network.MagpieApiClient
import com.magpie.pocket.core.network.MagpieApiException
import com.magpie.pocket.core.network.MagpieSession
import com.magpie.pocket.core.network.ResourceNotFoundException
import com.magpie.pocket.core.network.UpstreamRoute
import com.magpie.pocket.core.network.UpstreamTry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.magpie.pocket.core.util.RequestTimestamps
import java.util.concurrent.ConcurrentHashMap

class LiveMagpieRepository(
    private val baseUrl: String,
    private val connectionId: String,
    private val apiClient: MagpieApiClient = MagpieApiClient(),
    private val webKey: String? = null
) : MagpieRepository {

    private val cachedRoutes = ConcurrentHashMap<String, UpstreamRoute>()
    private var lastEpoch: String = ""
    private var lastSeq: Long = 0L
    private var hasObservedTrace: Boolean = false
    private var sessionReady: Boolean = false

    override suspend fun probe(): Capabilities {
        val result = MagpieSession.connect(apiClient, baseUrl, webKey)
        sessionReady = true
        return result.capabilities
    }

    override suspend fun probeLight() {
        ensureSession()
        apiClient.getState(baseUrl)
    }

    private suspend fun ensureSession() {
        if (sessionReady) return
        MagpieSession.initialize(apiClient, baseUrl, webKey)
        sessionReady = true
    }

    override suspend fun overview(period: Period): OverviewSnapshot {
        val periodParam = if (period == Period.TODAY) "today" else "7d"
        val resp = apiClient.getUsage(baseUrl, periodParam)

        val hourlyList = resp.series?.map {
            HourlyTrendPoint(it.label ?: it.time ?: "", it.calls)
        } ?: resp.hourly?.map {
            HourlyTrendPoint(it.hour, it.calls)
        } ?: emptyList()

        return OverviewSnapshot(
            period = period,
            rangeStart = resp.rangeStart ?: resp.since,
            rangeEnd = resp.rangeEnd,
            coverage = Coverage.COMPLETE,
            completedRequests = resp.calls,
            finalErrors = resp.errors,
            ttftSumMs = resp.ttftMs,
            ttftSampleCount = resp.timed,
            sameTimeYesterdayCompletedRequests = null,
            hourlyTrends = hourlyList
        )
    }

    override fun observeTrace(): Flow<TraceSnapshot> = flow {
        ensureSession()
        var currentSeq = 0L

        while (true) {
            try {
                val previousSeq = currentSeq
                val resp = apiClient.getTraceSnapshot(baseUrl, currentSeq)
                val epochChanged = resp.epoch != lastEpoch
                val seqRolledBack = hasObservedTrace && resp.seq < lastSeq
                val restarted = hasObservedTrace && (epochChanged || seqRolledBack)
                if (epochChanged || seqRolledBack) {
                    // Route ids are only meaningful inside one trace epoch.
                    cachedRoutes.clear()
                    lastEpoch = resp.epoch
                }
                currentSeq = resp.seq
                lastSeq = resp.seq
                hasObservedTrace = true

                resp.routes?.forEach { route ->
                    cachedRoutes[route.id] = route
                }

                val retained: List<String>
                if (resp.ids != null) {
                    retained = resp.ids
                    val retainedSet = retained.toSet()
                    cachedRoutes.keys.retainAll(retainedSet)
                } else {
                    // The deployed shape has no ids: keep the same latest-60 window
                    // the browser uses, ordered by real instants rather than raw text.
                    retained = cachedRoutes.values
                        .sortedByDescending { routeMillis(it) }
                        .take(60)
                        .map { it.id }
                    val retainedSet = retained.toSet()
                    cachedRoutes.keys.retainAll(retainedSet)
                }
                val running = cachedRoutes.values
                    .filter { it.done != true }
                    .map { it.id }
                    .filter { it in retained }
                val details = retained
                    .mapNotNull { cachedRoutes[it] }
                    .sortedByDescending { routeMillis(it) }
                    .map { it.toRequestDetail(RequestKey(connectionId, lastEpoch, it.id)) }

                val totals = resp.totals
                emit(
                    TraceSnapshot(
                        epoch = resp.epoch,
                        seq = resp.seq,
                        serverNow = resp.now ?: RequestTimestamps.nowUtcIso(),
                        observable = resp.mine,
                        restarted = restarted,
                        retainedRouteIds = retained,
                        runningRouteIds = running,
                        recentRoutes = details.map { it.summary },
                        recentDetails = details,
                        totals = TraceTotals(
                            completedRequests = totals?.requests ?: 0L,
                            rerouteHandoffs = totals?.rerouted ?: 0L,
                            finalErrors = totals?.errors ?: 0L
                        )
                    )
                )

                // An unchanged sequence means the server did not actually wait;
                // pause briefly instead of immediately reconnecting in a tight loop.
                traceIdleDelayMs(previousSeq, currentSeq)?.let { delay(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ResourceNotFoundException) {
                // Trace is genuinely unsupported by this server; stop instead of looping.
                throw e
            } catch (e: Exception) {
                // Surface auth/transport failures instead of hiding them behind a
                // forever-successful empty stream.
                throw e
            }
        }
    }

    override suspend fun groups(): List<RouteGroup> {
        val resp = apiClient.getGroups(baseUrl)
        return resp.groups?.map { g ->
            val info = g.memberInfo ?: g.members.orEmpty()
            // A string member is a model id, never an account id. Nested groups
            // are not providers, so they contribute no provider evidence.
            val pIds = info
                .filterNot { it.group }
                .mapNotNull { it.provider }
                .distinct()
            RouteGroup(
                id = g.id,
                name = g.name ?: g.id,
                strategy = g.routing ?: g.strategy,
                affinity = g.affinity,
                enabledMemberCount = info.count {
                    !it.group && it.enabled && it.ready && !it.providerOff
                },
                providerIds = pIds,
                detailsComplete = true
            )
        } ?: emptyList()
    }

    override suspend fun accounts(): List<Account> {
        val groups = apiClient.getGroups(baseUrl)
        val list = mutableListOf<Account>()
        var idx = 1
        groups.groups?.forEach { g ->
            val info = g.memberInfo ?: emptyList()
            info.filterNot { it.group }.forEach { m ->
                // memberInfo describes models; only explicit account metadata
                // is allowed to become an account row.
                val accName = m.account ?: return@forEach
                list.add(
                    Account(
                        id = accName,
                        providerId = m.provider ?: "default",
                        alias = m.name ?: "账号 ${idx++}",
                        availability = when {
                            m.providerOff || !m.enabled -> "DISABLED"
                            !m.ready -> "UNKNOWN"
                            else -> "AVAILABLE"
                        }
                    )
                )
            }
        }
        return list.distinctBy { it.id }
    }

    override suspend fun usage(period: Period): UsageSnapshot {
        val periodParam = if (period == Period.TODAY) "today" else "7d"
        val resp = apiClient.getUsage(baseUrl, periodParam)

        val models = resp.models?.map {
            UsageModelItem(
                providerId = it.provider ?: "unknown",
                modelId = it.model ?: "unknown",
                totalTokens = it.total ?: (
                    (it.input ?: 0L) + (it.output ?: 0L) +
                        (it.cacheRead ?: 0L) + (it.cacheWrite ?: 0L)
                    )
            )
        } ?: emptyList()

        return UsageSnapshot(
            period = period,
            coverage = Coverage.COMPLETE,
            tokens = TokenUsage(
                inputUncached = resp.input,
                output = resp.output,
                cacheRead = resp.cacheRead,
                cacheWrite = resp.cacheWrite
            ),
            models = models
        )
    }

    override suspend fun quotas(): List<AccountQuota> {
        val resp = apiClient.getQuotas(baseUrl)
        return resp.quotas?.map { q ->
            val windows = q.windows?.map { w ->
                val hasAbsolute = w.amount != null || w.limit != null
                val hasPercent = w.used != null
                QuotaWindow(
                    id = w.name ?: w.id ?: "window",
                    unit = w.unit ?: "",
                    used = w.amount?.toLong() ?: 0L,
                    limit = w.limit?.toLong() ?: 0L,
                    resetsAt = w.resetsAt ?: w.resets,
                    usedPercent = w.used,
                    amount = w.amount,
                    allowanceLimit = w.limit,
                    display = w.display,
                    unlimited = w.unlimited,
                    known = !w.unlimited && (hasAbsolute || hasPercent)
                )
            } ?: emptyList()

            AccountQuota(
                accountId = q.user ?: q.account ?: q.name ?: "unknown",
                known = q.known && windows.any { it.known },
                observedAt = q.readAt ?: q.observed ?: RequestTimestamps.nowUtcIso(),
                windows = windows
            )
        } ?: emptyList()
    }

    override suspend fun requests(query: RequestQuery): RequestPage {
        val isFailed = query.statusFilter == "ERROR"
        // Capture before the request: a trace restart may happen while the ledger
        // call is in flight, and these rows belong to the epoch that was current
        // when they were requested.
        val requestEpoch = lastEpoch
        val resp = apiClient.getLedgerRequests(
            baseUrl = baseUrl,
            period = if (query.day != null) "day" else "today",
            offset = query.offset,
            limit = query.limit,
            query = query.query,
            failedOnly = isFailed
        )

        val summaries = (resp.rows ?: resp.records)?.map { rec ->
            val finalState = when {
                rec.failed -> FinalState.FAILED
                rec.status == null || rec.status == 0 -> FinalState.IN_PROGRESS
                rec.status in 200..299 -> FinalState.SUCCEEDED
                else -> FinalState.FAILED
            }

            val rerouteCount = if (rec.retried) 1 else 0
            val groupFromReq = rec.req?.takeIf { it.startsWith("group/") }
                ?.removePrefix("group/")
            val requested = rec.req?.takeUnless { it.startsWith("group/") }
                ?: rec.model

            RequestSummary(
                key = RequestKey(
                    connectionId = connectionId,
                    epoch = requestEpoch,
                    routeId = rec.routeId ?: rec.route ?: rec.id ?: "0",
                    sourceDay = rec.day
                ),
                startedAt = rec.t ?: rec.time ?: RequestTimestamps.nowUtcIso(),
                agentId = rec.agent,
                groupId = groupFromReq,
                requestedModel = requested,
                servedModel = rec.served ?: rec.model,
                providerId = rec.provider,
                accountId = rec.providerAccount ?: rec.account,
                finalState = finalState,
                rerouteCount = rerouteCount,
                ttftMs = rec.ttftMs,
                durationMs = rec.ms ?: rec.elapsedMs,
                usage = TokenUsage(
                    inputUncached = rec.inputUncached,
                    output = rec.output,
                    cacheRead = rec.cacheRead,
                    cacheWrite = rec.cacheWrite,
                    reasoningIncludedInOutput = rec.reasoning
                ),
                meta = SourceMeta(
                    connectionId = connectionId,
                    mode = DataMode.LIVE,
                    observedAt = RequestTimestamps.nowUtcIso(),
                    fetchedAt = RequestTimestamps.nowUtcIso(),
                    coverage = Coverage.RECENT_ONLY,
                    stale = false
                )
            )
        } ?: emptyList()

        val hasMore = (query.offset + summaries.size) < resp.total

        return RequestPage(
            items = summaries,
            offset = query.offset,
            total = resp.total,
            hasMore = hasMore,
            source = "gateway",
            coverage = Coverage.RECENT_ONLY,
            sourceDay = query.day
        )
    }

    override suspend fun requestDetail(key: RequestKey): RequestDetail {
        val route = cachedRoutes[key.routeId] ?: run {
            try {
                apiClient.getRoute(baseUrl, key.routeId, key.sourceDay)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is MagpieApiException) throw e
                throw MagpieApiException("未能获取请求详情")
            }
        }

        return route.toRequestDetail(key)
    }

    private fun UpstreamRoute.toRequestDetail(key: RequestKey): RequestDetail {
        val orderById = order.orEmpty().associateBy { it.id }
        val attempts = tries?.mapIndexed { idx, t ->
            val ordered = t.id?.let { orderById[it] }
            Attempt(
                index = idx + 1,
                providerId = t.provider ?: ordered?.provider ?: provider,
                accountId = t.account ?: t.id,
                model = t.served ?: t.model ?: ordered?.model,
                startedAt = t.start ?: t.started,
                httpStatus = t.status,
                durationMs = t.ms ?: t.elapsed,
                ttftMs = t.ttft,
                errorCode = t.error,
                handedOn = idx < (tries.size - 1)
            )
        } ?: emptyList()

        val prompt = prompt
        val contextBreakdown = if (prompt != null && prompt.tokens != null) {
            val parts = prompt.parts?.map {
                ContextPart(it.kind ?: it.type ?: "other", it.tokens ?: 0L)
            } ?: emptyList()

            ContextBreakdown(
                requestKey = key,
                totalTokens = prompt.tokens,
                windowLimit = prompt.window,
                estimated = false,
                parts = parts
            )
        } else null

        // Sum supplied token categories. Reasoning is already part of output
        // when present and must not be added twice.
        val routeUsages = usage.orEmpty()
        val usage = if (routeUsages.isEmpty()) {
            null
        } else {
            TokenUsage(
                inputUncached = routeUsages.sumOf { it.input ?: 0L },
                output = routeUsages.sumOf { it.output ?: 0L },
                cacheRead = routeUsages.sumOf { it.cacheRead ?: 0L },
                cacheWrite = routeUsages.sumOf { it.cacheWrite ?: 0L },
                reasoningIncludedInOutput = routeUsages.sumOf { it.reasoning ?: 0L }
            )
        }
        val lastTry = tries?.lastOrNull()
        val lastOrder = lastTry?.id?.let { orderById[it] }
        val actualServed = served ?: lastTry?.served ?: lastTry?.model
        val actualProvider = lastTry?.provider ?: lastOrder?.provider ?: provider
        val actualAccount = lastTry?.account ?: lastTry?.id
        val startedAt = time ?: started ?: RequestTimestamps.nowUtcIso()

        val summary = RequestSummary(
            key = key,
            startedAt = startedAt,
            agentId = agent,
            groupId = group?.id,
            requestedModel = model,
            servedModel = actualServed ?: model,
            providerId = actualProvider,
            accountId = actualAccount,
            finalState = when {
                done != true -> FinalState.IN_PROGRESS
                error != null -> FinalState.FAILED
                status in 200..299 -> FinalState.SUCCEEDED
                status != null -> FinalState.FAILED
                else -> FinalState.UNCONFIRMED
            },
            rerouteCount = (attempts.size - 1).coerceAtLeast(0),
            ttftMs = ttft,
            durationMs = ms ?: elapsed,
            usage = usage,
            meta = SourceMeta(
                connectionId = connectionId,
                mode = DataMode.LIVE,
                observedAt = RequestTimestamps.nowUtcIso(),
                fetchedAt = RequestTimestamps.nowUtcIso(),
                coverage = Coverage.COMPLETE
            ),
            attempts = attempts
        )

        val evidence = mutableListOf<String>()
        affinity?.let { affinity ->
            val detail = listOfNotNull(
                affinity.mode?.takeIf { it.isNotBlank() },
                affinity.why?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (detail.isNotBlank()) evidence.add("会话亲和性: $detail")
        }
        rule?.let { evidence.add("路由规则: $it") }
        if (attempts.size > 1) {
            evidence.add("发生 ${attempts.size - 1} 次自动改道重试")
        }

        return RequestDetail(
            summary = summary,
            attempts = attempts,
            decisionEvidence = evidence,
            context = contextBreakdown,
            sanitizedError = error?.let { sanitizeError(it) },
            sourceMeta = summary.meta
        )
    }

    private fun sanitizeError(raw: String): String {
        // Strip sensitive authorization headers, bearer tokens, or user filesystem paths
        return raw
            .replace(Regex("(?i)bearer\\s+[a-zA-Z0-9_.-]+"), "Bearer ***")
            .replace(Regex("(?i)key=[a-zA-Z0-9_.-]+"), "key=***")
            .replace(Regex("/Users/[^/]+/"), "/Users/***/")
            .replace(Regex("/home/[^/]+/"), "/home/***/")
    }

    internal fun traceIdleDelayMs(previousSeq: Long, currentSeq: Long): Long? = when {
        currentSeq == 0L -> 5000L
        currentSeq == previousSeq -> 1500L
        else -> null
    }

    private fun UpstreamRoute.toRequestSummary(): RequestSummary {
        return toRequestDetail(RequestKey(connectionId, lastEpoch, id)).summary
    }

    private fun routeMillis(route: UpstreamRoute): Long =
        RequestTimestamps.parseMillis(route.time ?: route.started) ?: Long.MIN_VALUE

    private fun UpstreamTry.toAttempt(index: Int, lastIndex: Int): Attempt = Attempt(
        index = index + 1,
        providerId = provider,
        accountId = account ?: id,
        model = served ?: model,
        startedAt = start ?: started,
        httpStatus = status,
        durationMs = ms ?: elapsed,
        ttftMs = ttft,
        errorCode = error,
        handedOn = index < lastIndex
    )
}
