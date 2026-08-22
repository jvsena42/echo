package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.deckRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * That a library lists **every** deck it holds.
 *
 * The homeserver answers an unpaged `list()` with its `DEFAULT_LIST_LIMIT` of 100 *records*, and a
 * deck contributes a manifest plus one chunk record per 100 cards plus each of its media blobs.
 * Five ordinary imported Anki decks overrun that, and the decks past the boundary silently vanished
 * from the library and from the profile counts while their own detail pages still loaded and still
 * said "IN YOUR LIBRARY".
 */
class DeckRepositoryListingTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val repo = deckRepository(pubky, session, cardRepo, revalidator, RecordingTagRepository())

    // The regression this section exists for: the homeserver's default page is 100 *records*,
    // and a deck contributes a manifest plus one chunk record per 100 cards plus its media. Five
    // ordinary imported decks overrun that, and the decks past the boundary silently vanished
    // from the library while their own detail pages still loaded.

    @Test
    fun listByAuthorSeesEveryDeckWhenTheRecordsOutrunOnePage() = runTest {
        val ids = putDecksSpanningSeveralPages()
        pubky.honoursShallow = false

        val decks = repo.listByAuthor("friendpk")

        assertEquals(ids, decks.map { it.id }.sorted())
    }

    @Test
    fun listByAuthorAsksForOneEntryPerDeckDirectory() = runTest {
        putDecksSpanningSeveralPages()
        pubky.listCalls.clear()

        repo.listByAuthor("friendpk")

        val listings = pubky.listCalls.filter { it.url.endsWith("/decks/") }
        assertEquals(true, listings.first().shallow)
        // A shallow listing is one entry per deck, so five decks fit in a single page.
        assertEquals(1, listings.size)
    }

    @Test
    fun listByAuthorStillSeesEveryDeckWhenTheHomeserverIgnoresShallow() = runTest {
        val ids = putDecksSpanningSeveralPages()
        pubky.honoursShallow = false
        pubky.listCalls.clear()

        val decks = repo.listByAuthor("friendpk")

        assertEquals(ids, decks.map { it.id }.sorted())
        assertTrue(pubky.listCalls.count { it.url.endsWith("/decks/") } > 1)
    }

    @Test
    fun listByAuthorThrowsWhenAPageFails() = runTest {
        putDecksSpanningSeveralPages()
        pubky.honoursShallow = false
        pubky.failListAfterPages = 1

        assertFailsWith<PubkyError> { repo.listByAuthor("friendpk") }
    }

    @Test
    fun listByAuthorReadsDeckIdsFromShallowAndDeepEntriesAlike() = runTest {
        putRemoteManifest(deckId = "alpha", title = "Alpha")
        putRemoteManifest(deckId = "beta", title = "Beta")
        // `beta` also holds a blob, so the shallow entry for it is a bare directory rather than
        // the manifest path a deep listing returns. Both shapes must yield the same id.
        pubky.store["pubky://friendpk/pub/loopky/decks/beta/media/abc.png"] = "blob"
        pubky.store["pubky://friendpk/pub/pubky.app/profile.json"] = "{}"

        val decks = repo.listByAuthor("friendpk")

        assertEquals(listOf("alpha", "beta"), decks.map { it.id }.sorted())
    }

    /**
     * Five decks whose records together overrun one 100-record page, the way five imported Anki
     * decks do. Returns their ids, sorted.
     */
    private fun putDecksSpanningSeveralPages(): List<String> {
        val ids = listOf("alpha", "bravo", "charlie", "delta", "echo")
        ids.forEach { deckId ->
            val root = "pubky://friendpk/pub/loopky/decks/$deckId"
            putRemoteManifest(deckId = deckId, title = deckId)
            repeat(CHUNKS_PER_BIG_DECK) { n -> pubky.store["$root/cards/$n.json"] = "{}" }
            repeat(BLOBS_PER_BIG_DECK) { n -> pubky.store["$root/media/blob$n.png"] = "blob" }
        }
        return ids.sorted()
    }

    private fun putRemoteManifest(deckId: String, title: String) {
        val dto = testDeck(id = deckId, authorPubky = "friendpk", title = title).toDto()
        pubky.store["pubky://friendpk/pub/loopky/decks/$deckId/manifest.json"] =
            loopkyJson.encodeToString(dto)
    }

    private companion object {
        /** Enough per deck that five of them overrun one requested page of records. */
        const val CHUNKS_PER_BIG_DECK = 40
        const val BLOBS_PER_BIG_DECK = 8
    }
}
