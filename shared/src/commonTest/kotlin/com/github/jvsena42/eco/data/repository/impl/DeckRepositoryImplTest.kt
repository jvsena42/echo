package com.github.jvsena42.eco.data.repository.impl

import com.github.jvsena42.eco.data.pubky.ManifestDto
import com.github.jvsena42.eco.data.pubky.toDto
import com.github.jvsena42.eco.domain.model.CardSide
import com.github.jvsena42.eco.domain.model.Tag
import com.github.jvsena42.eco.testing.CountingRevalidator
import com.github.jvsena42.eco.testing.FakePubkyClient
import com.github.jvsena42.eco.testing.RecordingTagRepository
import com.github.jvsena42.eco.testing.TEST_PUBKY
import com.github.jvsena42.eco.testing.signedInProvider
import com.github.jvsena42.eco.testing.testCard
import com.github.jvsena42.eco.testing.testDeck
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckRepositoryImplTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val tagRepo = RecordingTagRepository()
    private val repo = DeckRepositoryImpl(
        pubky = pubky,
        session = session,
        cardRepo = CardRepositoryImpl(pubky, session, revalidator),
        revalidator = revalidator,
        tagRepo = tagRepo,
    )

    private val deckRoot = "pubky://$TEST_PUBKY/pub/echo/decks/deck1"

    // ── publish ──────────────────────────────────────────────────────────

    @Test
    fun publishWritesOneRecordPerCardPlusManifest() = runTest {
        val cards = listOf(testCard("c1", updatedAt = 10L), testCard("c2", updatedAt = 20L))

        val published = repo.publish(testDeck(id = "deck1"), cards).getOrThrow()

        assertTrue("$deckRoot/cards/c1.json" in pubky.store)
        assertTrue("$deckRoot/cards/c2.json" in pubky.store)
        val manifest = echoJson.decodeFromString<ManifestDto>(
            pubky.store.getValue("$deckRoot/manifest.json"),
        )
        assertEquals("deck1", manifest.deck_id)
        assertEquals(TEST_PUBKY, manifest.author_pubky)
        assertEquals(listOf("c1", "c2"), manifest.cards.map { it.id })
        assertEquals(listOf(10L, 20L), manifest.cards.map { it.updated_at })
        assertEquals(listOf("c1", "c2"), published.cardIndex.map { it.id })
    }

    @Test
    fun publishMirrorsDeckTagsAsTagRecords() = runTest {
        val deck = testDeck(id = "deck1", tags = listOf(Tag("spanish"), Tag("language")))

        repo.publish(deck, listOf(testCard("c1"))).getOrThrow()

        assertEquals(
            listOf(deck.pubkyUri to Tag("spanish"), deck.pubkyUri to Tag("language")),
            tagRepo.putTags,
        )
    }

    @Test
    fun publishRejectsAuthorMismatch() = runTest {
        val foreign = testDeck(id = "deck1", authorPubky = "someone-else")

        val result = repo.publish(foreign, listOf(testCard("c1")))

        assertTrue(result.isFailure)
        assertTrue(pubky.puts.isEmpty())
    }

    @Test
    fun publishRejectsCardsWithAnEmptySide() = runTest {
        val blankBack = testCard("c1").copy(back = CardSide(text = "  "))

        val result = repo.publish(testDeck(id = "deck1"), listOf(blankBack))

        assertTrue(result.isFailure)
        assertTrue(pubky.puts.isEmpty())
    }

    // ── delete ───────────────────────────────────────────────────────────

    @Test
    fun deleteSweepsEverythingUnderTheDeckRootManifestLast() = runTest {
        val deck = testDeck(id = "deck1", tags = listOf(Tag("spanish")))
        repo.publish(deck, listOf(testCard("c1"), testCard("c2"))).getOrThrow()
        // An SRS record under the deck root must be swept too.
        pubky.store["$deckRoot/srs/c1.json"] = "{}"

        repo.delete("deck1").getOrThrow()

        assertTrue(pubky.store.keys.none { it.startsWith(deckRoot) })
        assertEquals("$deckRoot/manifest.json", pubky.deletes.last())
        assertTrue(pubky.deletes.containsAll(listOf("$deckRoot/cards/c1.json", "$deckRoot/srs/c1.json")))
        assertEquals(listOf(deck.pubkyUri to Tag("spanish")), tagRepo.removedTags)
        assertNull(repo.getLocal("deck1"))
    }

    // ── listByAuthor / fetchRemote / cache ───────────────────────────────

    @Test
    fun listByAuthorParsesDeckIdsFromTheListPayload() = runTest {
        putRemoteManifest(author = "friendpk", deckId = "alpha", title = "Alpha")
        putRemoteManifest(author = "friendpk", deckId = "beta", title = "Beta")
        // A record from another namespace must not leak in.
        pubky.store["pubky://friendpk/pub/pubky.app/profile.json"] = "{}"

        val decks = repo.listByAuthor("friendpk")

        assertEquals(listOf("alpha", "beta"), decks.map { it.id }.sorted())
        assertEquals(listOf("Alpha", "Beta"), decks.map { it.title }.sorted())
    }

    @Test
    fun listOwnedReturnsEmptyWhenSignedOut() = runTest {
        session.set(null)

        assertEquals(emptyList(), repo.listOwned())
    }

    @Test
    fun fetchRemotePopulatesTheLocalCache() = runTest {
        putRemoteManifest(author = "friendpk", deckId = "alpha", title = "Alpha")
        assertNull(repo.getLocal("alpha"))

        val fetched = repo.fetchRemote("friendpk", "alpha").getOrThrow()

        assertEquals(fetched, repo.getLocal("alpha"))
        assertEquals("Alpha", assertNotNull(repo.getLocal("alpha")).title)
    }

    @Test
    fun fetchRemoteFailsForMissingDeck() = runTest {
        assertTrue(repo.fetchRemote("friendpk", "nope").isFailure)
    }

    private fun putRemoteManifest(author: String, deckId: String, title: String) {
        val dto = testDeck(id = deckId, authorPubky = author, title = title).toDto()
        pubky.store["pubky://$author/pub/echo/decks/$deckId/manifest.json"] =
            echoJson.encodeToString(dto)
    }
}
