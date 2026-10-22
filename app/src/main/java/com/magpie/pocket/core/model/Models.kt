package com.magpie.pocket.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class DataMode {
    DEMO, LIVE
}

@Serializable
enum class Coverage {
    COMPLETE, RECENT_ONLY, PARTIAL, UNKNOWN
}

@Serializable
enum class FinalState {
    PENDING, IN_PROGRESS, SUCCEEDED, FAILED, CANCELLED, UNCONFIRMED
}

@Serializable
enum class Period {
    TODAY, SEVEN_DAYS
}

@Serializable
enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

@Serializable
enum class GlassTier {
    CLEAR, TINTED, SOLID
}

enum class ConnectionHealth {
    CONNECTING, CONNECTED, AUTH_REQUIRED, ACCESS_DENIED, NETWORK_UNREACHABLE, INCOMPATIBLE, OFFLINE
}

data class ConnectionStatus(
    val health: ConnectionHealth,
    val message: String? = null
)

@Serializable
data class SourceMeta(
    val connectionId: String,
    val mode: DataMode,
    val observedAt: String,
    val fetchedAt: String,
    val statisticsTimeZone: String? = null,
    val coverage: Coverage = Coverage.COMPLETE,
    val stale: Boolean = false
)

@Serializable
data class RequestKey(
    val connectionId: String,
    val epoch: String,
    val routeId: String,
    val sourceDay: String? = null
)

@Serializable
data class TokenUsage(
    val inputUncached: Long? = 0L,
    val output: Long? = 0L,
    val cacheRead: Long? = 0L,
    val cacheWrite: Long? = 0L,
    val reasoningIncludedInOutput: Long? = 0L
)

@Serializable
data class Attempt(
    val index: Int,
    val providerId: String? = null,
    val accountId: String? = null,
    val model: String? = null,
    val startedAt: String? = null,
    val httpStatus: Int? = null,
    val durationMs: Long? = null,
    val ttftMs: Long? = null,
    val errorCode: String? = null,
    val handedOn: Boolean = false
)

@Serializable
data class RequestSummary(
    val key: RequestKey,
    val startedAt: String,
    val agentId: String? = null,
    val groupId: String? = null,
    val requestedModel: String? = null,
    val servedModel: String? = null,
    val providerId: String? = null,
    val accountId: String? = null,
    val finalState: FinalState,
    val rerouteCount: Int = 0,
    val ttftMs: Long? = null,
    val durationMs: Long? = null,
    val usage: TokenUsage? = null,
    val meta: SourceMeta,
    val attempts: List<Attempt> = emptyList()
)

@Serializable
data class RouteGroup(
    val id: String,
    val name: String,
    val strategy: String? = null,
    val affinity: String? = null,
    val enabledMemberCount: Int = 0,
    val providerIds: List<String> = emptyList(),
    val detailsComplete: Boolean = true
)

@Serializable
data class Account(
    val id: String,
    val providerId: String,
    val alias: String,
    val availability: String = "AVAILABLE", // AVAILABLE, BUSY, COOLDOWN, UNKNOWN
    val cooldownUntil: String? = null
)

@Serializable
data class QuotaWindow(
    val id: String,
    val unit: String,
    val used: Long,
    val limit: Long,
    val resetsAt: String? = null,
    /** Upstream's used share, 0..100, when the server reports a percentage. */
    val usedPercent: Double? = null,
    /** Upstream's own count and allowance, which can be fractional. */
    val amount: Double? = null,
    val allowanceLimit: Double? = null,
    val display: String? = null,
    val unlimited: Boolean = false,
    val known: Boolean = true
)

@Serializable
data class AccountQuota(
    val accountId: String,
    val known: Boolean,
    val observedAt: String? = null,
    val windows: List<QuotaWindow> = emptyList()
)

@Serializable
data class ContextPart(
    val kind: String,
    val tokens: Long
)

@Serializable
data class ContextBreakdown(
    val requestKey: RequestKey,
    val totalTokens: Long,
    val windowLimit: Long? = null,
    val estimated: Boolean = false,
    val parts: List<ContextPart> = emptyList()
)

@Serializable
data class TraceTotals(
    val completedRequests: Long = 0L,
    val rerouteHandoffs: Long = 0L,
    val finalErrors: Long = 0L
)

@Serializable
data class TraceSnapshot(
    val epoch: String,
    val seq: Long,
    val serverNow: String? = null,
    val observable: Boolean = true,
    /** True when the trace sequence/epoch restarted; no old completion may replay. */
    val restarted: Boolean = false,
    val retainedRouteIds: List<String> = emptyList(),
    val runningRouteIds: List<String> = emptyList(),
    /** Normalized recent routes from the same trace response/cache; never an N+1 fetch. */
    val recentRoutes: List<RequestSummary> = emptyList(),
    /** Full details mapped from the same cached routes as [recentRoutes]. */
    val recentDetails: List<RequestDetail> = emptyList(),
    val totals: TraceTotals = TraceTotals()
)

@Serializable
data class HourlyTrendPoint(
    val hourLabel: String,
    val completedRequests: Long
)

@Serializable
data class OverviewSnapshot(
    val period: Period = Period.TODAY,
    val rangeStart: String? = null,
    val rangeEnd: String? = null,
    val coverage: Coverage = Coverage.COMPLETE,
    val completedRequests: Long = 0L,
    val finalErrors: Long = 0L,
    val ttftSumMs: Long? = null,
    val ttftSampleCount: Long? = null,
    val sameTimeYesterdayCompletedRequests: Long? = null,
    val hourlyTrends: List<HourlyTrendPoint> = emptyList()
)

@Serializable
data class UsageModelItem(
    val providerId: String,
    val modelId: String,
    val totalTokens: Long
)

@Serializable
data class UsageSnapshot(
    val period: Period = Period.TODAY,
    val coverage: Coverage = Coverage.COMPLETE,
    val tokens: TokenUsage = TokenUsage(),
    val models: List<UsageModelItem> = emptyList()
)

@Serializable
data class Capabilities(
    val trace: Boolean = true,
    val history: Boolean = true,
    val requestDetail: Boolean = true,
    val usage: Boolean = true,
    val ledger: Boolean = true,
    val quotas: Boolean = true,
    val groups: Boolean = true,
    val statisticsTimeZone: String? = null,
    val verifiedDeploymentVersion: String? = null
)

@Serializable
data class RequestDetail(
    val summary: RequestSummary,
    val attempts: List<Attempt> = emptyList(),
    val decisionEvidence: List<String> = emptyList(),
    val context: ContextBreakdown? = null,
    val sanitizedError: String? = null,
    val sourceMeta: SourceMeta
)

@Serializable
data class RequestPage(
    val items: List<RequestSummary>,
    val offset: Int,
    val total: Int,
    val hasMore: Boolean,
    val source: String = "gateway",
    val coverage: Coverage = Coverage.COMPLETE,
    val sourceDay: String? = null
)

@Serializable
data class ConnectionConfig(
    val id: String,
    val name: String,
    val mode: DataMode,
    val baseUrl: String? = null,
    val webKey: String? = null,
    val serverReadOnlyPermissionConfirmed: Boolean = false,
    val cfAccessClientId: String? = null,
    val cfAccessClientSecret: String? = null
)

@Serializable
data class DisplayPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val glassTier: GlassTier = GlassTier.CLEAR,
    val reduceMotion: Boolean = false,
    val maskAccountNames: Boolean = true
)
