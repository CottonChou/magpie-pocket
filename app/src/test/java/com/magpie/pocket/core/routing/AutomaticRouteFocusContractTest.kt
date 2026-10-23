package com.magpie.pocket.core.routing

import com.magpie.pocket.core.model.DataMode
import com.magpie.pocket.core.model.FinalState
import com.magpie.pocket.core.model.RequestKey
import com.magpie.pocket.core.model.RequestSummary
import com.magpie.pocket.core.model.SourceMeta
import com.magpie.pocket.core.model.TraceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract for the automatic route focus the user actually experiences: the app follows the
 * newest real activity anywhere, never the first or manually remembered group, and it never
 * resurrects history that belongs to a superseded trace epoch.
 *
 * Written against the required behavior, so it stays valid while the implementation is tuned.
 */
class AutomaticRouteFocusContractTest {

    // ---- fixtures -------------------------------------------------------

    private fun route(
        id: String,
        startedAt: String,
        state: FinalState = FinalState.SUCCEEDED,
        group: String? = "g1",
        connection: String = "conn-a",
        epoch: String = "e1"
    ): RequestSummary = RequestSummary(
        key = RequestKey(connectionId = connection, epoch = epoch, routeId = id),
        startedAt = startedAt,
        groupId = group,
        finalState = state,
        meta = SourceMeta(
            connectionId = connection,
            mode = DataMode.LIVE,
            observedAt = startedAt,
            fetchedAt = startedAt
        )
    )

    private fun trace(
        running: List<String> = emptyList(),
        routes: List<RequestSummary> = emptyList(),
        epoch: String = "e1",
        restarted: Boolean = false
    ): TraceSnapshot = TraceSnapshot(
        epoch = epoch,
        seq = 1L,
        restarted = restarted,
        runningRouteIds = running,
        recentRoutes = routes
    )

    private fun assertRoute(expected: RequestSummary, actual: RequestSummary?, context: String) {
        assertNotNull("$context -> expected an automatic route", actual)
        assertEquals("$context -> connection", expected.key.connectionId, actual!!.key.connectionId)
        assertEquals("$context -> epoch", expected.key.epoch, actual.key.epoch)
        assertEquals("$context -> route", expected.key.routeId, actual.key.routeId)
    }

    // ---- running evidence outranks everything else -----------------------

    @Test
    fun newestRunningRequestWinsOverANewerCompletedRequest() {
        val running = route("r-run", "2026-10-22T12:00:00Z", FinalState.IN_PROGRESS, group = "g1")
        val newerCompleted = route("r-done", "2026-10-22T12:00:10Z", FinalState.SUCCEEDED, group = "g2")
        val olderCompleted = route("r-old", "2026-10-22T11:59:00Z", FinalState.FAILED, group = "g1")

        val snapshot = trace(
            running = listOf(running.key.routeId),
            routes = listOf(newerCompleted, olderCompleted, running)
        )

        assertRoute(running, chooseAutoRoute(snapshot, emptyList()), "running beats newer completed")
        assertRoute(running, chooseLiveRoute(snapshot), "live selector agrees")
    }

    @Test
    fun newestRunningRequestWinsAcrossGroupsRegardlessOfWhichGroupStartedFirst() {
        val firstGroupRunning = route("r-g1", "2026-10-22T12:00:01Z", FinalState.IN_PROGRESS, group = "g1")
        val laterGroupRunning = route("r-g2", "2026-10-22T12:00:05Z", FinalState.IN_PROGRESS, group = "g2")

        val snapshot = trace(
            running = listOf(firstGroupRunning.key.routeId, laterGroupRunning.key.routeId),
            routes = listOf(firstGroupRunning, laterGroupRunning)
        )

        assertRoute(
            laterGroupRunning,
            chooseAutoRoute(snapshot, emptyList()),
            "newest in-flight request across every group wins"
        )
    }

    @Test
    fun newestRunningRequestWinsOverFresherLedgerHistory() {
        val running = route("r-run", "2026-10-22T12:00:00Z", FinalState.IN_PROGRESS, group = "g1")
        val snapshot = trace(running = listOf(running.key.routeId), routes = listOf(running))
        val ledger = listOf(
            route("r-ledger", "2026-10-22T13:00:00Z", FinalState.SUCCEEDED, group = "g9")
        )

        assertRoute(running, chooseAutoRoute(snapshot, ledger), "trace activity beats ledger rows")
    }

    // ---- real instants, not string order --------------------------------

    @Test
    fun offsetsAreSortedByActualInstantForRunningRequests() {
        val zulu = route("r-z", "2026-10-22T12:00:04Z", FinalState.IN_PROGRESS, group = "g1")
        // 20:00:05+08:00 is 12:00:05Z, one second after the Zulu route.
        val east = route("r-east", "2026-10-22T20:00:05+08:00", FinalState.IN_PROGRESS, group = "g2")

        val snapshot = trace(running = listOf(zulu.key.routeId, east.key.routeId), routes = listOf(zulu, east))

        assertRoute(east, chooseAutoRoute(snapshot, emptyList()), "later instant behind a +offset wins")
    }

    @Test
    fun offsetsAreSortedByActualInstantForCompletedRequests() {
        val west = route("r-west", "2026-10-22T09:00:01-04:00", FinalState.SUCCEEDED, group = "g1")
        val zulu = route("r-z", "2026-10-22T12:30:00Z", FinalState.SUCCEEDED, group = "g2")

        // Text order would prefer "12:30:00Z"; the actual instants say 13:00:01Z wins.
        val snapshot = trace(routes = listOf(west, zulu))
        assertRoute(west, chooseAutoRoute(snapshot, emptyList()), "negative offset resolves to a later instant")

        val flipped = trace(routes = listOf(zulu, west))
        assertRoute(west, chooseAutoRoute(flipped, emptyList()), "list order must not decide")
    }

    // ---- no running activity: newest observed completion ----------------

    @Test
    fun withNoRunningRequestTheLatestObservedCompletedRequestIsChosen() {
        val older = route("r-1", "2026-10-22T11:00:00Z", FinalState.SUCCEEDED, group = "g1")
        val newest = route("r-2", "2026-10-22T12:00:00Z", FinalState.FAILED, group = "g2")
        val cancelled = route("r-3", "2026-10-22T11:30:00Z", FinalState.CANCELLED, group = "g3")

        val snapshot = trace(routes = listOf(older, cancelled, newest))
        assertRoute(newest, chooseAutoRoute(snapshot, emptyList()), "newest finished route wins")
        assertRoute(newest, chooseLiveRoute(snapshot), "live selector agrees")
    }

    @Test
    fun ledgerHistoryIsUsedWhenTheLiveTraceHasNotObservedAnythingYet() {
        val currentEpochRow = route("r-l2", "2026-10-22T12:00:00Z", FinalState.SUCCEEDED, epoch = "e1")
        val olderRow = route("r-l1", "2026-10-22T10:00:00Z", FinalState.SUCCEEDED, epoch = "e1")
        val snapshot = trace(routes = emptyList(), epoch = "e1")

        assertRoute(
            currentEpochRow,
            chooseAutoRoute(snapshot, listOf(olderRow, currentEpochRow)),
            "same-epoch ledger is legitimate historical evidence"
        )

        // With no trace at all the ledger is the only evidence; a stale unfinished row is not.
        assertRoute(
            currentEpochRow,
            chooseAutoRoute(
                null,
                listOf(
                    olderRow,
                    currentEpochRow,
                    route("r-stale", "2026-10-22T14:00:00Z", FinalState.IN_PROGRESS)
                )
            ),
            "unconfirmed ledger rows are not presented as observed completions"
        )
    }

    // ---- group identity --------------------------------------------------

    @Test
    fun directRequestsWithoutAGroupRemainEligible() {
        val direct = route("r-direct", "2026-10-22T12:00:00Z", FinalState.IN_PROGRESS, group = null)
        val grouped = route("r-group", "2026-10-22T12:00:05Z", FinalState.SUCCEEDED, group = "g1")

        val snapshot = trace(running = listOf(direct.key.routeId), routes = listOf(direct, grouped))
        assertRoute(direct, chooseAutoRoute(snapshot, emptyList()), "a direct request is real activity")
        assertNull(chooseAutoRoute(snapshot, emptyList())!!.groupId)

        val idleSnapshot = trace(routes = listOf(direct, grouped))
        assertRoute(grouped, chooseAutoRoute(idleSnapshot, emptyList()), "grouped completion still wins when idle")

        val directOnly = trace(routes = listOf(route("r-direct-done", "2026-10-22T12:00:00Z", group = null)))
        assertEquals(
            "r-direct-done",
            chooseAutoRoute(directOnly, emptyList())?.key?.routeId
        )
    }

    @Test
    fun missingGroupMetadataDoesNotDiscardTheActualGroupId() {
        // g-unlisted never appears as route-group metadata; the request still carries it.
        val unlisted = route("r-unlisted", "2026-10-22T12:00:09Z", FinalState.IN_PROGRESS, group = "g-unlisted")
        val known = route("r-known", "2026-10-22T12:00:01Z", FinalState.IN_PROGRESS, group = "g1")

        val snapshot = trace(running = listOf(unlisted.key.routeId, known.key.routeId), routes = listOf(known, unlisted))
        val chosen = chooseAutoRoute(snapshot, emptyList())

        assertRoute(unlisted, chosen, "unknown group id must not disqualify the request")
        assertEquals("g-unlisted", chosen!!.groupId)
    }

    // ---- identity and emptiness -----------------------------------------

    @Test
    fun sameRouteIdInDifferentEpochsOrConnectionsIsNotConfused() {
        val previousEpoch = route("r-1", "2026-10-22T11:00:00Z", FinalState.IN_PROGRESS, epoch = "e1")
        val currentEpoch = route("r-1", "2026-10-22T12:00:00Z", FinalState.IN_PROGRESS, epoch = "e2")
        val otherConnection = route("r-1", "2026-10-22T12:30:00Z", FinalState.IN_PROGRESS, connection = "conn-b", epoch = "e2")

        val snapshot = trace(
            running = listOf("r-1"),
            routes = listOf(previousEpoch, currentEpoch),
            epoch = "e2"
        )
        assertRoute(currentEpoch, chooseAutoRoute(snapshot, emptyList()), "newer epoch instance wins")

        val acrossConnections = trace(
            running = listOf("r-1"),
            routes = listOf(currentEpoch, otherConnection),
            epoch = "e2"
        )
        assertRoute(otherConnection, chooseAutoRoute(acrossConnections, emptyList()), "newest instant wins across connections")
        assertEquals("conn-b", chooseAutoRoute(acrossConnections, emptyList())!!.key.connectionId)
    }

    @Test
    fun trulyEmptyEvidenceProducesNoAutomaticRoute() {
        assertNull(chooseAutoRoute(null, emptyList()))
        assertNull(chooseAutoRoute(trace(), emptyList()))
        assertNull(chooseAutoRoute(trace(routes = emptyList(), running = emptyList()), emptyList()))
        assertNull(chooseLiveRoute(null))
        assertNull(chooseLiveRoute(trace()))
    }

    @Test
    fun restartedEmptyTraceDoesNotRevivePreviousEpochLedgerFallback() {
        val previousEpoch = route(
            "r-stale",
            "2026-10-22T13:00:00Z",
            FinalState.SUCCEEDED,
            epoch = "e1",
            group = "g1"
        )
        val restartedSnapshot = trace(epoch = "e2", restarted = true, routes = emptyList(), running = emptyList())

        assertNull(
            "a fresh epoch must not auto-focus a route from the superseded epoch",
            chooseAutoRoute(restartedSnapshot, listOf(previousEpoch))
        )
        assertNull(chooseLiveRoute(restartedSnapshot))

        // Once the new epoch has observed its own traffic, only that traffic is evidence.
        val currentEpochRow = route("r-fresh", "2026-10-22T13:30:00Z", FinalState.SUCCEEDED, epoch = "e2")
        assertRoute(
            currentEpochRow,
            chooseAutoRoute(restartedSnapshot, listOf(previousEpoch, currentEpochRow)),
            "current-epoch evidence is never displaced by stale history"
        )
    }
}
