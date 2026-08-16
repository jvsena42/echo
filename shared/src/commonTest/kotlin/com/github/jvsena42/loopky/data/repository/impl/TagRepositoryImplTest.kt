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
    private val profileUri = PubkyUri("pubky://$TEST_PUBKY/pub/pubky.app/profile.json")

    /** Deck subjects are indexed as generic resources, so their records live in `pub/loopky`. */
    private fun tagUrlFor(label: String, subject: PubkyUri = deckUri): String {
        val tagId = pubky.createTagId(subject.value, label).getOrThrow()
        return "pubky://$TEST_PUBKY/pub/loopky/tags/$tagId"
    }

    /** Profile subjects must stay in the pubky.app namespace to reach the user graph. */
    private fun pubkyAppTagUrlFor(label: String, subject: PubkyUri): String {
        val tagId = pubky.createTagId(subject.value, label).getOrThrow()
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
    fun putTagOnADeckWritesIntoTheLoopkyNamespace() = runTest {
        // Nexus only indexes a deck manifest subject when the record itself sits outside
        // pubky.app — under pubky.app the watcher drops it silently (#40).
        repo.putTag(deckUri, Tag("spanish")).getOrThrow()

        val url = pubky.puts.single().first
        assertTrue(url.startsWith("pubky://$TEST_PUBKY/pub/loopky/tags/"), "was $url")
    }

    @Test
    fun putTagOnAProfileStaysInThePubkyAppNamespace() = runTest {
        // Profile subjects are the one case that must stay in pubky.app: that is the only
        // namespace Nexus files into the user graph, which is what makes the tag globally
        // queryable by label.
        repo.putTag(profileUri, Tag("teacher")).getOrThrow()

        val url = pubky.puts.single().first
        assertEquals(pubkyAppTagUrlFor("teacher", profileUri), url)
        val dto = loopkyJson.decodeFromString<TagDto>(pubky.store.getValue(url))
        assertEquals(profileUri.value, dto.uri)
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
        assertEquals(url, pubky.deletes.first())
    }

    @Test
    fun removeTagAlsoClearsThePreNamespaceRoutingRecord() = runTest {
        // Deck tags used to be written (and silently never indexed) under pubky.app.
        val legacyUrl = pubkyAppTagUrlFor("spanish", deckUri)
        pubky.store[legacyUrl] = "{}"

        repo.removeTag(deckUri, Tag("spanish")).getOrThrow()

        assertTrue(legacyUrl !in pubky.store)
        assertEquals(listOf(tagUrlFor("spanish"), legacyUrl), pubky.deletes)
    }

    @Test
    fun removeTagOnAProfileTouchesOnlyThePubkyAppRecord() = runTest {
        repo.putTag(profileUri, Tag("teacher")).getOrThrow()

        repo.removeTag(profileUri, Tag("teacher")).getOrThrow()

        assertEquals(listOf(pubkyAppTagUrlFor("teacher", profileUri)), pubky.deletes)
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
