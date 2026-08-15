package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.nexus.HttpError
import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.TagDto
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.signedInProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TagRepositoryImplTest {

    private val pubky = FakePubkyClient()
    private val http = FakeHttpFetcher()
    private val repo = TagRepositoryImpl(
        pubky = pubky,
        session = signedInProvider(),
        revalidator = CountingRevalidator(),
        nexus = NexusClient(http, baseUrl = NEXUS_BASE),
    )

    private val deckUri = PubkyUri("pubky://$TEST_PUBKY/pub/loopky/decks/deck1/manifest.json")

    private fun tagUrlFor(label: String): String {
        val tagId = pubky.createTagId(deckUri.value, label).getOrThrow()
        return "pubky://$TEST_PUBKY/pub/pubky.app/tags/$tagId"
    }

    // ── putTag ───────────────────────────────────────────────────────────

    @Test
    fun putTagSanitizesLabelAndWritesTagRecord() = runTest {
        val result = repo.putTag(deckUri, Tag("  SPANish "))

        assertTrue(result.isSuccess)
        val body = pubky.store.getValue(tagUrlFor("spanish"))
        val dto = loopkyJson.decodeFromString<TagDto>(body)
        assertEquals(deckUri.value, dto.uri)
        assertEquals("spanish", dto.label)
        assertTrue(dto.created_at > 0)
    }

    @Test
    fun putTagRejectsEmptyLabel() = runTest {
        assertTrue(repo.putTag(deckUri, Tag("")).isFailure)
        assertTrue(repo.putTag(deckUri, Tag("   ")).isFailure)
        assertTrue(pubky.puts.isEmpty())
    }

    @Test
    fun putTagRejectsLabelOverTwentyChars() = runTest {
        val result = repo.putTag(deckUri, Tag("a".repeat(21)))

        assertTrue(result.isFailure)
        assertTrue(pubky.puts.isEmpty())
    }

    @Test
    fun putTagAcceptsLabelAtExactlyTwentyChars() = runTest {
        val result = repo.putTag(deckUri, Tag("a".repeat(20)))

        assertTrue(result.isSuccess)
        assertEquals(expected = 1, actual = pubky.puts.size)
    }

    @Test
    fun putTagRejectsLabelWithInnerWhitespace() = runTest {
        val result = repo.putTag(deckUri, Tag("two words"))

        assertTrue(result.isFailure)
        assertTrue(pubky.puts.isEmpty())
    }

    // ── removeTag ────────────────────────────────────────────────────────

    @Test
    fun removeTagDeletesTheSamePathPutTagWrote() = runTest {
        repo.putTag(deckUri, Tag("Spanish")).getOrThrow()
        val url = tagUrlFor("spanish")
        assertTrue(url in pubky.store)

        repo.removeTag(deckUri, Tag(" spanish ")).getOrThrow()

        assertTrue(url !in pubky.store)
        assertEquals(listOf(url), pubky.deletes)
    }

    @Test
    fun removeTagRejectsInvalidLabel() = runTest {
        assertTrue(repo.removeTag(deckUri, Tag("")).isFailure)
        assertTrue(pubky.deletes.isEmpty())
    }

    // ── trending ─────────────────────────────────────────────────────────

    @Test
    fun trendingMapsNexusHotTags() = runTest {
        http.respond(
            "$NEXUS_BASE/v0/tags/hot?limit=20",
            """
            [
              {"label":"spanish","taggers_id":["pk1"],"tagged_count":12,"taggers_count":4},
              {"label":"biology"}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf(Tag("spanish"), Tag("biology")), repo.trending())
    }

    @Test
    fun trendingReturnsEmptyListOnHttpFailure() = runTest {
        http.fail("$NEXUS_BASE/v0/tags/hot?limit=20", HttpError(statusCode = 500, message = "boom"))

        assertEquals(emptyList(), repo.trending())
    }

    private companion object {
        const val NEXUS_BASE = "https://nexus.test"
    }
}
