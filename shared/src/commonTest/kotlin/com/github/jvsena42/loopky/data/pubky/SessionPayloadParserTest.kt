package com.github.jvsena42.loopky.data.pubky

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The FFI names the session secret differently per auth flow — `session_secret` from the cookie
 * flow Loopky uses, `grant_secret` from the grant flow pubky 0.10 added. Both parse, because the
 * value is interchangeable downstream and a rename here surfaces as a missing-field error miles
 * from its cause.
 */
class SessionPayloadParserTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val secret = "U55XnoH6vsMCpx1pxHtt8fReVg4Brvu9C0gUBuw-Jkw"
    private val pubky = "5jsjx1o6fzu6aeeo697r3i5rx15zq41kikcye8wtwdqm4nb4tryo"

    @Test
    fun theCookieFlowPayloadParses() {
        val payload = """{"pubky":"$pubky","capabilities":["/pub/loopky/:rw"],"session_secret":"$secret"}"""

        val session = parseSessionPayload(payload, json)

        assertEquals(pubky, session.identity.pubky)
        assertEquals(secret, session.sessionSecret)
        assertEquals(listOf("/pub/loopky/:rw"), session.capabilities.map { it.value })
    }

    @Test
    fun theGrantFlowPayloadParsesToTheSameSession() {
        val payload = """{"pubky":"$pubky","capabilities":["/pub/loopky/:rw"],"grant_secret":"$secret"}"""

        val session = parseSessionPayload(payload, json)

        assertEquals(secret, session.sessionSecret, "grant_secret is the same secret under another name")
    }

    @Test
    fun aPayloadWithNoSecretAtAllFails() {
        val payload = """{"pubky":"$pubky","capabilities":[]}"""

        val error = assertFailsWith<IllegalStateException> { parseSessionPayload(payload, json) }

        assertTrue("session_secret" in error.message.orEmpty())
    }
}
