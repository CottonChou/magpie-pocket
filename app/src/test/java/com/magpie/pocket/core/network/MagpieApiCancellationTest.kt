package com.magpie.pocket.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class MagpieApiCancellationTest {

    @Test
    fun traceLongPollCancellationClosesWaitingCall() = runBlocking {
        val server = MockWebServer()
        var job: Job? = null
        try {
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = MagpieApiClient()
            val outcome = CompletableDeferred<Throwable?>()
            val callJob = launch(Dispatchers.IO) {
                try {
                    client.getTraceSnapshot(server.url("/").toString(), afterSeq = 1L)
                    outcome.complete(null)
                } catch (e: CancellationException) {
                    outcome.complete(e)
                    throw e
                } catch (e: Throwable) {
                    outcome.complete(e)
                }
            }
            job = callJob

            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            withTimeout(1_000) { callJob.cancelAndJoin() }

            assertTrue(callJob.isCancelled)
            assertTrue(withTimeout(1_000) { outcome.await() } is CancellationException)
        } finally {
            job?.cancelAndJoin()
            server.shutdown()
        }
    }

    @Test
    fun delayedBodyCancellationClosesBodyRead() = runBlocking {
        val server = MockWebServer()
        var job: Job? = null
        try {
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"version":"fixture","timezone":"UTC","read_only":false}""")
                    .setBodyDelay(2, TimeUnit.SECONDS)
            )
            val client = MagpieApiClient()
            val outcome = CompletableDeferred<Throwable?>()
            val callJob = launch(Dispatchers.IO) {
                try {
                    client.getState(server.url("/").toString())
                    outcome.complete(null)
                } catch (e: CancellationException) {
                    outcome.complete(e)
                    throw e
                } catch (e: Throwable) {
                    outcome.complete(e)
                }
            }
            job = callJob

            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            delay(200)
            assertTrue(callJob.isActive)
            withTimeout(1_000) { callJob.cancelAndJoin() }

            assertTrue(callJob.isCancelled)
            assertTrue(withTimeout(1_000) { outcome.await() } is CancellationException)
        } finally {
            job?.cancelAndJoin()
            server.shutdown()
        }
    }

    @Test
    fun signInCancellationClosesWaitingCall() = runBlocking {
        val server = MockWebServer()
        var job: Job? = null
        try {
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = MagpieApiClient()
            val outcome = CompletableDeferred<Throwable?>()
            val callJob = launch(Dispatchers.IO) {
                try {
                    client.signIn(server.url("/").toString(), "fixture-web-key")
                    outcome.complete(null)
                } catch (e: CancellationException) {
                    outcome.complete(e)
                    throw e
                } catch (e: Throwable) {
                    outcome.complete(e)
                }
            }
            job = callJob

            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            withTimeout(1_000) { callJob.cancelAndJoin() }

            assertTrue(callJob.isCancelled)
            assertTrue(withTimeout(1_000) { outcome.await() } is CancellationException)
        } finally {
            job?.cancelAndJoin()
            server.shutdown()
        }
    }
}
