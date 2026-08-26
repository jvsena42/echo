package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.ErrorReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun aRateLimitedSessionImportIsServerBusyNotAnExpiry() {
        // Seen on device deleting a deck: it fires one session-authenticated delete per record,
        // the homeserver 429s the session import, and the FFI wraps it in wording carrying both
        // "session" and "import". Reading that as an expiry did more than mislabel it — the retry
        // loop answers an expiry by revalidating, which is itself a homeserver call, so it hit the
        // same rate limit and gave up instead of backing off. The deck stayed put and the user was
        // told their session had expired.
        val limited = PubkyError(
            "Failed to import session: Request failed: Server responded with an error: " +
                "429 Too Many Requests - Rate limit exceeded",
        )

        assertFalse(limited.isSessionExpired())
        // ServerBusy, not Offline: the homeserver answered, so telling the user to check their
        // connection points them at the one thing that is definitely working.
        assertEquals(ErrorReason.ServerBusy, limited.toErrorReason())
    }

    @Test
    fun aQuotaFailureDuringSessionImportIsStorageFullNotAnExpiry() {
        // Same wording trap as the 429, but terminal: routing it through revalidate() would spend
        // a round trip to reach the same answer, and "sign in again" does not free any disk.
        val full = PubkyError("Failed to import session: 507 Insufficient Storage")

        assertFalse(full.isSessionExpired())
        assertEquals(ErrorReason.StorageFull, full.toErrorReason())
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

    @Test
    fun namesAPubkyWithNoHomeserverRecordInsteadOfGuessingAtTheNetwork() {
        // `get_homeserver` turns Ok(None) into an error carrying this exact wording. Without its
        // own classifier it fell through to Unknown, and the sign-in path renders Unknown as
        // "sign-in didn't finish" — a Pubky Ring failure, for a phrase that simply has no account.
        val noRecord = PubkyError("No homeserver found for this public key")

        assertEquals(ErrorReason.NoHomeserverAccount, noRecord.toErrorReason())
    }

    @Test
    fun aDhtResolutionFailureIsNotReportedAsHavingNoAccount() {
        // The other arm of the same FFI call. This one genuinely is a transport failure and must
        // stay one: telling a user their recovery phrase belongs to no account because the DHT was
        // unreachable is a verdict we have no basis for (#147).
        val unreachable = PubkyError("Failed to get homeserver: pkarr: failed to resolve packet for key")

        assertEquals(ErrorReason.Offline, unreachable.toErrorReason())
    }

    @Test
    fun theNoRecordClassifierWinsOverTheTransportOneWhenAMessageCarriesBoth() {
        // The ordering guard. "failed to resolve" is in isNetworkFailure's list, so a message
        // containing both substrings is exactly how the specific answer gets swallowed by the
        // generic one. isNoHomeserverRecord runs first precisely so this cannot happen.
        val both = PubkyError("No homeserver found for this public key (failed to resolve)")

        assertEquals(ErrorReason.NoHomeserverAccount, both.toErrorReason())
    }

    @Test
    fun anUnrelatedNotFoundIsStillANotFound() {
        // Proof the new classifier is narrow enough to sit second: it must not capture the
        // ordinary record misses that every deck and profile read produces.
        val deckMiss = PubkyError("Request failed: 404 Not Found - pubky://x/pub/loopky/decks/1/manifest.json")

        assertEquals(ErrorReason.NotFound, deckMiss.toErrorReason())
        assertFalse(deckMiss.isNoHomeserverRecord())
    }
}
