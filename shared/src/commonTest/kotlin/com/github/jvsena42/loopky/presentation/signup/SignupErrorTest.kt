package com.github.jvsena42.loopky.presentation.signup

import com.github.jvsena42.loopky.data.homegate.HomegateError
import com.github.jvsena42.loopky.data.homegate.HomegateException
import com.github.jvsena42.loopky.data.pubky.PubkyError
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SignupError.TokenRejected] is the only classification that lets the UI clear a stored signup
 * token, and that token may have cost the user an SMS attempt or sats. So both directions matter:
 * missing a real rejection loops them on a dead token, and a false positive destroys a live one.
 *
 * There was no test for this at all until the third review round pointed that out.
 */
class SignupErrorTest {

    @Test
    fun theOnDeviceInvalidTokenMessageIsRecognised() {
        // Verbatim from logcat on an invalid invite code.
        val real = PubkyError(
            "signup failure: Request failed: Server responded with an error: 401 Unauthorized - Invalid token",
        )

        assertEquals(SignupError.TokenRejected, real.toSignupError())
    }

    @Test
    fun aHomeserverRefusingSignupWithA403DoesNotCostTheUserTheirToken() {
        // The case that matters most. A homeserver can refuse a signup for its own reasons —
        // registration closed, or the `/pub/`-write refusal this branch already hit — while the
        // token is untouched and still spendable. Classifying that as a dead token would clear
        // something the user may have paid sats for.
        val refusal = PubkyError(
            "signup failure: Request failed: Server responded with an error: " +
                "403 Forbidden - Writing to directories other than '/pub/' is forbidden",
        )

        assertEquals(SignupError.Unavailable, refusal.toSignupError())
    }

    @Test
    fun aStatusCodeInsideAnIdentifierIsNotARejection() {
        // Ids and paths are random alphanumerics; a bare "401" substring used to be enough.
        val transport = PubkyError(
            "signup failure: HTTP transport error: error sending request for url " +
                "(https://homeserver/pub/loopky/decks/a401b7/manifest.json)",
        )

        assertEquals(SignupError.Unavailable, transport.toSignupError())
    }

    @Test
    fun anOrdinaryTransportFailureDuringSignupStaysRetryable() {
        val offline = PubkyError("signup failure: HTTP transport error: error sending request")

        assertEquals(SignupError.Unavailable, offline.toSignupError())
    }

    @Test
    fun homegateVerdictsAreUnaffected() {
        assertEquals(
            SignupError.CodeIncorrect,
            HomegateException(HomegateError.CodeIncorrect).toSignupError(),
        )
        assertEquals(
            SignupError.RateLimitedWeekly,
            HomegateException(HomegateError.RateLimitedWeekly).toSignupError(),
        )
    }

    @Test
    fun aRejectedTokenIsTheOneErrorThatOffersNoRetry() {
        // Retrying re-sends the same dead value, so the button must not be there.
        val rejected = LocalSignupUiState(isWorking = false, error = SignupError.TokenRejected)
        val transient = LocalSignupUiState(isWorking = false, error = SignupError.Unavailable)

        assertEquals(false, rejected.canRetry)
        assertEquals(true, rejected.canStartOver)
        assertEquals(true, transient.canRetry)
        assertEquals(false, transient.canStartOver)
    }
}
