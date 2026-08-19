package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.deckRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tag records are separate records from the deck manifest, so a manifest write on its own changes
 * nothing an indexer can see. Every path that can change a deck's tag list therefore has to
 * reconcile the records too — including the metadata-only save a tag edit actually takes (#47),
 * which wrote the manifest and stopped.
 */
class DeckRepositoryTagSyncTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val tagRepo = RecordingTagRepository()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator)
    private val repo = deckRepository(pubky, session, cardRepo, revalidator, tagRepo)

    @Test
    fun updateMetadataMirrorsANewlyAddedTag() = runTest {
        // A tag-only edit takes the metadata-only path, so this is where the tag record has to be
        // written — the manifest alone is not what Nexus indexes (#47).
        val deck = repo.publish(testDeck(id = "deck1"), listOf(testCard("c1"))).getOrThrow()
        tagRepo.putTags.clear()

        repo.updateMetadata(deck.copy(tags = listOf(Tag("spanish")))).getOrThrow()

        assertEquals(listOf(deck.pubkyUri to Tag("spanish")), tagRepo.putTags)
    }

    @Test
    fun updateMetadataRemovesTheRecordOfADroppedTag() = runTest {
        val deck = testDeck(id = "deck1", tags = listOf(Tag("spanish"), Tag("language")))
        repo.publish(deck, listOf(testCard("c1"))).getOrThrow()

        repo.updateMetadata(deck.copy(tags = listOf(Tag("spanish")))).getOrThrow()

        // Left behind, the dropped label keeps the deck listed under a topic it no longer carries.
        assertEquals(listOf(deck.pubkyUri to Tag("language")), tagRepo.removedTags)
        assertEquals(listOf(Tag("spanish")), repo.getLocal("deck1")?.tags)
    }

    @Test
    fun updateMetadataKeepsTheDeckMarkedForGlobalBrowse() = runTest {
        val deck = repo.publish(testDeck(id = "deck1"), listOf(testCard("c1"))).getOrThrow()

        repo.updateMetadata(deck.copy(title = "Renamed")).getOrThrow()

        // Idempotent, and it re-asserts the marker for a deck published before #40 added it.
        assertEquals(emptyList(), tagRepo.removedReservedTags)
        assertEquals(
            listOf(deck.pubkyUri to ReservedTags.DECK, deck.pubkyUri to ReservedTags.DECK),
            tagRepo.putReservedTags,
        )
    }

    @Test
    fun updateMetadataSurvivesAFailedTagWrite() = runTest {
        val deck = repo.publish(testDeck(id = "deck1"), listOf(testCard("c1"))).getOrThrow()
        tagRepo.failWith = IllegalStateException("homeserver refused the tag")

        // Same rule as publish: discoverability never fails the save the user asked for.
        assertTrue(repo.updateMetadata(deck.copy(tags = listOf(Tag("spanish")))).isSuccess)
    }

    @Test
    fun republishingDropsTheTagRecordsThatFellAway() = runTest {
        val deck = testDeck(id = "deck1", tags = listOf(Tag("spanish"), Tag("language")))
        repo.publish(deck, listOf(testCard("c1"))).getOrThrow()

        // A card edit alongside a tag edit goes down the publish path instead — same staleness.
        repo.publish(deck.copy(tags = listOf(Tag("spanish"))), listOf(testCard("c2"))).getOrThrow()

        assertEquals(listOf(deck.pubkyUri to Tag("language")), tagRepo.removedTags)
    }
}
