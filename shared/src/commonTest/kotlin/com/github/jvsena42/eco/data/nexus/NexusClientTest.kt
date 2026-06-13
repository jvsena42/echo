package com.github.jvsena42.eco.data.nexus

import com.github.jvsena42.eco.testing.FakeHttpFetcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NexusClientTest {

    private val http = FakeHttpFetcher()
    private val client = NexusClient(http, baseUrl = BASE)

    @Test
    fun hotTagsParsesNexusPayload() = runTest {
        http.respond(
            "$BASE/v0/tags/hot?limit=20",
            """
            [
              {"label":"spanish","taggers_id":["pk1","pk2"],"tagged_count":12,"taggers_count":2},
              {"label":"biology","taggers_id":[],"tagged_count":3,"taggers_count":1}
            ]
            """.trimIndent(),
        )

        val tags = client.hotTags().getOrThrow()

        assertEquals(expected = 2, actual = tags.size)
        assertEquals("spanish", tags[0].label)
        assertEquals(listOf("pk1", "pk2"), tags[0].taggers_id)
        assertEquals(expected = 12L, actual = tags[0].tagged_count)
        assertEquals(expected = 2L, actual = tags[0].taggers_count)
        assertEquals("biology", tags[1].label)
    }

    @Test
    fun hotTagsToleratesMissingOptionalFields() = runTest {
        http.respond("$BASE/v0/tags/hot?limit=5", """[{"label":"art"}]""")

        val tags = client.hotTags(limit = 5).getOrThrow()

        assertEquals("art", tags.single().label)
        assertEquals(emptyList(), tags.single().taggers_id)
        assertEquals(expected = 0L, actual = tags.single().tagged_count)
    }

    @Test
    fun searchTagsByPrefixParsesStringArray() = runTest {
        http.respond("$BASE/v0/search/tags/by_prefix/spa?limit=10", """["spanish","spark"]""")

        assertEquals(listOf("spanish", "spark"), client.searchTagsByPrefix("spa").getOrThrow())
    }

    @Test
    fun httpFailurePropagatesAsResultFailure() = runTest {
        http.fail("$BASE/v0/tags/hot?limit=20", HttpError(statusCode = 503, message = "unavailable"))

        val result = client.hotTags()

        assertTrue(result.isFailure)
        val error = assertIs<HttpError>(result.exceptionOrNull())
        assertEquals(expected = 503, actual = error.statusCode)
    }

    @Test
    fun malformedJsonIsAFailureNotACrash() = runTest {
        http.respond("$BASE/v0/tags/hot?limit=20", "not json")

        assertTrue(client.hotTags().isFailure)
    }

    private companion object {
        const val BASE = "https://nexus.test"
    }
}
