package com.magpie.pocket.app

import com.magpie.pocket.core.data.DemoRepository
import com.magpie.pocket.core.model.ConnectionConfig
import com.magpie.pocket.core.model.ConnectionHealth
import com.magpie.pocket.core.model.DataMode
import com.magpie.pocket.core.model.DisplayPreferences
import com.magpie.pocket.core.model.ThemeMode
import com.magpie.pocket.core.network.MagpieApiClient
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelAuthTest {

    @get:org.junit.Rule
    val testTimeout = org.junit.rules.Timeout.seconds(10)

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private val viewModels = CopyOnWriteArrayList<AppViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runBlocking {
            viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        }
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun failedCredentialSaveKeepsExistingConnectionAndDoesNotContactServer() {
        val initial = ConnectionConfig("demo-main", "主网关", DataMode.DEMO)
        val store = AuthFakeCredentialStore(initial).apply { failSave = true }
        val viewModel = newViewModel(store)
        org.junit.Assert.assertThrows(com.magpie.pocket.core.security.CredentialStorageException::class.java) {
            viewModel.saveConnection(liveConfig("not-saved"))
        }
        assertEquals(initial, viewModel.connectionConfig.value)
        assertEquals(initial, store.getConnection())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun disconnectStillStopsConnectionIfCredentialRemovalFails() {
        val initial = ConnectionConfig("demo-main", "主网关", DataMode.DEMO)
        val store = AuthFakeCredentialStore(initial).apply { failClear = true }
        val viewModel = newViewModel(store)
        viewModel.disconnect()
        assertEquals(DataMode.DEMO, viewModel.connectionConfig.value.mode)
        assertTrue(viewModel.errorMessage.value.orEmpty().contains("清除本应用数据"))
    }

    @Test
    fun restoredLiveConnectionSignsInBeforeProtectedLoads() = runBlocking {
        val paths = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path ?: ""
                return when {
                    request.path == "/_magpie/sign-in" -> MockResponse()
                        .setResponseCode(302)
                        .addHeader("Set-Cookie", "magpie_web_${server.port}=dummy-session; Path=/; HttpOnly")
                        .addHeader("Location", "/")
                    request.path == "/api/state" -> json("""{"version":"Magpie 2.4","timezone":"Asia/Shanghai"}""")
                    request.path == "/api/groups" -> json("""{"groups":[]}""")
                    request.path?.startsWith("/api/usage/requests") == true -> json("""{"total":0,"records":[]}""")
                    request.path?.startsWith("/api/usage") == true -> json("""{"calls":0}""")
                    request.path?.startsWith("/api/gateway/trace") == true -> json("""{"epoch":"e1","seq":1,"mine":true}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val store = AuthFakeCredentialStore(
            ConnectionConfig(
                id = "restored",
                name = "restored",
                mode = DataMode.LIVE,
                baseUrl = server.url("/").toString(),
                webKey = "dummy-web-key"
            )
        )
        val viewModel = newViewModel(store)

        viewModel.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
        viewModel.requestsPage.first { it != null }

        assertTrue(paths.first() == "/_magpie/sign-in")
        val signInIndex = paths.indexOf("/_magpie/sign-in")
        val stateIndex = paths.indexOf("/api/state")
        assertTrue(stateIndex > signInIndex)
        assertEquals(ConnectionHealth.CONNECTED, viewModel.connectionStatus.first().health)
    }

    @Test
    fun all401IsReportedAsAuthRequiredAndNotConnected() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path == "/_magpie/sign-in") {
                    MockResponse().setResponseCode(401).setBody("no sign-in cookie")
                } else {
                    MockResponse().setResponseCode(401).setBody("no sign-in cookie")
                }
            }
        }

        val store = AuthFakeCredentialStore(
            ConnectionConfig(
                id = "bad",
                name = "bad",
                mode = DataMode.LIVE,
                baseUrl = server.url("/").toString(),
                webKey = "wrong-key"
            )
        )
        val viewModel = newViewModel(store)

        val status = viewModel.connectionStatus.first {
            it.health != ConnectionHealth.CONNECTED && it.health != ConnectionHealth.CONNECTING
        }
        assertEquals(ConnectionHealth.AUTH_REQUIRED, status.health)
        assertTrue(status.message!!.contains("Web Key"))
        assertTrue(!viewModel.isOnline.first { !it })
    }

    @Test
    fun restoredCurrentSessionConnectsAndLoadsRowsAfterCookieAuth() = runBlocking {
        val paths = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path ?: ""
                return when {
                    request.path == "/_magpie/sign-in" -> MockResponse()
                        .setResponseCode(302)
                        .addHeader("Set-Cookie", "magpie_web_${server.port}=dummy-session; Path=/; HttpOnly")
                        .addHeader("Location", "/")
                    request.path == "/api/state" -> json("""{"version":"Magpie current","timezone":"Asia/Shanghai"}""")
                    request.path == "/api/groups" -> fixture("groups-current.json")
                    request.path?.startsWith("/api/gateway/trace") == true -> fixture("trace-current.json")
                    request.path?.startsWith("/api/usage/requests") == true -> fixture("ledger-current.json")
                    request.path == "/api/usage/quotas" -> fixture("quotas-current.json")
                    request.path?.startsWith("/api/usage") == true -> fixture("usage-current.json")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val store = AuthFakeCredentialStore(liveConfig("current"))
        val viewModel = newViewModel(store)

        viewModel.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
        val page = viewModel.requestsPage.first { it != null }!!
        assertEquals(1, page.items.size)
        assertEquals("coding", page.items.single().groupId)
        assertEquals(1, viewModel.groups.first().size)
        assertEquals(2, viewModel.quotas.first().size)
        assertEquals("account-a", viewModel.quotas.first().first().accountId)
        assertTrue(viewModel.trace.first { it != null }!!.retainedRouteIds.contains("101"))

        assertTrue(paths.first() == "/_magpie/sign-in")
        val signInIndex = paths.indexOf("/_magpie/sign-in")
        assertTrue(paths.indexOf("/api/state") > signInIndex)
        assertEquals(ConnectionHealth.CONNECTED, viewModel.connectionStatus.first().health)
    }

    @Test
    fun optionalGroupsAndTrace404StillConnectWithoutClaimingThem() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/state" -> json("""{"version":"Magpie current","timezone":"Asia/Shanghai"}""")
                request.path?.startsWith("/api/usage/requests") == true -> json("""{"total":0,"rows":[]}""")
                request.path?.startsWith("/api/usage") == true -> json("""{"calls":0,"series":[]}""")
                request.path == "/api/groups" -> MockResponse().setResponseCode(404)
                request.path?.startsWith("/api/gateway/trace") == true -> MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val viewModel = newViewModel(AuthFakeCredentialStore(liveConfig("optional-404").copy(webKey = null)))

        val status = viewModel.connectionStatus.first { it.health != ConnectionHealth.CONNECTING }
        assertEquals(ConnectionHealth.CONNECTED, status.health)
        kotlinx.coroutines.withTimeout(5000) {
            viewModel.requestsPage.first { it != null }
        }
        val caps = viewModel.capabilities.value!!
        assertFalse(caps.groups)
        assertFalse(caps.trace)
        val trace = viewModel.trace.value!!
        assertFalse(trace.observable)
        assertTrue(viewModel.groups.value.isEmpty())
        // A successful no-trace connection remains connected after the optional 404.
        assertEquals(ConnectionHealth.CONNECTED, viewModel.connectionStatus.value.health)
        assertTrue(viewModel.isOnline.value)
    }

    @Test
    fun connectionStartsConnectingUntilProbeCompletes() = runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/state") gate.await()
                return when {
                    request.path == "/_magpie/sign-in" -> signIn()
                    request.path == "/api/state" -> json("""{"version":"Magpie current","timezone":"Asia/Shanghai"}""")
                    request.path?.startsWith("/api/usage/requests") == true -> json("""{"total":0,"rows":[]}""")
                    request.path?.startsWith("/api/usage") == true -> json("""{"calls":0,"series":[]}""")
                    request.path == "/api/groups" -> json("""{"groups":[]}""")
                    request.path?.startsWith("/api/gateway/trace") == true -> json("""{"seq":1,"mine":true,"routes":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val viewModel = newViewModel(AuthFakeCredentialStore(liveConfig("connecting").copy(webKey = null)))
        assertEquals(ConnectionHealth.CONNECTING, viewModel.connectionStatus.value.health)
        assertFalse(viewModel.isOnline.value)
        gate.countDown()
        kotlinx.coroutines.withTimeout(5000) {
            viewModel.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
        }
        Unit
    }

    @Test
    fun currentConnection401IsStickyAndNoStaleTraceTurnsItGreen() = runBlocking {
        kotlinx.coroutines.withTimeout(10_000) {
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/_magpie/sign-in" -> signIn()
                request.path == "/api/state" -> json("""{"version":"Magpie current","timezone":"Asia/Shanghai"}""")
                request.path == "/api/groups" -> json("""{"groups":[]}""")
                request.path?.startsWith("/api/gateway/trace") == true -> json("""{"seq":1,"mine":true,"routes":[]}""")
                request.path?.startsWith("/api/usage/requests") == true -> json("""{"total":0,"rows":[]}""")
                request.path?.startsWith("/api/usage") == true ->
                    if (expired.get()) MockResponse().setResponseCode(401).setBody("expired")
                    else json("""{"calls":0,"series":[]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val viewModel = newViewModel(AuthFakeCredentialStore(liveConfig("sticky-401")))
        viewModel.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
        viewModel.trace.first { it != null }

        expired.set(true)
        viewModel.setPeriod(com.magpie.pocket.core.model.Period.SEVEN_DAYS)
        val auth = viewModel.connectionStatus.first { it.health == ConnectionHealth.AUTH_REQUIRED }
        assertTrue(auth.message!!.contains("Web Key"))
        assertFalse(viewModel.isOnline.first { !it })
        // Allow in-flight trace emissions to arrive; they must not turn auth green.
        Thread.sleep(200)
        assertEquals(ConnectionHealth.AUTH_REQUIRED, viewModel.connectionStatus.value.health)
        assertFalse(viewModel.isOnline.value)
        }
    }

    @Test
    fun oldConnectionFailureCannotOverwriteNewConnection() = runBlocking {
        val oldServer = MockWebServer()
        oldServer.start()
        val oldGate = java.util.concurrent.CountDownLatch(1)
        val oldRequestStarted = java.util.concurrent.CountDownLatch(1)
        try {
            oldServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/api/state") {
                        oldRequestStarted.countDown()
                        oldGate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    return MockResponse().setResponseCode(401).setBody("old expired")
                }
            }
            val clients = CopyOnWriteArrayList<MagpieApiClient>()
            val store = AuthFakeCredentialStore(
                ConnectionConfig(
                    id = "old",
                    name = "old",
                    mode = DataMode.LIVE,
                    baseUrl = oldServer.url("/").toString()
                )
            )
            val viewModel = newViewModel(store, clients)
            assertTrue(oldRequestStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))

            server.dispatcher = currentDispatcher()
            viewModel.saveConnection(liveConfig("new"))
            kotlinx.coroutines.withTimeout(5000) {
                viewModel.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
            }
            oldGate.countDown()
            Thread.sleep(300)
            assertEquals(ConnectionHealth.CONNECTED, viewModel.connectionStatus.value.health)
            assertEquals("new", viewModel.connectionConfig.value.id)
            assertEquals(2, clients.size)
        } finally {
            oldGate.countDown()
            oldServer.shutdown()
        }
    }

    private fun currentDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path == "/_magpie/sign-in" -> signIn()
            request.path == "/api/state" -> json("""{"version":"Magpie current","timezone":"Asia/Shanghai"}""")
            request.path == "/api/groups" -> json("""{"groups":[]}""")
            request.path?.startsWith("/api/gateway/trace") == true -> json("""{"seq":1,"mine":true,"routes":[]}""")
            request.path?.startsWith("/api/usage/requests") == true -> json("""{"total":0,"rows":[]}""")
            request.path?.startsWith("/api/usage") == true -> json("""{"calls":0,"series":[]}""")
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun liveConfig(id: String) = ConnectionConfig(
        id = id,
        name = id,
        mode = DataMode.LIVE,
        baseUrl = server.url("/").toString(),
        webKey = "dummy-web-key"
    )

    private fun signIn() = MockResponse()
        .setResponseCode(302)
        .addHeader("Set-Cookie", "magpie_web_${server.port}=dummy-session; Path=/; HttpOnly")
        .addHeader("Location", "/")

    private fun fixture(name: String) = json(
        javaClass.classLoader!!.getResource("upstream/$name")!!.readText()
    )

    @Test
    fun switchingConnectionClearsPreviousDataAndUsesFreshClient() = runBlocking {
        val firstServer = MockWebServer()
        firstServer.start()
        val clients = CopyOnWriteArrayList<MagpieApiClient>()
        try {
            firstServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/_magpie/sign-in" -> MockResponse().setResponseCode(303)
                        .addHeader("Set-Cookie", "magpie_web_${firstServer.port}=old-session; Path=/; HttpOnly")
                        .addHeader("Location", "/")
                    request.path == "/api/state" -> json("""{"version":"old","timezone":"Asia/Shanghai"}""")
                    request.path == "/api/groups" -> json("""{"groups":[]}""")
                    request.path == "/api/usage/quotas" -> json("[]")
                    request.path?.startsWith("/api/usage/requests") == true -> json("""{"total":0,"rows":[]}""")
                    request.path?.startsWith("/api/usage") == true -> json("""{"calls":0}""")
                    request.path?.startsWith("/api/gateway/trace") == true -> json("""{"epoch":"old","seq":0,"mine":true,"routes":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val store = AuthFakeCredentialStore(
                ConnectionConfig(
                    id = "old",
                    name = "old",
                    mode = DataMode.LIVE,
                    baseUrl = firstServer.url("/").toString(),
                    webKey = "dummy-web-key"
                )
            )
            val viewModel = newViewModel(store, clients)
            kotlinx.coroutines.withTimeout(5000) {
                viewModel.connectionStatus.first { it.health == ConnectionHealth.CONNECTED }
            }
            assertTrue(clients.single().cookieJar.loadForRequest(firstServer.url("/")).isNotEmpty())

            val demoConfig = ConnectionConfig(id = "demo-main", name = "demo", mode = DataMode.DEMO)
            store.saveConnection(demoConfig)
            viewModel.saveConnection(demoConfig)
            assertTrue(clients.single().cookieJar.loadForRequest(firstServer.url("/")).isEmpty())

            val demoGroups = viewModel.groups.first { it.isNotEmpty() }
            assertTrue(demoGroups.any { it.id == "free-deepseek4.1" })
            val demoTrace = viewModel.trace.first { it != null }!!
            assertTrue(demoTrace.epoch != "old")
            assertEquals(DataMode.DEMO, viewModel.connectionConfig.first().mode)
            // A fresh client was created per live connection rather than mutating the
            // shared app-level instance.
            assertEquals(1, clients.size)
        } finally {
            firstServer.shutdown()
        }
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun newViewModel(
        store: AuthFakeCredentialStore,
        clients: MutableList<MagpieApiClient> = CopyOnWriteArrayList()
    ): AppViewModel {
        val fixture = java.io.File("src/main/assets/normalized-demo.json")
        val json = if (fixture.exists()) fixture.readText() else DemoRepository.FALLBACK_DEMO_JSON
        return AppViewModel(
            credentialStore = store,
            preferencesManager = AuthFakePreferencesManager(),
            demoRepo = DemoRepository(initialJsonString = json),
            apiClient = MagpieApiClient(),
            clientFactory = { config ->
                MagpieApiClient(
                    cfAccessClientId = config.cfAccessClientId,
                    cfAccessClientSecret = config.cfAccessClientSecret
                ).also { clients += it }
            }
        ).also { viewModels += it; it.onActivityResumed() }
    }
}

private class AuthFakeCredentialStore(initial: ConnectionConfig) : ConnectionStateSource {
    private var config = initial
    var failSave = false
    var failClear = false

    override fun getConnection(): ConnectionConfig = config

    override fun saveConnection(config: ConnectionConfig) {
        if (failSave) throw com.magpie.pocket.core.security.CredentialStorageException()
        this.config = config
    }

    override fun clearCredentials() {
        if (failClear) throw com.magpie.pocket.core.security.CredentialStorageException()
        config = ConnectionConfig(id = "demo-main", name = "主网关", mode = DataMode.DEMO)
    }
}

private class AuthFakePreferencesManager : DisplayPreferencesSource {
    private val state = MutableStateFlow(DisplayPreferences())
    override val displayPreferencesFlow = state

    override suspend fun updateThemeMode(mode: ThemeMode) {
        state.value = state.value.copy(themeMode = mode)
    }

    override suspend fun updateGlassTier(tier: com.magpie.pocket.core.model.GlassTier) {
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
