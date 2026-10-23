package com.magpie.pocket.app

import androidx.lifecycle.viewModelScope
import com.magpie.pocket.core.data.DemoRepository
import com.magpie.pocket.core.model.*
import com.magpie.pocket.core.network.MagpieApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class BackgroundConnectionBehaviorTest {
    private val servers = mutableListOf<MockWebServer>()
    private val models = mutableListOf<AppViewModel>()
    private val main = newSingleThreadContext("connection-contract-main")

    @Before fun before() { Dispatchers.setMain(main) }
    @After fun after() {
        runBlocking { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        servers.forEach { it.shutdown() }
        Dispatchers.resetMain()
        main.close()
    }

    @Test fun initialOfflineSessionRetriesWithoutManualLogin(): Unit = runBlocking(main) {
        withTimeout(6_000) {
            val endpoint = Endpoint().apply { status = 503 }
            val server = serve(endpoint)
            val vm = model(server)
            vm.onActivityResumed()
            await { endpoint.states.get() >= 2 }
            endpoint.status = 200
            vm.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
            vm.trace.first { !it?.recentRoutes.isNullOrEmpty() }
            assertEquals("current", vm.connectionConfig.value.id)
        }
    }

    @Test fun authFailureRemainsStickyAcrossHeartbeatAndLifecycleSignals(): Unit = runBlocking(main) {
        withTimeout(6_000) {
            val endpoint = Endpoint().apply { status = 401 }
            val vm = model(serve(endpoint))
            vm.onActivityResumed()
            vm.connectionStatus.first { it.health == ConnectionHealth.AUTH_REQUIRED }
            val calls = endpoint.calls.get()
            repeat(4) { vm.onNetworkAvailable(); vm.onActivityResumed() }
            delay(180)
            assertEquals(calls, endpoint.calls.get())
            assertFalse(vm.isOnline.value)
        }
    }

    @Test fun backgroundOptOutStopsTrafficThenForegroundRestartsIt(): Unit = runBlocking(main) {
        withTimeout(6_000) {
            val endpoint = Endpoint()
            val prefs = MemoryPreferences()
            val vm = model(serve(endpoint), prefs = prefs)
            vm.onActivityResumed()
            vm.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
            vm.onKeepaliveServiceActive(true)
            vm.onActivityPaused(); vm.onActivityStopped()
            val before = endpoint.states.get()
            await { endpoint.states.get() > before }
            prefs.state.value = prefs.state.value.copy(keepLiveConnectionInBackground = false)
            vm.onKeepaliveServiceActive(false)
            delay(80)
            val paused = endpoint.calls.get()
            delay(180)
            assertEquals(paused, endpoint.calls.get())
            vm.onActivityResumed()
            await { endpoint.calls.get() > paused }
            vm.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
        }
    }

    @Test fun disconnectPreventsScheduledRecoveryFromReopeningOldGateway(): Unit = runBlocking(main) {
        withTimeout(6_000) {
            val endpoint = Endpoint().apply { status = 503 }
            val vm = model(serve(endpoint))
            vm.onActivityResumed()
            await { endpoint.states.get() >= 2 }
            vm.disconnect()
            delay(80)
            val stopped = endpoint.calls.get()
            endpoint.status = 200
            delay(180)
            assertEquals(stopped, endpoint.calls.get())
            assertEquals(DataMode.DEMO, vm.connectionConfig.value.mode)
            assertEquals(ConnectionHealth.CONNECTED, vm.connectionStatus.value.health)
        }
    }

    @Test fun heartbeatRunsWhenServerDoesNotSupportTrace(): Unit = runBlocking(main) {
        withTimeout(6_000) {
            val endpoint = Endpoint().apply { hasTrace = false }
            val vm = model(serve(endpoint))
            vm.onActivityResumed()
            vm.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
            vm.onKeepaliveServiceActive(true)
            vm.onActivityPaused(); vm.onActivityStopped()
            val before = endpoint.states.get()
            await { endpoint.states.get() >= before + 2 }
            assertEquals(false, vm.capabilities.value?.trace)
            assertEquals(ConnectionHealth.CONNECTED, vm.connectionStatus.value.health)
        }
    }

    @Test fun repeatedLifecycleCallbacksDoNotOverlapTraceCollectors(): Unit = runBlocking(main) {
        withTimeout(6_000) {
            val endpoint = Endpoint()
            val vm = model(serve(endpoint))
            vm.onActivityResumed()
            vm.trace.first { !it?.recentRoutes.isNullOrEmpty() }
            repeat(8) { vm.onActivityResumed(); vm.onKeepaliveServiceActive(true) }
            delay(220)
            assertEquals(1, endpoint.maxActiveTrace.get())
        }
    }

    @Test fun explicitRetryCanRecoverAfterAnAuthenticationFailureIsCorrected(): Unit = runBlocking(main) {
        withTimeout(6_000) {
            val endpoint = Endpoint().apply { status = 401 }
            val vm = model(serve(endpoint))
            vm.onActivityResumed()
            vm.connectionStatus.first { it.health == ConnectionHealth.AUTH_REQUIRED }
            endpoint.status = 200
            vm.refreshAll()
            vm.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
            assertTrue(vm.isOnline.value)
        }
    }

    @Test fun legacyRestartCannotRebindPinnedHistoryToReusedId(): Unit = runBlocking(main) {
        withTimeout(6_000) {
            val vm = model(serve(Endpoint()), mode = DataMode.DEMO)
            val page = vm.requestsPage.first { it != null }!!
            val old = page.items.first().copy(
                key = RequestKey("current", "", "reused"), groupId = "old-group",
                servedModel = "historical-model", finalState = FinalState.SUCCEEDED
            )
            vm.applyTraceSnapshot(TraceSnapshot(epoch = "", seq = 10, recentRoutes = listOf(old)))
            vm.selectRoutingRequest(old)
            val replacement = old.copy(groupId = "new-group", servedModel = "different-model")
            vm.applyTraceSnapshot(TraceSnapshot(epoch = "", seq = 1, restarted = true,
                recentRoutes = listOf(replacement)))
            assertNotEquals("different-model", vm.routingRequest.value?.servedModel)
            vm.applyTraceSnapshot(TraceSnapshot(epoch = "", seq = 2,
                recentRoutes = listOf(replacement)))
            assertNotEquals("Later same-ID snapshots must not rebind the historical pin", "different-model", vm.routingRequest.value?.servedModel)
            assertEquals("old-group", vm.selectedGroupId.value)
            assertFalse(vm.isFollowingLive.value)
        }
    }

    @Test fun optedInBackgroundTransitionDoesNotStopActiveService(): Unit = runBlocking(main) {
        withTimeout(6_000) {
            var stops = 0
            val controller = object : KeepaliveController {
                override fun start() = Unit
                override fun stop() { stops += 1 }
            }
            val vm = model(serve(Endpoint()), keepalive = controller)
            vm.onActivityStarted(); vm.onActivityResumed()
            vm.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
            vm.onKeepaliveServiceActive(true)
            val before = stops
            vm.onActivityPaused(); vm.onActivityStopped()
            delay(150)
            assertEquals("Enabled background service must survive Activity.onStop", before, stops)
        }
    }

    @Test fun keepaliveStartWaitsForPersistedPreferencesWhileForegroundStaysUsable(): Unit =
        runBlocking(main) {
            withTimeout(6_000) {
                val endpoint = Endpoint()
                val prefs = DelayedPreferences()
                val controller = RecordingKeepaliveController()
                val vm = model(serve(endpoint), prefs = prefs, keepalive = controller)
                prefs.collecting.await()

                vm.onActivityStarted()
                vm.onActivityResumed()
                await { endpoint.states.get() >= 1 }
                assertEquals("No service before the first persisted preference", 0, controller.starts)

                prefs.emit(DisplayPreferences(keepLiveConnectionInBackground = false))
                vm.displayPreferencesReady.first { it }
                assertEquals(0, controller.starts)
                vm.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }

                prefs.emit(DisplayPreferences(keepLiveConnectionInBackground = true))
                await { controller.starts == 1 }
                vm.onKeepaliveServiceActive(true)
                repeat(4) {
                    vm.onActivityStarted()
                    vm.onActivityResumed()
                    prefs.emit(DisplayPreferences(keepLiveConnectionInBackground = true))
                }
                delay(100)
                assertEquals("Repeated lifecycle/preference events stay owned by the controller",
                    1, controller.starts)
            }
        }

    @Test fun preloadedDisabledColdStartNeverStartsServiceOrBackgroundTraffic(): Unit =
        runBlocking(main) {
            withTimeout(6_000) {
                val endpoint = Endpoint()
                val prefs = MemoryPreferences(
                    DisplayPreferences(keepLiveConnectionInBackground = false)
                )
                val controller = RecordingKeepaliveController()
                val vm = model(serve(endpoint), prefs = prefs, keepalive = controller)
                vm.displayPreferencesReady.first { it }

                vm.onActivityStarted()
                assertEquals(0, controller.starts)
                vm.onActivityResumed()
                vm.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
                vm.onActivityPaused()
                vm.onActivityStopped()

                delay(150)
                val stoppedAt = endpoint.calls.get()
                delay(250)
                assertEquals("Disabled keepalive must not run background LIVE traffic",
                    stoppedAt, endpoint.calls.get())
                assertEquals(0, controller.starts)
            }
        }

    @Test fun stickyServiceWaitsForPreferencesThenRecoversOptedInRuntime(): Unit =
        runBlocking(main) {
            withTimeout(6_000) {
                val endpoint = Endpoint()
                val prefs = DelayedPreferences()
                val controller = RecordingKeepaliveController()
                val vm = model(serve(endpoint), prefs = prefs, keepalive = controller)
                prefs.collecting.await()

                vm.onKeepaliveServiceActive(true)
                delay(120)
                assertEquals("Sticky service must not use the default preference",
                    0, endpoint.states.get())

                prefs.emit(DisplayPreferences(keepLiveConnectionInBackground = true))
                vm.displayPreferencesReady.first { it }
                vm.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
                assertTrue(endpoint.states.get() >= 1)
            }
        }

    @Test fun stickyServiceStopsWithoutTrafficWhenPersistedPreferenceIsDisabled(): Unit =
        runBlocking(main) {
            withTimeout(6_000) {
                val endpoint = Endpoint()
                val prefs = DelayedPreferences()
                val controller = RecordingKeepaliveController()
                val vm = model(serve(endpoint), prefs = prefs, keepalive = controller)
                prefs.collecting.await()

                vm.onKeepaliveServiceActive(true)
                prefs.emit(DisplayPreferences(keepLiveConnectionInBackground = false))
                vm.displayPreferencesReady.first { it }
                await { controller.stops > 0 }
                delay(150)
                assertEquals(0, endpoint.states.get())
            }
        }

    @Test fun initialOutageKeepsRetryingAfterBackoffReachesItsCap(): Unit = runBlocking(main) {
        withTimeout(6_000) {
            val endpoint = Endpoint().apply { status = 503 }
            val vm = model(serve(endpoint))
            vm.onActivityResumed()
            delay(350)
            val failedCalls = endpoint.states.get()
            endpoint.status = 200
            vm.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
            assertTrue("Recovery must continue past the initial delay list", endpoint.states.get() > failedCalls)
        }
    }

    @Test fun pendingVisibleServiceStartSurvivesFastBackgroundTransition(): Unit = runBlocking(main) {
        withTimeout(6_000) {
            var stops = 0
            val controller = object : KeepaliveController {
                override fun start() = Unit
                override fun stop() { stops += 1 }
            }
            val endpoint = Endpoint()
            val vm = model(serve(endpoint), keepalive = controller)
            vm.onActivityStarted(); vm.onActivityResumed()
            vm.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
            val before = stops
            vm.onActivityPaused(); vm.onActivityStopped()
            assertEquals("Do not cancel a service that was legitimately requested while visible", before, stops)
            val calls = endpoint.states.get()
            vm.onKeepaliveServiceActive(true)
            await { endpoint.states.get() > calls }
            assertEquals(ConnectionHealth.CONNECTED, vm.connectionStatus.value.health)
        }
    }

    private suspend fun await(condition: () -> Boolean) {
        while (!condition()) delay(10)
    }

    private fun serve(endpoint: Endpoint) = MockWebServer().apply {
        dispatcher = endpoint
        start()
        servers += this
    }

    private fun model(server: MockWebServer, prefs: DisplayPreferencesSource = MemoryPreferences(),
                      mode: DataMode = DataMode.LIVE,
                      keepalive: KeepaliveController = NoOpKeepaliveController): AppViewModel {
        val connection = ConnectionConfig("current", "Fixture", mode,
            server.url("/").toString(), "fixture-web-key")
        val store = object : ConnectionStateSource {
            var value = connection
            override fun getConnection() = value
            override fun saveConnection(config: ConnectionConfig) { value = config }
            override fun clearCredentials() { value = ConnectionConfig("demo-main", "Demo", DataMode.DEMO) }
        }
        val fixture = java.io.File("src/main/assets/normalized-demo.json").readText()
        return AppViewModel(store, prefs, DemoRepository(initialJsonString = fixture), MagpieApiClient(),
            keepaliveController = keepalive, heartbeatIntervalMs = 55, retryDelaysMs = listOf(15L, 30L, 50L)).also { models += it }
    }

    private class MemoryPreferences(
        initial: DisplayPreferences = DisplayPreferences()
    ) : DisplayPreferencesSource {
        val state = MutableStateFlow(initial)
        override val displayPreferencesFlow = state
        override suspend fun updateThemeMode(mode: ThemeMode) { state.value = state.value.copy(themeMode = mode) }
        override suspend fun updateGlassTier(tier: GlassTier) { state.value = state.value.copy(glassTier = tier) }
        override suspend fun updateReduceMotion(enabled: Boolean) { state.value = state.value.copy(reduceMotion = enabled) }
        override suspend fun updateMaskAccounts(enabled: Boolean) { state.value = state.value.copy(maskAccountNames = enabled) }
        override suspend fun updateKeepLiveConnectionInBackground(enabled: Boolean) { state.value = state.value.copy(keepLiveConnectionInBackground = enabled) }
    }

    private class DelayedPreferences : DisplayPreferencesSource {
        private val state = MutableStateFlow<DisplayPreferences?>(null)
        val collecting = CompletableDeferred<Unit>()
        override val displayPreferencesFlow = state.filterNotNull()
            .onStart { collecting.complete(Unit) }

        fun emit(preferences: DisplayPreferences) {
            state.value = preferences
        }

        override suspend fun updateThemeMode(mode: ThemeMode) {
            state.value = (state.value ?: DisplayPreferences()).copy(themeMode = mode)
        }
        override suspend fun updateGlassTier(tier: GlassTier) {
            state.value = (state.value ?: DisplayPreferences()).copy(glassTier = tier)
        }
        override suspend fun updateReduceMotion(enabled: Boolean) {
            state.value = (state.value ?: DisplayPreferences()).copy(reduceMotion = enabled)
        }
        override suspend fun updateMaskAccounts(enabled: Boolean) {
            state.value = (state.value ?: DisplayPreferences()).copy(maskAccountNames = enabled)
        }
        override suspend fun updateKeepLiveConnectionInBackground(enabled: Boolean) {
            state.value = (state.value ?: DisplayPreferences())
                .copy(keepLiveConnectionInBackground = enabled)
        }
    }

    private class RecordingKeepaliveController : KeepaliveController {
        var starts = 0
        var stops = 0
        private var requested = false
        private var running = false

        override fun start() {
            if (requested || running) return
            requested = true
            starts += 1
        }

        override fun stop() {
            if (!requested && !running) return
            requested = false
            running = false
            stops += 1
        }

        override fun onServiceActive() {
            requested = false
            running = true
        }

        override fun onServiceInactive() {
            requested = false
            running = false
        }
    }

    private class Endpoint : Dispatcher() {
        @Volatile var status = 200
        @Volatile var hasTrace = true
        val calls = AtomicInteger()
        val states = AtomicInteger()
        private val activeTrace = AtomicInteger()
        val maxActiveTrace = AtomicInteger()
        private val seq = AtomicInteger()
        override fun dispatch(request: RecordedRequest): MockResponse {
            calls.incrementAndGet()
            val path = request.requestUrl!!.encodedPath
            if (path == "/_magpie/sign-in") return MockResponse().setResponseCode(302)
                .addHeader("Location", "/").addHeader("Set-Cookie", "magpie_web_fixture=fixture; Path=/; HttpOnly")
            if (path == "/api/state") {
                states.incrementAndGet()
                if (status != 200) return MockResponse().setResponseCode(status).setBody("fixture failure")
                return json("""{"version":"Fixture","read_only":true}""")
            }
            if (path == "/api/gateway/trace") {
                if (!hasTrace) return MockResponse().setResponseCode(404)
                val active = activeTrace.incrementAndGet()
                maxActiveTrace.updateAndGet { maxOf(it, active) }
                try {
                    Thread.sleep(15)
                    return json("""{"epoch":"fixture","mine":true,"seq":${seq.incrementAndGet()},"routes":[{"id":1,"time":"2026-10-23T00:00:00Z","agent":"codex","model":"fixture-model","done":false}],"ids":[1]}""")
                } finally { activeTrace.decrementAndGet() }
            }
            return when (path) {
                "/api/groups" -> json("""{"groups":[]}""")
                "/api/usage" -> json("""{"calls":0}""")
                "/api/usage/requests" -> json("""{"records":[],"total":0}""")
                "/api/usage/quotas" -> json("[]")
                else -> MockResponse().setResponseCode(404)
            }
        }
        private fun json(body: String) = MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/json").setBody(body)
    }
}
