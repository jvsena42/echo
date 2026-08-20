package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.homegate.HomegateClient
import com.github.jvsena42.loopky.data.homegate.MethodAvailability
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.nexus.HttpMethod
import com.github.jvsena42.loopky.data.nexus.HttpResponse
import com.github.jvsena42.loopky.data.storage.PendingSignup
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import com.github.jvsena42.loopky.testing.FakeSignupTokenStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignupRepositoryImplTest {

    private val http = FakeHttpFetcher()
    private val store = FakeSignupTokenStore()
    private val environment = PubkyEnvironment.Staging

    private val repository = SignupRepositoryImpl(
        homegate = HomegateClient(http, baseUrl = environment.homegateBaseUrl),
        tokenStore = store,
        environment = environment,
        nowMillis = { 0L },
    )

    private val base = environment.homegateBaseUrl
    private fun ok(body: String) = HttpResponse(statusCode = 200, body = body)

    @Test
    fun aRedeemedSmsCodeIsPersistedBeforeTheCallReturns() = runTest {
        // Between "Homegate issued a token" and "the token is stored" the user's SMS attempt is
        // already spent — there must be no window where it lives only in memory.
        http.enqueue(
            HttpMethod.POST,
            "$base/sms_verification/validate_code",
            ok("""{"valid":true,"signupCode":"ABCD-1234-EFGH","homeserverPubky":"hs123"}"""),
        )

        val pending = repository.redeemSmsCode("+31600000000", "123456").getOrThrow()

        assertEquals(pending, store.stored, "the store must already hold it when the call returns")
        assertEquals("ABCD-1234-EFGH", pending.token)
        assertEquals("hs123", pending.homeserverPubky)
        assertEquals(PendingSignup.Source.Sms, pending.source)
    }

    @Test
    fun aRejectedSmsCodeStoresNothing() = runTest {
        http.enqueue(HttpMethod.POST, "$base/sms_verification/validate_code", ok("""{"valid":"false"}"""))

        assertTrue(repository.redeemSmsCode("+31", "000000").isFailure)
        assertNull(store.stored)
    }

    @Test
    fun aPaidInvoiceIsPersistedBeforeTheCallReturns() = runTest {
        // This is the one the user actually paid sats for.
        http.enqueue(
            HttpMethod.GET,
            "$base/ln_verification/v1/await",
            ok("""{"isPaid":true,"signupCode":"PAID-CODE-HERE","homeserverPubky":"hs123"}"""),
        )

        val pending = repository.awaitInvoice(invoice()).getOrThrow()

        assertEquals(pending, store.stored)
        assertEquals(PendingSignup.Source.Lightning, pending.source)
    }

    @Test
    fun anInviteCodeIsPairedWithTheConfiguredHomeserver() = runTest {
        // No Homegate call on this path, so the environment default is the only answer available.
        val pending = repository.redeemInviteCode("ABCD-1234-EFGH").getOrThrow()

        assertEquals(environment.defaultHomeserver, pending.homeserverPubky)
        assertEquals(PendingSignup.Source.Invite, pending.source)
        assertEquals(pending, store.stored)
    }

    @Test
    fun aMalformedInviteCodeCostsNoNetworkCall() = runTest {
        assertTrue(repository.redeemInviteCode("nope").isFailure)

        assertTrue(http.requests.isEmpty(), "a typo should not become a round trip")
        assertNull(store.stored)
    }

    @Test
    fun anInviteCodeIsNormalisedSoAPastedOneStillMatches() = runTest {
        // Users paste these out of chat apps and emails, complete with stray spacing and case.
        val pending = repository.redeemInviteCode(" abcd 1234 efgh ").getOrThrow()

        assertEquals("ABCD-1234-EFGH", pending.token)
    }

    @Test
    fun bothAvailabilityProbesAreAsked() = runTest {
        http.enqueue(HttpMethod.GET, "$base/sms_verification/info", HttpResponse(403, ""))
        http.enqueue(HttpMethod.GET, "$base/ln_verification/info", ok("""{"amountSat":10}"""))

        val availability = repository.availability()

        assertEquals(MethodAvailability.Unavailable, availability.sms)
        assertEquals(10L, assertIs<MethodAvailability.Available>(availability.lightning).priceSat)
    }

    @Test
    fun anUnreachableGateLeavesBothMethodsOfferedRatherThanLockingTheUserOut() = runTest {
        // Availability is a courtesy, not a gate: if it cannot be determined, let the method's own
        // screen fail honestly rather than hiding the only way into the app.
        val availability = repository.availability()

        assertEquals(MethodAvailability.Unknown, availability.sms)
        assertEquals(MethodAvailability.Unknown, availability.lightning)
    }

    @Test
    fun clearingIsTheOnlyWayTheTokenGoesAway() = runTest {
        repository.redeemInviteCode("ABCD-1234-EFGH").getOrThrow()

        repository.clearPending()

        assertNull(store.stored)
        assertEquals(1, store.clearCount)
    }

    private fun invoice() = com.github.jvsena42.loopky.data.homegate.LnInvoice(
        id = "v1",
        bolt11 = "lnbc",
        amountSat = 10,
        expiresAtMillis = 0L,
    )

    // --- surviving the app being killed --------------------------------------------------------

    @Test
    fun theInvoiceIsPersistedBeforeTheUserEverSeesIt() = runTest {
        // The next thing they do is leave for a wallet app, where Loopky can be killed. Without
        // the verification id on disk, a payment made in that window could never be claimed.
        http.enqueue(
            HttpMethod.POST,
            "$base/ln_verification",
            ok("""{"id":"v1","bolt11Invoice":"lnbc","amountSat":10,"expiresAt":9999999999999}"""),
        )

        repository.createInvoice().getOrThrow()

        val awaiting = assertIs<PendingSignup.AwaitingPayment>(store.stored)
        assertEquals("v1", awaiting.verificationId)
    }

    @Test
    fun anOutstandingInvoiceIsResumableRatherThanReissued() = runTest {
        store.save(
            PendingSignup.AwaitingPayment(verificationId = "v1", amountSat = 10, expiresAtMillis = 9999999999999),
        )

        val resumed = repository.resumableInvoice()

        assertEquals("v1", resumed?.id, "a second invoice would leave the paid one unwatched")
    }

    @Test
    fun anExpiredInvoiceIsNotResumedAndIsForgotten() = runTest {
        val expired = SignupRepositoryImpl(
            homegate = HomegateClient(http, baseUrl = environment.homegateBaseUrl),
            tokenStore = store,
            environment = environment,
            nowMillis = { 5_000L },
        )
        store.save(PendingSignup.AwaitingPayment(verificationId = "v1", amountSat = 10, expiresAtMillis = 1_000L))

        assertNull(expired.resumableInvoice())
        assertNull(store.stored, "nothing can be claimed against a dead invoice")
    }

    @Test
    fun aSentSmsIsRememberedSoTheUserDoesNotSpendASecondAttempt() = runTest {
        // Sending spends one of two verifications per week, and reading it happens in another app.
        http.enqueue(HttpMethod.POST, "$base/sms_verification/send_code", ok("{}"))

        repository.sendSmsCode("+31600000000").getOrThrow()

        assertEquals("+31600000000", assertIs<PendingSignup.AwaitingSmsCode>(store.stored).phoneNumber)
        assertEquals("+31600000000", repository.resumableSmsPhoneNumber())
    }

    @Test
    fun aFailedSmsSendRemembersNothing() = runTest {
        http.enqueue(HttpMethod.POST, "$base/sms_verification/send_code", HttpResponse(403, ""))

        assertTrue(repository.sendSmsCode("+31").isFailure)
        assertNull(store.stored, "no attempt was spent, so there is nothing to resume")
    }
}
