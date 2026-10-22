package com.magpie.pocket.core.routing

import com.magpie.pocket.core.model.FinalState
import com.magpie.pocket.core.model.RequestDetail
import com.magpie.pocket.core.model.RequestSummary
import com.magpie.pocket.core.model.RouteGroup
import com.magpie.pocket.core.model.TraceSnapshot
import com.magpie.pocket.core.util.RequestTimestamps

enum class RoutePhase {
    IDLE,
    ACTIVE,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNCONFIRMED,
    PINNED,
    UNAVAILABLE
}

data class RouteSettle(
    val routeId: String,
    val state: FinalState,
    val token: Long
)

data class TraceRoutingState(
    val runningIds: Set<String> = emptySet(),
    val settles: List<RouteSettle> = emptyList(),
    val token: Long = 0L
) {
    val settle: RouteSettle? get() = settles.lastOrNull()
}

private val FINISHED_STATES = setOf(
    FinalState.SUCCEEDED,
    FinalState.FAILED,
    FinalState.CANCELLED,
    FinalState.UNCONFIRMED
)

/** Pure reducer used by the ViewModel; the first snapshot never replays a completion. */
fun reduceTraceRouting(
    previous: TraceRoutingState,
    snapshot: TraceSnapshot,
    selectedGroupId: String? = null,
    focusedRouteId: String? = null
): TraceRoutingState {
    val nextRunning = snapshot.runningRouteIds.toSet()
    if (snapshot.restarted) {
        return TraceRoutingState(runningIds = nextRunning)
    }
    if (previous.runningIds.isEmpty()) {
        return previous.copy(runningIds = nextRunning)
    }

    val finished = (previous.runningIds - nextRunning)
        .mapNotNull { id -> snapshot.recentRoutes.find { it.key.routeId == id } }
        .filter { it.finalState in FINISHED_STATES }
    if (finished.isEmpty()) return previous.copy(runningIds = nextRunning)

    val selected = finished.filter { route ->
        route.groupId == selectedGroupId &&
            (focusedRouteId == null || route.key.routeId == focusedRouteId)
    }.maxByOrNull { RequestTimestamps.parseMillis(it.startedAt) ?: Long.MIN_VALUE }
        ?: finished.filter { it.groupId == selectedGroupId }
            .maxByOrNull { RequestTimestamps.parseMillis(it.startedAt) ?: Long.MIN_VALUE }
        ?: finished.maxByOrNull { RequestTimestamps.parseMillis(it.startedAt) ?: Long.MIN_VALUE }
        ?: return previous.copy(runningIds = nextRunning)

    val settleState = when (selected.finalState) {
        FinalState.SUCCEEDED -> FinalState.SUCCEEDED
        FinalState.CANCELLED -> FinalState.CANCELLED
        FinalState.UNCONFIRMED -> FinalState.UNCONFIRMED
        else -> FinalState.FAILED
    }
    val token = previous.token + 1
    val retainedIds = snapshot.retainedRouteIds.ifEmpty {
        snapshot.recentRoutes.map { it.key.routeId }
    }.toSet()
    val priorSettles = previous.settles
        .filter { it.routeId in retainedIds && it.routeId != selected.key.routeId }
        .takeLast(60)
    return TraceRoutingState(
        runningIds = nextRunning,
        settles = (priorSettles + RouteSettle(selected.key.routeId, settleState, token)).takeLast(60),
        token = token
    )
}

/** Latest in-flight request in the selected group wins; otherwise latest finished. */
fun chooseLiveRoute(snapshot: TraceSnapshot?, groupId: String?): RequestSummary? {
    if (snapshot == null || groupId == null) return null
    val matching = snapshot.recentRoutes.filter { it.groupId == groupId }
    val running = snapshot.runningRouteIds.toSet()
    return matching.filter { it.key.routeId in running }.maxByOrNull { sortKey(it) }
        ?: matching.maxByOrNull { sortKey(it) }
}

fun chooseRouteForGroup(
    snapshot: TraceSnapshot?,
    groupId: String?,
    fallback: List<RequestSummary>
): RequestSummary? {
    if (groupId == null) return null
    return chooseLiveRoute(snapshot, groupId)
        ?: fallback.firstOrNull { it.groupId == groupId }
}

/** Keep group ordering when the active provider is already visible; otherwise replace the tail. */
fun visibleProviderIds(
    groupProviderIds: List<String>,
    activeProviderId: String?,
    limit: Int = 2
): List<String> {
    val ordered = groupProviderIds.distinct()
    if (activeProviderId == null) return ordered.take(limit)
    if (activeProviderId in ordered) {
        val visible = ordered.take(limit).toMutableList()
        if (activeProviderId !in visible) {
            if (visible.size >= limit) visible[visible.lastIndex] = activeProviderId
            else visible += activeProviderId
        }
        return visible
    }
    return (ordered.take((limit - 1).coerceAtLeast(0)) + activeProviderId).take(limit)
}

fun activeAttempt(summary: RequestSummary?) = summary?.attempts?.lastOrNull { it.providerId != null || it.accountId != null }

/** A trace summary with no attempts still carries the actual provider/account fields. */
fun attemptEvidence(summary: RequestSummary?): Pair<String?, String?>? {
    val attempt = activeAttempt(summary)
    return (attempt?.providerId ?: summary?.providerId)?.let { provider ->
        provider to (attempt?.accountId ?: summary?.accountId)
    }
}

/** Routing evidence comes from the same trace cache as its summary, never a separate fetch. */
fun TraceSnapshot.detailFor(summary: RequestSummary): RequestDetail? =
    recentDetails.find { detail ->
        val key = detail.summary.key
        key.connectionId == summary.key.connectionId &&
            key.epoch == summary.key.epoch &&
            key.routeId == summary.key.routeId
    }

fun routePhase(
    request: RequestSummary?,
    trace: TraceSnapshot?,
    pinned: Boolean,
    online: Boolean
): RoutePhase {
    if (request == null) return if (trace?.observable == false) RoutePhase.UNAVAILABLE else RoutePhase.IDLE
    if (pinned) return RoutePhase.PINNED
    if (trace == null || !trace.observable || !online) return RoutePhase.IDLE
    if (request.key.routeId in trace.runningRouteIds) return RoutePhase.ACTIVE
    return when (request.finalState) {
        FinalState.SUCCEEDED -> RoutePhase.SUCCEEDED
        FinalState.FAILED -> RoutePhase.FAILED
        FinalState.CANCELLED -> RoutePhase.CANCELLED
        FinalState.UNCONFIRMED -> RoutePhase.UNCONFIRMED
        else -> RoutePhase.IDLE
    }
}

fun providerOrderForGroup(group: RouteGroup?): List<String> = group?.providerIds.orEmpty().distinct()

private fun sortKey(summary: RequestSummary): Long =
    RequestTimestamps.parseMillis(summary.startedAt) ?: Long.MIN_VALUE
