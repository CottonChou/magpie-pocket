package com.magpie.pocket.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MagpieApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: MagpieApiClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = MagpieApiClient()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun testNormalizeBaseUrl() {
        assertEquals("https://magpie.example.com", MagpieApiClient.normalizeBaseUrl("https://magpie.example.com/"))
        assertEquals("https://magpie.example.com", MagpieApiClient.normalizeBaseUrl("magpie.example.com"))
        assertEquals("https://magpie.example.com:8443", MagpieApiClient.normalizeBaseUrl("https://magpie.example.com:8443/?k=secret-key"))
    }

    @Test
    fun testExtractKeyFromUrl() {
        val extracted = MagpieApiClient.extractKeyFromUrl("https://magpie.example.com/?k=my-secret-key-123")
        assertEquals("my-secret-key-123", extracted)
    }

    @Test
    fun testSuccessfulGetState() = runBlocking {
        val json = """{"version":"Magpie 2.4","timezone":"Asia/Shanghai","read_only":false}"""
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json))

        val baseUrl = server.url("/").toString()
        val state = client.getState(baseUrl)

        assertEquals("Magpie 2.4", state.version)
        assertEquals("Asia/Shanghai", state.timezone)
        assertEquals(false, state.readOnly)
    }

    @Test(expected = AuthenticationRequiredException::class)
    fun testHtmlResponseThrowsAuthenticationRequired(): Unit = runBlocking {
        val html = "<!DOCTYPE html><html><body><h1>Please Login via Cloudflare Access</h1></body></html>"
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html; charset=utf-8").setBody(html))

        val baseUrl = server.url("/").toString()
        client.getState(baseUrl)
    }

    @Test(expected = AuthenticationRequiredException::class)
    fun test401ThrowsAuthenticationRequired(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        val baseUrl = server.url("/").toString()
        client.getState(baseUrl)
    }

    @Test(expected = PermissionDeniedException::class)
    fun test403ThrowsPermissionDenied(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""))
        val baseUrl = server.url("/").toString()
        client.getState(baseUrl)
    }

    @Test(expected = ResourceNotFoundException::class)
    fun test404ThrowsResourceNotFound(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))
        val baseUrl = server.url("/").toString()
        client.getState(baseUrl)
    }

    @Test(expected = UnexpectedResponseException::class)
    fun testInvalidJsonDoesNotLeakDecoderMessage(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("not json"))
        client.getState(server.url("/").toString())
    }

    @Test
    fun testSignInRetainsMagpieCookieAndSendsCfHeadersOnLaterRequests() = runBlocking {
        client.cfAccessClientId = "dummy-client-id.access"
        client.cfAccessClientSecret = "dummy-client-secret"

        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Set-Cookie", "magpie_web_${server.port}=dummy-session; Path=/; HttpOnly")
                .addHeader("Location", "/")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"version":"Magpie 2.4","timezone":"Asia/Shanghai"}""")
        )

        val baseUrl = server.url("/").toString()
        assertTrue(client.signIn(baseUrl, "dummy-web-key"))
        client.getState(baseUrl)

        val signInRequest = server.takeRequest()
        assertEquals("dummy-client-id.access", signInRequest.getHeader("CF-Access-Client-Id"))
        assertEquals("dummy-client-secret", signInRequest.getHeader("CF-Access-Client-Secret"))
        assertTrue(signInRequest.body.readUtf8().contains("key=dummy-web-key"))

        val stateRequest = server.takeRequest()
        assertEquals("dummy-client-id.access", stateRequest.getHeader("CF-Access-Client-Id"))
        assertEquals("dummy-client-secret", stateRequest.getHeader("CF-Access-Client-Secret"))
        assertTrue(
            stateRequest.getHeader("Cookie")?.contains("magpie_web_${server.port}=dummy-session") == true
        )
        assertTrue(client.cookieJar.loadForRequest(server.url("/")).any { it.name == "magpie_web_${server.port}" })
    }

    @Test
    fun testWrongKeyReturnsFalse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("no sign-in cookie"))
        assertFalse(client.signIn(server.url("/").toString(), "wrong-key"))
    }

    @Test(expected = CloudflareAccessRequiredException::class)
    fun testCfRedirectToAnotherHostIsRejected(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "https://team.cloudflareaccess.com/cdn-cgi/access/login")
        )
        client.signIn(server.url("/").toString(), "dummy-web-key")
    }

    @Test
    fun testSameOriginRedirectWithoutMagpieCookieIsNotSuccess() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Set-Cookie", "other_cookie=value; Path=/")
                .addHeader("Location", "/")
        )
        assertFalse(client.signIn(server.url("/").toString(), "dummy-web-key"))
    }

    @Test(expected = PermissionDeniedException::class)
    fun testSignIn403IsRejected(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        client.signIn(server.url("/").toString(), "dummy-web-key")
    }

    @Test
    fun testCloudflareAccessHeadersSent() = runBlocking {
        client.cfAccessClientId = "test-client-id.access"
        client.cfAccessClientSecret = "test-client-secret-key"

        val json = """{"version":"Magpie 2.4","timezone":"Asia/Shanghai","read_only":false}"""
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json))

        val baseUrl = server.url("/").toString()
        client.getState(baseUrl)

        val recorded = server.takeRequest()
        assertEquals("test-client-id.access", recorded.getHeader("CF-Access-Client-Id"))
        assertEquals("test-client-secret-key", recorded.getHeader("CF-Access-Client-Secret"))
    }
}
