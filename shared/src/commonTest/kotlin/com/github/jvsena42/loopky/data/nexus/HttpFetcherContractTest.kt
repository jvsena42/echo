package com.github.jvsena42.loopky.data.nexus

import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract that lets a gated API's verdicts survive the trip to the caller. Written against
 * the fake, because what is being pinned down is the *shape* both platform impls must honour.
 */
class HttpFetcherContractTest {

    private val http = FakeHttpFetcher()
    private val url = "https://example.test/thing"

    @Test
    fun aNonSuccessStatusIsDataRatherThanAFailure() = runTest {
        // The point of `send`: 403 means "not available in your region" to a gated signup
        // service, and the caller has to be able to read that rather than catch it.
        http.enqueue(HttpMethod.GET, url, HttpResponse(statusCode = 403, body = """{"reason":"geo"}"""))

        val response = http.send(HttpRequest(url)).getOrThrow()

        assertEquals(403, response.statusCode)
        assertFalse(response.isSuccess)
        assertEquals("""{"reason":"geo"}""", response.body, "the failure body is the whole reason for this API")
    }

    @Test
    fun onlyATransportFailureIsAFailedResult() = runTest {
        http.enqueueFailure(HttpMethod.GET, url, IllegalStateException("offline"))

        val result = http.send(HttpRequest(url))

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is HttpError, "an unreachable server has no status to report")
    }

    @Test
    fun theNarrowGetContractStillTurnsNonSuccessIntoAnHttpError() = runTest {
        // Nexus and Unsplash only ever expect 200, and must keep behaving as they did.
        http.enqueue(HttpMethod.GET, url, HttpResponse(statusCode = 404, body = "gone"))

        val error = http.get(url).exceptionOrNull()

        assertEquals(404, assertIs<HttpError>(error).statusCode)
    }

    @Test
    fun theGetErrorNeverCarriesTheResponseBody() = runTest {
        // Bodies from third-party services echo back what was sent, and this message reaches
        // logcat. Callers that need the body use `send`.
        val secret = "+31600000000"
        http.enqueue(HttpMethod.GET, url, HttpResponse(statusCode = 429, body = """{"phone":"$secret"}"""))

        val message = http.get(url).exceptionOrNull()?.message.orEmpty()

        assertFalse(secret in message)
    }

    @Test
    fun getAndPostOnTheSameUrlAreDistinct() = runTest {
        http.enqueue(HttpMethod.GET, url, HttpResponse(statusCode = 200, body = "read"))
        http.enqueue(HttpMethod.POST, url, HttpResponse(statusCode = 200, body = "wrote"))

        assertEquals("read", http.send(HttpRequest(url, HttpMethod.GET)).getOrThrow().body)
        assertEquals("wrote", http.send(HttpRequest(url, HttpMethod.POST, body = "{}")).getOrThrow().body)
    }

    @Test
    fun queuedResponsesLetOneUrlAnswerDifferentlyEachTime() = runTest {
        // A long-poll answers 408, 408, then 200 on the very same URL — without this there is no
        // way to test the re-poll loop at all.
        http.enqueue(
            HttpMethod.GET,
            url,
            HttpResponse(statusCode = 408, body = ""),
            HttpResponse(statusCode = 408, body = ""),
            HttpResponse(statusCode = 200, body = "paid"),
        )

        val codes = List(3) { http.send(HttpRequest(url)).getOrThrow().statusCode }

        assertEquals(listOf(408, 408, 200), codes)
    }

    @Test
    fun theLastQueuedResponseRepeatsSoAPollCanOverrun() = runTest {
        http.enqueue(HttpMethod.GET, url, HttpResponse(statusCode = 200, body = "steady"))

        repeat(3) { assertEquals("steady", http.send(HttpRequest(url)).getOrThrow().body) }
    }

    @Test
    fun theRequestRecordsMethodBodyAndTimeoutForAssertions() = runTest {
        http.enqueue(HttpMethod.POST, url, HttpResponse(statusCode = 200, body = "{}"))

        http.send(HttpRequest(url, HttpMethod.POST, body = """{"phoneNumber":"+31"}""", timeoutMs = 70_000L))

        val request = http.requests.single()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("""{"phoneNumber":"+31"}""", request.body)
        assertEquals(70_000L, request.timeoutMs)
    }

    @Test
    fun headerLookupIsCaseInsensitive() = runTest {
        // Platforms disagree on header casing; callers should not have to care.
        http.enqueue(
            HttpMethod.GET,
            url,
            HttpResponse(statusCode = 429, body = "", headers = mapOf("retry-after" to "60")),
        )

        val response = http.send(HttpRequest(url)).getOrThrow()

        assertEquals("60", response.header("Retry-After"))
        assertNull(response.header("x-absent"))
    }
}
