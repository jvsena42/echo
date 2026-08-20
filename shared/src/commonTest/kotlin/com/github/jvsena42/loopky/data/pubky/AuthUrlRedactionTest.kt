package com.github.jvsena42.loopky.data.pubky

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These assert an absence, which is the only property that matters: `Log.d` reaches logcat in
 * release, so anything these helpers let through is readable on a production device.
 */
class AuthUrlRedactionTest {

    private val relaySecret = "U55XnoH6vsMCpx1pxHtt8fReVg4Brvu9C0gUBuw-Jkw"
    private val signupToken = "ABCD-1234-EFGH"

    @Test
    fun theRelaySecretNeverSurvivesRedaction() {
        val url = "pubkyauth:///?caps=/pub/loopky/:rw&secret=$relaySecret&relay=https://httprelay.pubky.app/inbox"

        val redacted = url.redactAuthUrl()

        assertFalse(relaySecret in redacted, "the client secret decrypts the auth token — it must not be logged")
    }

    @Test
    fun theSignupTokenNeverSurvivesRedaction() {
        val url = "pubkyauth://signup?caps=/pub/loopky/:rw&secret=$relaySecret&hs=homeserverpk&st=$signupToken"

        val redacted = url.redactAuthUrl()

        assertFalse(signupToken in redacted, "a signup token is single-use and may have been paid for")
        assertFalse(relaySecret in redacted)
    }

    @Test
    fun theStructureSurvivesSoAMalformedUrlIsStillDiagnosable() {
        val url = "pubkyauth://signup?caps=/pub/loopky/:rw&secret=$relaySecret&hs=homeserverpk&st=$signupToken"

        val redacted = url.redactAuthUrl()

        assertTrue(redacted.startsWith("pubkyauth://signup?"), "the intent host is what tells signin from signup")
        assertTrue("caps=/pub/loopky/:rw" in redacted, "capabilities are not secret and are the usual suspect")
        assertTrue("hs=homeserverpk" in redacted, "which homeserver was targeted is not secret")
    }

    @Test
    fun aUrlWithNoQueryIsLeftAlone() {
        assertEquals("loopky://login-callback", "loopky://login-callback".redactAuthUrl())
    }

    @Test
    fun theSessionSecretNeverSurvivesPayloadRedaction() {
        val secret = "live-session-secret-value"
        val payload = """{"pubky":"ownerpk","session_secret":"$secret","capabilities":["/pub/loopky/:rw"]}"""

        val redacted = payload.redactSessionPayload()

        assertFalse(secret in redacted, "this credential authenticates every write the app makes")
        assertFalse("ownerpk" in redacted, "only key names are printed, so values cannot leak by omission")
    }

    @Test
    fun payloadRedactionReportsWhichKeysArrived() {
        val payload = """{"pubky":"ownerpk","session_secret":"s","capabilities":[]}"""

        val redacted = payload.redactSessionPayload()

        // The point of the log line is "did the payload arrive, and did it carry what we need".
        assertTrue("pubky" in redacted)
        assertTrue("capabilities" in redacted)
        assertTrue("session_secret=" in redacted, "a missing secret is a real failure mode worth seeing")
    }

    @Test
    fun anUnrecognisableThirdPartyPayloadStillLeaksNothing() {
        // Ring owns this payload's shape; an allow-list of printed keys means an unexpected
        // shape degrades to a length, never to its contents.
        val redacted = "not json at all, but possibly containing a secret".redactSessionPayload()

        assertFalse("secret" in redacted)
        assertTrue("chars" in redacted)
    }
}
