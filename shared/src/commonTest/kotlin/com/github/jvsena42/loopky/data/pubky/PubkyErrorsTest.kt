package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.ErrorReason
import kotlin.test.Test
import kotlin.test.assertEquals

class PubkyErrorsTest {

    @Test
    fun classifiesTheTransportFailureSeenOnDevice() {
        val real = PubkyError(
            "Failed to send list request: Request failed: HTTP transport error: " +
                "error sending request for url (https://_pubky.rc3omrqq/pub/loopky/decks/?)",
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
        assertEquals(ErrorReason.NotFound, PubkyError("not found: pubky://x/pub/loopky").toErrorReason())
    }

    @Test
    fun classifiesAnExpiredSessionAheadOfEverythingElse() {
        // Session expiry must win: it is actionable in a way "offline" is not.
        val expired = PubkyError("session import failed: invalid session")

        assertEquals(ErrorReason.SessionExpired, expired.toErrorReason())
    }

    @Test
    fun anOfflineSessionImportIsOfflineNotAnExpiry() {
        // Seen on device: offline, the FFI's own session call fails with wording that contains
        // both "session" and "import", so it classified as an expiry and the study screen told the
        // user to sign in with Pubky Ring again over a dropped connection.
        val offline = PubkyError(
            "Failed to import session: Request failed: HTTP transport error: error sending " +
                "request for url (https://_pubky.rc3omrqq/session)",
        )

        assertEquals(ErrorReason.Offline, offline.toErrorReason())
    }

    @Test
    fun classifiesTheHomeserverQuotaBodyAsStorageFull() {
        // The body the homeserver actually returns with 507, verbatim.
        val full = PubkyError("Request failed: HTTP status 507: Disk space quota exceeded")

        assertEquals(ErrorReason.StorageFull, full.toErrorReason())
    }

    @Test
    fun classifiesTheStatusLineAloneAsStorageFull() {
        // The storage layer builds its own 507 separately from the pre-flight check, so the
        // wording reaching the FFI need not include the body at all.
        assertEquals(
            ErrorReason.StorageFull,
            PubkyError("Failed to put record: 507 Insufficient Storage").toErrorReason(),
        )
    }

    @Test
    fun doesNotReadAnIdContaining507AsAFullDisk() {
        // Every failure message carries a pubky:// URL and ids are random alphanumerics; a bare
        // "507" substring match would send this user to delete decks over a missing record.
        val notFound = PubkyError("not found: pubky://x/pub/loopky/decks/a507bc/manifest.json")

        assertEquals(ErrorReason.NotFound, notFound.toErrorReason())
    }

    @Test
    fun quotaWinsOverTheTransientClassifiers() {
        // A quota message that also trips isRateLimited must not be retried: the request is not
        // going to succeed after a backoff.
        val ambiguous = PubkyError("rate limit: storage quota exceeded")

        assertEquals(ErrorReason.StorageFull, ambiguous.toErrorReason())
    }

    @Test
    fun fallsBackToUnknownForUnrecognisedText() {
        assertEquals(ErrorReason.Unknown, IllegalStateException("kaboom").toErrorReason())
    }
}
