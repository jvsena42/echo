package com.github.jvsena42.loopky.data.homegate

import com.github.jvsena42.loopky.data.nexus.HttpMethod
import com.github.jvsena42.loopky.data.nexus.HttpResponse
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HomegateClientTest {

    private val http = FakeHttpFetcher()
    private val client = HomegateClient(http, baseUrl = BASE)

    private fun <T> Result<T>.homegateError(): HomegateError? =
        (exceptionOrNull() as? HomegateException)?.error

    private fun ok(body: String) = HttpResponse(statusCode = 200, body = body)

    // --- availability -------------------------------------------------------------------------

    @Test
    fun aForbiddenInfoCallMeansTheMethodIsNotOfferedHere() = runTest {
        http.enqueue(HttpMethod.GET, "$BASE/sms_verification/info", HttpResponse(403, ""))

        assertEquals(MethodAvailability.Unavailable, client.smsAvailability())
    }

    @Test
    fun anUnreachableInfoCallLeavesTheMethodOfferedRatherThanHidden() = runTest {
        // A flaky availability check must not lock someone out of the only route into the app;
        // the method's own screen will fail honestly if it really is blocked.
        http.enqueueFailure(HttpMethod.GET, "$BASE/sms_verification/info", IllegalStateException("offline"))

        assertEquals(MethodAvailability.Unknown, client.smsAvailability())
    }

    @Test
    fun lightningAvailabilityCarriesThePrice() = runTest {
        http.enqueue(HttpMethod.GET, "$BASE/ln_verification/info", ok("""{"amountSat":1500}"""))

        val availability = assertIs<MethodAvailability.Available>(client.lightningAvailability())
        assertEquals(1500L, availability.priceSat)
    }

    // --- SMS ----------------------------------------------------------------------------------

    @Test
    fun sendingACodePostsOnlyThePhoneNumber() = runTest {
        http.enqueue(HttpMethod.POST, SEND_CODE, ok("{}"))

        client.sendSmsCode("+31600000000").getOrThrow()

        val request = http.requests.single()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("""{"phoneNumber":"+31600000000"}""", request.body)
    }

    @Test
    fun aBlockedNumberIsNamedSeparatelyFromARateLimit() = runTest {
        http.enqueue(HttpMethod.POST, SEND_CODE, HttpResponse(403, ""))

        assertEquals(HomegateError.PhoneBlocked, client.sendSmsCode("+31600000000").homegateError())
    }

    @Test
    fun weeklyAndYearlyLimitsAreReadOutOfTheBodyBecauseThereIsNoErrorCode() = runTest {
        http.enqueue(
            HttpMethod.POST,
            SEND_CODE,
            HttpResponse(429, """{"error":"Weekly limit reached"}"""),
            HttpResponse(429, """{"error":"Annual limit reached"}"""),
        )

        assertEquals(HomegateError.RateLimitedWeekly, client.sendSmsCode("+31").homegateError())
        assertEquals(HomegateError.RateLimitedYearly, client.sendSmsCode("+31").homegateError())
    }

    @Test
    fun anUnrecognisedRateLimitBodyDegradesToTheRecoverableVerdict() = runTest {
        // Substring matching is fragile by construction, so a miss must never invent a terminal
        // "you cannot try again this year" out of a body we simply did not understand.
        http.enqueue(
            HttpMethod.POST,
            SEND_CODE,
            HttpResponse(429, """{"error":"slow down"}""", mapOf("retry-after" to "45")),
        )

        val error = assertIs<HomegateError.RateLimitedTemporary>(client.sendSmsCode("+31").homegateError())
        assertEquals(45, error.retryAfterSeconds)
    }

    @Test
    fun aValidCodeYieldsBothTheTokenAndTheHomeserverItBelongsTo() = runTest {
        http.enqueue(
            HttpMethod.POST,
            VALIDATE_CODE,
            ok("""{"valid":true,"signupCode":"ABCD-1234-EFGH","homeserverPubky":"hs123"}"""),
        )

        val grant = client.validateSmsCode("+31", "123456").getOrThrow()

        assertEquals("ABCD-1234-EFGH", grant.token)
        assertEquals("hs123", grant.homeserverPubky, "spending a token on another homeserver burns it")
    }

    @Test
    fun validIsAcceptedAsTheStringTrueAsWellAsTheBoolean() = runTest {
        // Homegate really does send both; pubky-app's client carries the same coercion.
        http.enqueue(
            HttpMethod.POST,
            VALIDATE_CODE,
            ok("""{"valid":"true","signupCode":"ABCD-1234-EFGH","homeserverPubky":"hs123"}"""),
        )

        assertEquals("ABCD-1234-EFGH", client.validateSmsCode("+31", "1").getOrThrow().token)
    }

    @Test
    fun anInvalidCodeIsNamedRatherThanReadingAsAServiceFailure() = runTest {
        http.enqueue(HttpMethod.POST, VALIDATE_CODE, ok("""{"valid":"false"}"""))

        assertEquals(HomegateError.CodeIncorrect, client.validateSmsCode("+31", "000000").homegateError())
    }

    @Test
    fun aFailureNeverCarriesThePhoneNumberOrTheCode() = runTest {
        // These messages reach logcat, and logcat is readable in release.
        val phone = "+31600000000"
        http.enqueue(HttpMethod.POST, VALIDATE_CODE, HttpResponse(500, """{"phone":"$phone","code":"123456"}"""))

        val message = client.validateSmsCode(phone, "123456").exceptionOrNull()?.message.orEmpty()

        assertFalse(phone in message)
        assertFalse("123456" in message)
        assertFalse(BASE in message)
    }

    @Test
    fun malformedJsonIsAServiceProblemNotACodeProblem() = runTest {
        http.enqueue(HttpMethod.POST, VALIDATE_CODE, ok("{not json"))

        assertTrue(client.validateSmsCode("+31", "1").isFailure)
    }

    // --- Lightning ----------------------------------------------------------------------------

    @Test
    fun anInvoiceCarriesEnoughToRenderAndToExpire() = runTest {
        http.enqueue(
            HttpMethod.POST,
            "$BASE/ln_verification",
            ok("""{"id":"v1","bolt11Invoice":"lnbc10n1...","amountSat":1500,"expiresAt":1700000000000}"""),
        )

        val invoice = client.createLnInvoice().getOrThrow()

        assertEquals("v1", invoice.id)
        assertEquals("lnbc10n1...", invoice.bolt11)
        assertEquals(1500L, invoice.amountSat)
        assertEquals(1700000000000L, invoice.expiresAtMillis)
    }

    @Test
    fun theAwaitLoopRepollsOnTimeoutBecause408MeansAskAgain() = runTest {
        http.enqueue(
            HttpMethod.GET,
            AWAIT,
            HttpResponse(408, ""),
            HttpResponse(408, ""),
            ok("""{"isPaid":true,"signupCode":"TOK","homeserverPubky":"hs123"}"""),
        )

        val grant = client.awaitLnPayment(invoice(), nowMillis = { 0L }).getOrThrow()

        assertEquals("TOK", grant.token)
        assertEquals(3, http.requests.size, "each 408 has to cost exactly one more request")
    }

    @Test
    fun theAwaitRequestOutlivesTheServersOwnWindow() = runTest {
        // Our timeout firing before the server's 60s would report a paid invoice as unpaid.
        http.enqueue(HttpMethod.GET, AWAIT, ok("""{"isPaid":true,"signupCode":"T","homeserverPubky":"h"}"""))

        client.awaitLnPayment(invoice(), nowMillis = { 0L }).getOrThrow()

        assertTrue(http.requests.single().timeoutMs > 60_000L)
    }

    @Test
    fun aNotYetPaidResponseKeepsWaitingRatherThanReportingFailure() = runTest {
        http.enqueue(
            HttpMethod.GET,
            AWAIT,
            ok("""{"isPaid":false}"""),
            ok("""{"isPaid":true,"signupCode":"TOK","homeserverPubky":"hs"}"""),
        )

        assertEquals("TOK", client.awaitLnPayment(invoice(), nowMillis = { 0L }).getOrThrow().token)
    }

    @Test
    fun aLostVerificationStopsTheLoopInsteadOfSpinningForever() = runTest {
        http.enqueue(HttpMethod.GET, AWAIT, HttpResponse(404, ""))

        assertEquals(
            HomegateError.VerificationLost,
            client.awaitLnPayment(invoice(), nowMillis = { 0L }).homegateError(),
        )
    }

    @Test
    fun anExpiredInvoiceStopsBeforeAskingAtAll() = runTest {
        val result = client.awaitLnPayment(invoice(expiresAt = 1_000L), nowMillis = { 2_000L })

        assertEquals(HomegateError.InvoiceExpired, result.homegateError())
        assertTrue(http.requests.isEmpty(), "there is nothing to ask about once the invoice is dead")
    }

    @Test
    fun aTransportFailureMidAwaitIsRetriedRatherThanReportedAsUnpaid() = runTest {
        // Doze and sleeping radios kill long sockets routinely; treating that as "unpaid" would
        // lose a payment the user already made.
        http.enqueueFailure(HttpMethod.GET, AWAIT, IllegalStateException("socket closed"))
        http.enqueue(HttpMethod.GET, AWAIT, ok("""{"isPaid":true,"signupCode":"TOK","homeserverPubky":"hs"}"""))

        assertEquals("TOK", client.awaitLnPayment(invoice(), nowMillis = { 0L }).getOrThrow().token)
    }

    private fun invoice(expiresAt: Long = 0L) =
        LnInvoice(id = "v1", bolt11 = "lnbc", amountSat = 1500, expiresAtMillis = expiresAt)

    private companion object {
        const val BASE = "https://homegate.test"
        const val SEND_CODE = "$BASE/sms_verification/send_code"
        const val VALIDATE_CODE = "$BASE/sms_verification/validate_code"
        const val AWAIT = "$BASE/ln_verification/v1/await"
    }
}
