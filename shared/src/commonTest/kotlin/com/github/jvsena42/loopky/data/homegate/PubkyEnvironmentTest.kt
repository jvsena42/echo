package com.github.jvsena42.loopky.data.homegate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PubkyEnvironmentTest {

    @Test
    fun theTwoEnvironmentsNeverShareAHomeserverOrAGate() {
        // The pairing is the whole point of the type: a token minted by one Homegate is only
        // valid on its own homeserver, and a signup token is single-use, so crossing them is
        // not a recoverable mistake.
        assertNotEquals(PubkyEnvironment.Staging.homegateBaseUrl, PubkyEnvironment.Production.homegateBaseUrl)
        assertNotEquals(PubkyEnvironment.Staging.defaultHomeserver, PubkyEnvironment.Production.defaultHomeserver)
    }

    @Test
    fun theHomeserversMatchPubkyRingsConstants() {
        // Verbatim from pubky-ring `src/utils/constants.ts:2-3`. If Ring's defaults ever move,
        // a key created there and a deck published here would land on different servers.
        assertEquals(
            "ufibwbmed6jeq9k4p583go95wofakh9fwpp4k734trq79pd9u1uy",
            PubkyEnvironment.Staging.defaultHomeserver,
        )
        assertEquals(
            "8um71us3fyw6h8wbcxb5ar3rwusy1a6u49956ikzojg3gcwd1dty",
            PubkyEnvironment.Production.defaultHomeserver,
        )
    }

    @Test
    fun anUnknownOrMissingNameResolvesToProduction() {
        // Production is the safe fallback: a confused build pointed at staging would mint tokens
        // the production homeserver rejects, which is worse than the reverse.
        assertEquals(PubkyEnvironment.Production, PubkyEnvironment.fromNameOrProduction(null))
        assertEquals(PubkyEnvironment.Production, PubkyEnvironment.fromNameOrProduction(""))
        assertEquals(PubkyEnvironment.Production, PubkyEnvironment.fromNameOrProduction("nonsense"))
    }

    @Test
    fun namesRoundTripSoAPersistedChoiceSurvives() {
        // The Settings override stores the enum name; a mismatch here silently reverts a debug
        // build to production on the next launch.
        PubkyEnvironment.entries.forEach { environment ->
            assertEquals(environment, PubkyEnvironment.fromNameOrProduction(environment.name))
        }
        assertEquals(PubkyEnvironment.Staging, PubkyEnvironment.fromNameOrProduction("staging"))
    }

    @Test
    fun everyGateIsHttps() {
        PubkyEnvironment.entries.forEach {
            assertTrue(it.homegateBaseUrl.startsWith("https://"), "${it.name} must not be plaintext")
        }
    }
}
