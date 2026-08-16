package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.testing.testDeck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PubkyPathsTest {

    // ── isProfileUri ─────────────────────────────────────────────────────

    @Test
    fun recognisesAProfileUri() {
        assertTrue(PubkyPaths.isProfileUri("pubky://$PUBKY/pub/pubky.app/profile.json"))
    }

    @Test
    fun rejectsNonProfileUris() {
        assertTrue(!PubkyPaths.isProfileUri("pubky://$PUBKY/pub/loopky/decks/d1/manifest.json"))
        assertTrue(!PubkyPaths.isProfileUri("pubky://$PUBKY/pub/pubky.app/profile.json/extra"))
        assertTrue(!PubkyPaths.isProfileUri("https://example.com/profile.json"))
        assertTrue(!PubkyPaths.isProfileUri("pubky://"))
    }

    // ── parseDeckManifestUri ─────────────────────────────────────────────

    @Test
    fun parsesAManifestUriBackIntoAuthorAndDeckId() {
        val ref = PubkyPaths.parseDeckManifestUri("pubky://$PUBKY/pub/loopky/decks/deck1/manifest.json")

        assertEquals(DeckRef(authorPubky = PUBKY, deckId = "deck1"), ref)
    }

    @Test
    fun roundTripsWhatDeckPubkyUriBuilds() {
        // Deck.pubkyUri builds the same string literally (domain can't depend on this layer), so
        // the parser has to stay in sync with it.
        val deck = testDeck(id = "deck1", authorPubky = PUBKY)

        assertEquals(
            DeckRef(authorPubky = PUBKY, deckId = "deck1"),
            PubkyPaths.parseDeckManifestUri(deck.pubkyUri.value),
        )
    }

    @Test
    fun rejectsAnythingThatIsNotExactlyAManifest() {
        // These are the forged-tag shapes the global browse verification has to drop (#40).
        listOf(
            "pubky://$PUBKY/pub/loopky/decks/deck1/cards/0.json",
            "pubky://$PUBKY/pub/loopky/decks/deck1",
            "pubky://$PUBKY/pub/loopky/decks//manifest.json",
            "pubky://$PUBKY/pub/loopky/decks/deck1/nested/manifest.json",
            "pubky://$PUBKY/pub/pubky.app/posts/abc",
            "https://example.com/pub/loopky/decks/deck1/manifest.json",
            "pubky:///pub/loopky/decks/deck1/manifest.json",
            "",
        ).forEach { uri -> assertNull(PubkyPaths.parseDeckManifestUri(uri), "should reject $uri") }
    }

    private companion object {
        const val PUBKY = "authorpubky"
    }
}
