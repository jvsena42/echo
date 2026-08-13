package com.github.jvsena42.echo.data.pubky

import com.github.jvsena42.echo.domain.model.ErrorReason
import kotlin.test.Test
import kotlin.test.assertEquals

class PubkyErrorsTest {

    @Test
    fun classifiesTheTransportFailureSeenOnDevice() {
        val real = PubkyError(
            "Failed to send list request: Request failed: HTTP transport error: " +
                "error sending request for url (https://_pubky.rc3omrqq/pub/echo/decks/?)",
        )

        assertEquals(ErrorReason.Offline, real.toErrorReason())
    }

    @Test
    fun classifiesTheAuthRelayFailureSeenOnDevice() {
        val real = PubkyError(
            "Auth approval failed: Request failed: HTTP transport error: error sending " +
                "request for url (https://httprelay.pubky.app/inbox/j42EOsZz)",
        )

        assertEquals(ErrorReason.Offline, real.toErrorReason())
    }

    @Test
    fun classifiesAMissingRecordAsNotFound() {
        assertEquals(ErrorReason.NotFound, PubkyError("not found: pubky://x/pub/echo").toErrorReason())
    }

    @Test
    fun classifiesAnExpiredSessionAheadOfEverythingElse() {
        // Session expiry must win: it is actionable in a way "offline" is not.
        val expired = PubkyError("session import failed: invalid session")

        assertEquals(ErrorReason.SessionExpired, expired.toErrorReason())
    }

    @Test
    fun fallsBackToUnknownForUnrecognisedText() {
        assertEquals(ErrorReason.Unknown, IllegalStateException("kaboom").toErrorReason())
    }
}
