package com.magpie.pocket.core.routing

import com.magpie.pocket.core.model.Attempt
import com.magpie.pocket.core.model.ContextBreakdown
import com.magpie.pocket.core.model.Coverage
import com.magpie.pocket.core.model.DataMode
import com.magpie.pocket.core.model.FinalState
import com.magpie.pocket.core.model.RequestDetail
import com.magpie.pocket.core.model.RequestKey
import com.magpie.pocket.core.model.RequestSummary
import com.magpie.pocket.core.model.SourceMeta
import com.magpie.pocket.core.model.TraceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPresentationTest {
    @Test
    fun liveSelectionPrefersRunningThenLatestFinished() {
        val running = request("2", "g1", FinalState.IN_PROGRESS, "2026-10-22T12:00:02Z")
        val finished = request("1", "g1", FinalState.SUCCEEDED, "2026-10-22T12:00:01Z")
        val trace = snapshot(runningIds = listOf("2"), routes = listOf(finished, running))
        assertEquals("2", chooseLiveRoute(trace, "g1")?.key?.routeId)

        val idle = snapshot(runningIds = emptyList(), routes = listOf(finished, running))
        assertEquals("2", chooseLiveRoute(idle, "g1")?.key?.routeId)
    }

    @Test
    fun latestSelectionUsesActualInstantsAcrossOffsets() {
        val zulu = request("1", "g1", FinalState.SUCCEEDED, "2026-10-22T12:00:00Z")
        val laterOffset = request("2", "g1", FinalState.SUCCEEDED, "2026-10-22T20:00:01+08:00")
        assertEquals("2", chooseLiveRoute(snapshot(emptyList(), listOf(zulu, laterOffset)), "g1")?.key?.routeId)

        // 08:00:02-04:00 is 12:00:02Z, so the later Z value must win.
        val negativeOffset = request("3", "g1", FinalState.SUCCEEDED, "2026-10-22T08:00:02-04:00")
        val zuluLater = request("4", "g1", FinalState.SUCCEEDED, "2026-10-22T12:00:03Z")
        assertEquals(
            "4",
            chooseLiveRoute(snapshot(emptyList(), listOf(negativeOffset, zuluLater)), "g1")?.key?.routeId
        )
    }

    @Test
    fun otherGroupIsNeverShown() {
        val other = request("9", "g2", FinalState.IN_PROGRESS, "2026-10-22T12:00:09Z")
        val trace = snapshot(runningIds = listOf("9"), routes = listOf(other))
        assertNull(chooseLiveRoute(trace, "g1"))
    }

    @Test
    fun retryFocusMovesToActualSecondAccount() {
        val retried = request(
            id = "3",
            groupId = "g1",
            state = FinalState.IN_PROGRESS,
            startedAt = "2026-10-22T12:00:03Z",
            attempts = listOf(
                Attempt(1, providerId = "workbuddy", accountId = "wb-01", httpStatus = 429, errorCode = "rate_limited", handedOn = true),
                Attempt(2, providerId = "workbuddy-ai", accountId = "wba-03")
            )
        )
        val trace = snapshot(runningIds = listOf("3"), routes = listOf(retried))
        val selected = chooseLiveRoute(trace, "g1")!!
        assertEquals("workbuddy-ai", activeAttempt(selected)?.providerId)
        assertEquals("wba-03", activeAttempt(selected)?.accountId)
        assertEquals(RoutePhase.ACTIVE, routePhase(selected, trace, pinned = false, online = true))
    }

    @Test
    fun completionStopsFlowAndProducesOneSettle() {
        val active = request("4", "g1", FinalState.IN_PROGRESS, "2026-10-22T12:00:04Z")
        val done = active.copy(finalState = FinalState.SUCCEEDED)
        val initial = reduceTraceRouting(TraceRoutingState(), snapshot(listOf("4"), listOf(active)))
        assertNull(initial.settle)

        val completed = reduceTraceRouting(initial, snapshot(emptyList(), listOf(done)))
        assertEquals(FinalState.SUCCEEDED, completed.settle?.state)
        assertEquals(RoutePhase.SUCCEEDED, routePhase(done, snapshot(emptyList(), listOf(done)), false, true))

        val again = reduceTraceRouting(completed, snapshot(emptyList(), listOf(done)))
        assertEquals(completed.settle, again.settle)
        assertEquals(completed.token, again.token)
    }

    @Test
    fun simultaneousCompletionsSelectTheViewedGroup() {
        val activeA = request("a", "g1", FinalState.IN_PROGRESS, "2026-10-22T12:00:01Z")
        val activeB = request("b", "g2", FinalState.IN_PROGRESS, "2026-10-22T12:00:02Z")
        val initial = reduceTraceRouting(
            TraceRoutingState(),
            snapshot(listOf("a", "b"), listOf(activeA, activeB))
        )
        val doneA = activeA.copy(finalState = FinalState.SUCCEEDED)
        val doneB = activeB.copy(finalState = FinalState.FAILED)
        val state = reduceTraceRouting(
            initial,
            snapshot(emptyList(), listOf(doneA, doneB)),
            selectedGroupId = "g1"
        )
        assertEquals("a", state.settle?.routeId)
        assertEquals(FinalState.SUCCEEDED, state.settle?.state)
    }

    @Test
    fun disappearanceWithoutTerminalEvidenceDoesNotInventSuccess() {
        val active = request("gone", "g1", FinalState.IN_PROGRESS, "2026-10-22T12:00:01Z")
        val initial = reduceTraceRouting(TraceRoutingState(), snapshot(listOf("gone"), listOf(active)))
        val noEvidence = active.copy(finalState = FinalState.PENDING)
        val state = reduceTraceRouting(initial, snapshot(emptyList(), listOf(noEvidence)))
        assertNull(state.settle)
        assertTrue(state.runningIds.isEmpty())
    }

    @Test
    fun cancelledCompletionKeepsCancelledTruth() {
        val active = request("cancel", "g1", FinalState.IN_PROGRESS, "2026-10-22T12:00:01Z")
        val initial = reduceTraceRouting(TraceRoutingState(), snapshot(listOf("cancel"), listOf(active)))
        val cancelled = active.copy(finalState = FinalState.CANCELLED)
        val state = reduceTraceRouting(initial, snapshot(emptyList(), listOf(cancelled)))
        assertEquals(FinalState.CANCELLED, state.settle?.state)
        assertEquals(RoutePhase.CANCELLED, routePhase(cancelled, snapshot(emptyList(), listOf(cancelled)), false, true))
    }

    @Test
    fun restartedTraceDoesNotReplayOldCompletion() {
        val active = request("r", "g1", FinalState.IN_PROGRESS, "2026-10-22T12:00:01Z")
        val initial = reduceTraceRouting(TraceRoutingState(), snapshot(listOf("r"), listOf(active)))
        val restarted = snapshot(emptyList(), listOf(active.copy(finalState = FinalState.SUCCEEDED)))
            .copy(restarted = true)
        val state = reduceTraceRouting(initial, restarted)
        assertNull(state.settle)
    }

    @Test
    fun traceDetailForUsesSharedCacheIdentity() {
        val summary = request("d", "g1", FinalState.SUCCEEDED, "2026-10-22T12:00:01Z")
        val detail = RequestDetail(
            summary = summary,
            decisionEvidence = listOf("cached evidence"),
            context = ContextBreakdown(summary.key, totalTokens = 42),
            sourceMeta = summary.meta
        )
        val trace = snapshot(emptyList(), listOf(summary)).copy(recentDetails = listOf(detail))
        assertEquals("cached evidence", trace.detailFor(summary)?.decisionEvidence?.single())
    }

    @Test
    fun detailForMatchesConnectionAndEpochButIgnoresSourceDay() {
        val summary = request("d", "g1", FinalState.SUCCEEDED, "2026-10-22T12:00:01Z")
        val sameRoute = summary.copy(key = summary.key.copy(sourceDay = "2026-10-21"))
        val sameRouteDetail = RequestDetail(
            summary = sameRoute,
            decisionEvidence = listOf("same epoch"),
            sourceMeta = sameRoute.meta
        )
        assertEquals(
            "same epoch",
            snapshot(emptyList(), listOf(summary))
                .copy(recentDetails = listOf(sameRouteDetail))
                .detailFor(summary)?.decisionEvidence?.single()
        )

        val otherConnection = sameRoute.copy(key = sameRoute.key.copy(connectionId = "other"))
        val otherEpoch = sameRoute.copy(key = sameRoute.key.copy(epoch = "other"))
        val trace = snapshot(emptyList(), listOf(summary)).copy(
            recentDetails = listOf(
                RequestDetail(otherConnection, decisionEvidence = listOf("wrong connection"), sourceMeta = otherConnection.meta),
                RequestDetail(otherEpoch, decisionEvidence = listOf("wrong epoch"), sourceMeta = otherEpoch.meta)
            )
        )
        assertNull(trace.detailFor(summary))
    }

    @Test
    fun historicalSnapshotDoesNotReplayCompletion() {
        val done = request("5", "g1", FinalState.FAILED, "2026-10-22T12:00:05Z")
        val state = reduceTraceRouting(
            TraceRoutingState(runningIds = emptySet()),
            snapshot(emptyList(), listOf(done))
        )
        assertNull(state.settle)
    }

    @Test
    fun providerOrderingKeepsStableSlotsAndIncludesActiveProvider() {
        assertEquals(listOf("a", "b"), visibleProviderIds(listOf("a", "b", "c"), "a"))
        assertEquals(listOf("a", "c"), visibleProviderIds(listOf("a", "b", "c"), "c"))
        assertEquals(listOf("a", "x"), visibleProviderIds(listOf("a", "b"), "x"))
    }

    private fun request(
        id: String,
        groupId: String,
        state: FinalState,
        startedAt: String,
        attempts: List<Attempt> = emptyList()
    ) = RequestSummary(
        key = RequestKey("c", "e", id),
        startedAt = startedAt,
        groupId = groupId,
        finalState = state,
        attempts = attempts,
        meta = SourceMeta("c", DataMode.LIVE, startedAt, startedAt, coverage = Coverage.RECENT_ONLY)
    )

    private fun snapshot(runningIds: List<String>, routes: List<RequestSummary>) = TraceSnapshot(
        epoch = "e",
        seq = 1,
        retainedRouteIds = routes.map { it.key.routeId },
        runningRouteIds = runningIds,
        recentRoutes = routes
    )
}
