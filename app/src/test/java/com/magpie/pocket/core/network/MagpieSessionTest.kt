package com.magpie.pocket.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class MagpieSessionTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun stateJson() = """{"version":"Magpie 2.4","timezone":"Asia/Shanghai","read_only":false}"""

    private fun signInResponse() = MockResponse()
        .setResponseCode(302)
        .addHeader("Set-Cookie", "magpie_web_${server.port}=dummy-session; Path=/; HttpOnly")
        .addHeader("Location", "/")

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun connectSignsInBeforeLoadingProtectedStateAndKeepsCookie() = runBlocking {
        val client = MagpieApiClient(
            cfAccessClientId = "dummy-client-id.access",
            cfAccessClientSecret = "dummy-client-secret"
        )
        server.enqueue(signInResponse())
        server.enqueue(json(stateJson()))
        server.enqueue(json("""{"groups":[]}"""))
        server.enqueue(json("""{"epoch":"e1","seq":1,"mine":true}"""))
        server.enqueue(json("""{"calls":0,"series":[]}"""))
        server.enqueue(json("""{"total":0,"rows":[]}"""))
        server.enqueue(json("[]"))

        val result = MagpieSession.connect(client, server.url("/").toString(), "dummy-web-key")

        assertEquals("Magpie 2.4", result.capabilities.verifiedDeploymentVersion)
        assertEquals("Asia/Shanghai", result.capabilities.statisticsTimeZone)
        assertTrue(result.groupsSupported)
        assertTrue(result.traceSupported)

        val signIn = server.takeRequest()
        assertEquals("/_magpie/sign-in", signIn.path)
        assertEquals("POST", signIn.method)
        assertTrue(signIn.body.readUtf8().contains("key=dummy-web-key"))
        assertEquals("dummy-client-id.access", signIn.getHeader("CF-Access-Client-Id"))
        assertEquals("dummy-client-secret", signIn.getHeader("CF-Access-Client-Secret"))

        val state = server.takeRequest()
        assertEquals("/api/state", state.path)
        assertTrue(state.getHeader("Cookie")?.contains("magpie_web_${server.port}=dummy-session") == true)
        assertEquals("dummy-client-id.access", state.getHeader("CF-Access-Client-Id"))
    }

    @Test
    fun blankWebKeyIsAllowedWhenServerDoesNotRequireIt() = runBlocking {
        server.enqueue(json(stateJson()))
        server.enqueue(json("""{"groups":[]}"""))
        server.enqueue(json("""{"epoch":"e1","seq":1,"mine":true}"""))
        server.enqueue(json("""{"calls":0,"series":[]}"""))
        server.enqueue(json("""{"total":0,"rows":[]}"""))
        server.enqueue(json("[]"))

        val result = MagpieSession.connect(MagpieApiClient(), server.url("/").toString(), null)

        assertEquals("Magpie 2.4", result.capabilities.verifiedDeploymentVersion)
        assertEquals("/api/state", server.takeRequest().path)
    }

    @Test
    fun missingWebKeyOnProtectedServerFailsWithAuthRequired() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("no sign-in cookie"))

        try {
            MagpieSession.connect(MagpieApiClient(), server.url("/").toString(), "")
            fail("Expected AuthenticationRequiredException")
        } catch (e: AuthenticationRequiredException) {
            assertTrue(e.message!!.contains("Web Key"))
        }
    }

    @Test
    fun wrongWebKeyDoesNotProbeOrVerify() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("no sign-in cookie"))

        try {
            MagpieSession.connect(MagpieApiClient(), server.url("/").toString(), "wrong-key")
            fail("Expected AuthenticationRequiredException")
        } catch (e: AuthenticationRequiredException) {
            assertTrue(e.message!!.contains("Web Key"))
        }
        // Only the failed sign-in attempt happened; no protected endpoint was probed.
        assertEquals("/_magpie/sign-in", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun stateIsRequiredAndAuthFailurePropagates() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("no sign-in cookie"))

        try {
            MagpieSession.probe(MagpieApiClient(), server.url("/").toString())
            fail("Expected AuthenticationRequiredException")
        } catch (e: AuthenticationRequiredException) {
            // expected
        }
    }

    @Test
    fun invalidStateJsonFailsInsteadOfInventingCapabilities() = runBlocking {
        server.enqueue(json("not-json"))

        try {
            MagpieSession.probe(MagpieApiClient(), server.url("/").toString())
            fail("Expected UnexpectedResponseException")
        } catch (e: UnexpectedResponseException) {
            assertFalse(e.message!!.contains("JsonDecoding"))
        }
    }

    @Test
    fun optionalEndpointsMayDegradeOnlyOn404() = runBlocking {
        server.enqueue(json(stateJson()))
        repeat(5) { server.enqueue(MockResponse().setResponseCode(404)) }

        val result = MagpieSession.probe(MagpieApiClient(), server.url("/").toString())

        assertFalse(result.capabilities.groups)
        assertFalse(result.capabilities.trace)
        assertEquals("Magpie 2.4", result.capabilities.verifiedDeploymentVersion)
    }

    @Test
    fun optionalEndpoint401CannotDegrade() = runBlocking {
        server.enqueue(json(stateJson()))
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            MagpieSession.probe(MagpieApiClient(), server.url("/").toString())
            fail("Expected AuthenticationRequiredException")
        } catch (e: AuthenticationRequiredException) {
            // expected
        }
    }

    @Test
    fun optionalEndpoint403CannotDegrade() = runBlocking {
        server.enqueue(json(stateJson()))
        server.enqueue(MockResponse().setResponseCode(403))

        try {
            MagpieSession.probe(MagpieApiClient(), server.url("/").toString())
            fail("Expected PermissionDeniedException")
        } catch (e: PermissionDeniedException) {
            // expected
        }
    }

    @Test
    fun traceCancellationPropagates() = runBlocking {
        server.enqueue(json("""{"epoch":"e1","seq":0,"mine":true}"""))
        val repo = com.magpie.pocket.core.data.LiveMagpieRepository(
            baseUrl = server.url("/").toString(),
            connectionId = "test",
            apiClient = MagpieApiClient()
        )

        try {
            withTimeout(300) {
                repo.observeTrace().collect { }
            }
            fail("Expected cancellation")
        } catch (e: CancellationException) {
            // expected: cancellation is never swallowed
        }
    }
}
