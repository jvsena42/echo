package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CardChunkDto
import com.github.jvsena42.loopky.data.pubky.ManifestDto
import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.PublishProgress
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FailingChunkCardRepository
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    private val deckRoot = "pubky://$TEST_PUBKY/pub/loopky/decks/deck1"

    // ── publish ──────────────────────────────────────────────────────────

    @Test
    fun publishWritesOneChunkRecordPlusManifest() = runTest {
        val cards = listOf(testCard("c1", updatedAt = 10L), testCard("c2", updatedAt = 20L))

        val published = repo.publish(testDeck(id = "deck1"), cards).getOrThrow()

        // Two cards, one chunk record — not one record per card.
        assertTrue("$deckRoot/cards/0.json" in pubky.store)
        // claim manifest + chunk + settled manifest — still not one record per card.
        assertEquals(expected = 3, actual = pubky.puts.size)

        val manifest = loopkyJson.decodeFromString<ManifestDto>(
            pubky.store.getValue("$deckRoot/manifest.json"),
        )
        assertEquals("deck1", manifest.deck_id)
        assertEquals(TEST_PUBKY, manifest.author_pubky)
        assertEquals(expected = 2, actual = manifest.card_count)
        assertEquals(listOf(0), manifest.chunks.map { it.n })
        assertEquals(listOf(2), manifest.chunks.map { it.count })
        assertEquals(expected = 2, actual = published.cardCount)
    }

    @Test
    fun publishSplitsCardsAcrossChunksAtTheBoundary() = runTest {
        val cards = (1..250).map { testCard("c$it") }

        val published = repo.publish(testDeck(id = "deck1"), cards).getOrThrow()

        val manifest = loopkyJson.decodeFromString<ManifestDto>(
            pubky.store.getValue("$deckRoot/manifest.json"),
        )
        assertEquals(listOf(0, 1, 2), manifest.chunks.map { it.n })
        assertEquals(listOf(100, 100, 50), manifest.chunks.map { it.count })
        assertEquals(expected = 250, actual = manifest.card_count)
        assertEquals(expected = 250, actual = published.cardCount)
        // 3 chunks + 2 manifest writes (claim, then settle), against 251 under the old layout.
        assertEquals(expected = 5, actual = pubky.puts.size)
    }

    @Test
    fun publishAssignsSparseStudyOrder() = runTest {
        val cards = listOf(testCard("c1"), testCard("c2"), testCard("c3"))

        repo.publish(testDeck(id = "deck1"), cards).getOrThrow()

        val chunk = loopkyJson.decodeFromString<CardChunkDto>(
            pubky.store.getValue("$deckRoot/cards/0.json"),
        )
        assertEquals(listOf(0L, 1000L, 2000L), chunk.cards.map { it.ord })
    }

    @Test
    fun republishingASmallerDeckRemovesTheChunksThatFellAway() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") }).getOrThrow()

        repo.publish(testDeck(id = "deck1"), listOf(testCard("c1"))).getOrThrow()

        assertTrue("$deckRoot/cards/0.json" in pubky.store)
        assertTrue("$deckRoot/cards/1.json" !in pubky.store, "stale chunk 1 left behind")
        assertTrue("$deckRoot/cards/2.json" !in pubky.store, "stale chunk 2 left behind")
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

    @Test
    fun publishClaimsTheDeckWithAMarkerManifestBeforeUploadingCards() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") }).getOrThrow()

        // Manifest first (marked incomplete), chunks, then the manifest again to clear the mark.
        assertEquals("$deckRoot/manifest.json", pubky.puts.first().first)
        assertEquals("$deckRoot/manifest.json", pubky.puts.last().first)

        val marker = loopkyJson.decodeFromString<ManifestDto>(pubky.puts.first().second)
        assertTrue(marker.incomplete, "the claim manifest was not marked incomplete")
        val settled = loopkyJson.decodeFromString<ManifestDto>(
            pubky.store.getValue("$deckRoot/manifest.json"),
        )
        assertFalse(settled.incomplete, "the mark was not cleared once the chunks were up")
    }

    @Test
    fun aPublishThatDiesPartWayLeavesTheDeckListableSoItCanBeDeleted() = runTest {
        val cardRepo = FailingChunkCardRepository(CardRepositoryImpl(pubky, session, revalidator))
        val failing = DeckRepositoryImpl(pubky, session, cardRepo, revalidator, tagRepo)

        val result = failing.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") })

        assertTrue(result.isFailure)
        // The old order wrote the manifest last, so a failure here left orphaned chunk records
        // under a deck root that listByAuthor could not see — unreachable and undeletable.
        val manifest = pubky.store["$deckRoot/manifest.json"]
        assertNotNull(manifest, "an interrupted publish left no manifest, orphaning its chunks")
        assertTrue(loopkyJson.decodeFromString<ManifestDto>(manifest).incomplete)
        assertEquals(listOf("deck1"), repo.listByAuthor(TEST_PUBKY).map { it.id })
    }

    @Test
    fun publishReportsProgressEndingAtDone() = runTest {
        val seen = mutableListOf<PublishProgress>()

        repo.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") }) { seen.add(it) }
            .getOrThrow()

        assertTrue(seen.isNotEmpty())
        assertTrue(seen.last().done)
        assertEquals(1f, seen.last().fraction)
        assertEquals(expected = 250, actual = seen.last().cardsWritten)
        // Every chunk reports exactly once, so the counter never loses an increment.
        assertEquals(listOf(1, 2, 3), seen.filterNot { it.done }.map { it.chunksWritten }.filter { it > 0 })
    }

    @Test
    fun deleteSweepsPastTheFirstListingPage() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") }).getOrThrow()
        pubky.listPageSize = 2 // force the sweep to page

        repo.delete("deck1").getOrThrow()

        assertTrue(
            pubky.store.keys.none { it.startsWith(deckRoot) },
            "records survived the sweep: ${pubky.store.keys.filter { it.startsWith(deckRoot) }}",
        )
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
        assertTrue(pubky.deletes.containsAll(listOf("$deckRoot/cards/0.json", "$deckRoot/srs/c1.json")))
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

    // An unreachable homeserver must never look like an empty library: Pubky is the only
    // source of truth, so "no decks" would read to the user as "my decks are gone".

    @Test
    fun listByAuthorThrowsWhenTheHomeserverIsUnreachable() = runTest {
        pubky.failListWith = PubkyError("HTTP transport error: error sending request for url (...)")

        assertFailsWith<PubkyError> { repo.listByAuthor("friendpk") }
    }

    @Test
    fun listByAuthorReturnsEmptyWhenNothingHasBeenPublished() = runTest {
        pubky.failListWith = PubkyError("not found: pubky://friendpk/pub/loopky/decks/")

        assertEquals(emptyList(), repo.listByAuthor("friendpk"))
    }

    @Test
    fun listByAuthorThrowsWhenTheListingHasDecksButNoneCanBeRead() = runTest {
        putRemoteManifest(author = "friendpk", deckId = "alpha", title = "Alpha")
        pubky.failGetWith = PubkyError("HTTP transport error: error sending request for url (...)")

        assertFailsWith<PubkyError> { repo.listByAuthor("friendpk") }
    }

    @Test
    fun listByAuthorStillReturnsTheDecksItCanRead() = runTest {
        putRemoteManifest(author = "friendpk", deckId = "alpha", title = "Alpha")
        // A listed deck whose manifest is missing must not hide the readable one.
        pubky.store["pubky://friendpk/pub/loopky/decks/broken/manifest.json"] = "{ not json"

        val decks = repo.listByAuthor("friendpk")

        assertEquals(listOf("alpha"), decks.map { it.id })
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

    // ── sync ─────────────────────────────────────────────────────────────

    @Test
    fun syncPullsTheCardsIntoTheCacheWithoutWritingThemBack() = runTest {
        val cards = listOf(testCard("c1", updatedAt = 10L), testCard("c2", updatedAt = 20L))
        repo.publish(testDeck(id = "deck1"), cards).getOrThrow()

        // A fresh repo pair over the same store: the manifest and card records are on the
        // homeserver, nothing is cached.
        val coldCardRepo = CardRepositoryImpl(pubky, session, revalidator)
        val coldRepo = DeckRepositoryImpl(pubky, session, coldCardRepo, revalidator, tagRepo)
        pubky.puts.clear()

        coldRepo.sync("deck1").getOrThrow()

        // publish() stamps a sparse ord onto each card, so compare identity and order.
        assertEquals(cards.map { it.id }, coldCardRepo.listByDeck("deck1").map { it.id })
        // sync used to re-`upsert` every card it had just downloaded, PUTting each one straight
        // back to the homeserver it came from.
        assertTrue(pubky.puts.isEmpty(), "sync wrote ${pubky.puts.map { it.first }}")
    }

    @Test
    fun syncDropsCardsTheDeckNoLongerContains() = runTest {
        val cardRepo = CardRepositoryImpl(pubky, session, revalidator)
        val repoWithCards = DeckRepositoryImpl(pubky, session, cardRepo, revalidator, tagRepo)
        repoWithCards.publish(
            testDeck(id = "deck1"),
            listOf(testCard("c1"), testCard("c2")),
        ).getOrThrow()
        assertEquals(listOf("c1", "c2"), cardRepo.listByDeck("deck1").map { it.id })

        // The deck is republished without c2, as an edit that removed a card would leave it.
        repoWithCards.publish(
            testDeck(id = "deck1", updatedAt = 9_000L),
            listOf(testCard("c1")),
        ).getOrThrow()
        repoWithCards.sync("deck1").getOrThrow()

        assertEquals(listOf("c1"), cardRepo.listByDeck("deck1").map { it.id })
    }

    // ── single-card writes ───────────────────────────────────────────────

    @Test
    fun upsertCardRewritesOnlyItsChunkAndBumpsTheManifest() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") }).getOrThrow()
        pubky.puts.clear()

        val edited = testCard("c5", front = "edited", updatedAt = 7_000L)
        val deck = repo.upsertCard("deck1", edited).getOrThrow()

        // Exactly two writes: the one chunk holding c5, and the manifest.
        assertEquals(
            listOf("$deckRoot/cards/0.json", "$deckRoot/manifest.json"),
            pubky.puts.map { it.first },
        )
        assertEquals(expected = 250, actual = deck.cardCount, message = "an edit changed the count")

        val chunk = loopkyJson.decodeFromString<CardChunkDto>(
            pubky.store.getValue("$deckRoot/cards/0.json"),
        )
        assertEquals("edited", chunk.cards.single { it.id == "c5" }.front.text)
        assertEquals(expected = 100, actual = chunk.cards.size)
    }

    @Test
    fun editingACardReadsOnlyTheChunkThatHoldsIt() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..500).map { testCard("c$it") }).getOrThrow()
        pubky.gets.clear()

        // c450 lives in chunk 4. Locating it must not walk chunks 0..3 on the way.
        repo.upsertCard("deck1", testCard("c450", front = "edited")).getOrThrow()

        assertEquals(
            listOf("$deckRoot/cards/4.json"),
            pubky.gets,
            "a single-card edit read more than its own chunk",
        )
    }

    @Test
    fun upsertCardAppendsANewCardToTheLastChunkWithRoom() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..150).map { testCard("c$it") }).getOrThrow()

        val deck = repo.upsertCard("deck1", testCard("brand-new")).getOrThrow()

        assertEquals(expected = 151, actual = deck.cardCount)
        assertEquals(listOf(100, 51), deck.chunks.map { it.count })
        val chunk = loopkyJson.decodeFromString<CardChunkDto>(
            pubky.store.getValue("$deckRoot/cards/1.json"),
        )
        assertTrue(chunk.cards.any { it.id == "brand-new" })
    }

    @Test
    fun upsertCardOpensANewChunkWhenTheLastOneIsFull() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..100).map { testCard("c$it") }).getOrThrow()

        val deck = repo.upsertCard("deck1", testCard("overflow")).getOrThrow()

        assertEquals(listOf(0, 1), deck.chunks.map { it.n })
        assertEquals(listOf(100, 1), deck.chunks.map { it.count })
        assertEquals(expected = 101, actual = deck.cardCount)
    }

    @Test
    fun deleteCardLeavesAHoleRatherThanResequencingTheDeck() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") }).getOrThrow()
        pubky.puts.clear()

        val deck = repo.deleteCard("deck1", "c5").getOrThrow()

        // Only the affected chunk shrinks; later chunks are untouched.
        assertEquals(listOf(99, 100, 50), deck.chunks.map { it.count })
        assertEquals(expected = 249, actual = deck.cardCount)
        assertEquals(
            listOf("$deckRoot/cards/0.json", "$deckRoot/manifest.json"),
            pubky.puts.map { it.first },
        )
    }

    @Test
    fun deletingTheLastCardInAChunkDropsTheChunkRecord() = runTest {
        repo.publish(testDeck(id = "deck1"), listOf(testCard("only"))).getOrThrow()

        val deck = repo.deleteCard("deck1", "only").getOrThrow()

        assertEquals(emptyList(), deck.chunks)
        assertEquals(expected = 0, actual = deck.cardCount)
        assertTrue("$deckRoot/cards/0.json" !in pubky.store)
    }

    @Test
    fun upsertCardRejectsADeckYouDoNotOwn() = runTest {
        putRemoteManifest("friendpk", "foreign", "Someone else's")

        assertTrue(repo.upsertCard("foreign", testCard("c1")).isFailure)
    }

    private fun putRemoteManifest(author: String, deckId: String, title: String) {
        val dto = testDeck(id = deckId, authorPubky = author, title = title).toDto()
        pubky.store["pubky://$author/pub/loopky/decks/$deckId/manifest.json"] =
            loopkyJson.encodeToString(dto)
    }
}
