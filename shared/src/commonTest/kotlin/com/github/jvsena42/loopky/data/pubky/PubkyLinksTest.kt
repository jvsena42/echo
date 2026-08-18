package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.testing.testDeck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PubkyLinksTest {

    // ── profiles ──────────────────────────────────────────────────────

    @Test
    fun parsesABarePubkyUri() {
        assertEquals(PubkyLink.Profile(PUBKY), PubkyLinks.parse("pubky://$PUBKY"))
        assertEquals(PubkyLink.Profile(PUBKY), PubkyLinks.parse("pubky://$PUBKY/"))
    }

    @Test
    fun parsesTheProfileRecordItself() {
        assertEquals(
            PubkyLink.Profile(PUBKY),
            PubkyLinks.parse("pubky://$PUBKY/pub/pubky.app/profile.json"),
        )
    }

    @Test
    fun parsesAPubkyOnItsOwn() {
        assertEquals(PubkyLink.Profile(PUBKY), PubkyLinks.parse(PUBKY))
        assertEquals(PubkyLink.Profile(PUBKY), PubkyLinks.parse("pk:$PUBKY"))
    }

    @Test
    fun fallsBackToTheOwnerForAPathItDoesNotKnow() {
        // The URI still names a person, and landing on them beats a tap that does nothing.
        assertEquals(
            PubkyLink.Profile(PUBKY),
            PubkyLinks.parse("pubky://$PUBKY/pub/pubky.app/posts/abc"),
        )
    }

    // ── decks ─────────────────────────────────────────────────────────

    @Test
    fun parsesTheDeckUriThatSharingProduces() {
        val deck = testDeck(id = "deck1", authorPubky = PUBKY)

        assertEquals(PubkyLink.Deck(PUBKY, "deck1"), PubkyLinks.parse(deck.pubkyUri.value))
    }

    @Test
    fun parsesAnythingUnderTheDeckRootAsThatDeck() {
        listOf(
            "pubky://$PUBKY/pub/loopky/decks/deck1",
            "pubky://$PUBKY/pub/loopky/decks/deck1/",
            "pubky://$PUBKY/pub/loopky/decks/deck1/cards/0.json",
            "pubky://$PUBKY/pub/loopky/decks/deck1/media/abc.png",
        ).forEach { uri ->
            assertEquals(PubkyLink.Deck(PUBKY, "deck1"), PubkyLinks.parse(uri), "for $uri")
        }
    }

    @Test
    fun treatsTheDeckListingAsTheAuthorsProfile() {
        assertEquals(
            PubkyLink.Profile(PUBKY),
            PubkyLinks.parse("pubky://$PUBKY/pub/loopky/decks/"),
        )
    }

    // ── text around the link ──────────────────────────────────────────

    @Test
    fun findsTheLinkInsideTheSharedMessage() {
        // What `share_deck_body` puts on the clipboard, pasted whole into the add-friend sheet.
        val shared = "Spanish Verbs on Loopky\npubky://$PUBKY/pub/loopky/decks/deck1/manifest.json"

        assertEquals(PubkyLink.Deck(PUBKY, "deck1"), PubkyLinks.parse(shared))
    }

    @Test
    fun dropsPunctuationThatTrailsALinkInASentence() {
        assertEquals(
            PubkyLink.Profile(PUBKY),
            PubkyLinks.parse("follow me at pubky://$PUBKY, it is worth it!"),
        )
    }

    @Test
    fun findsABarePubkyInsideText() {
        assertEquals(PubkyLink.Profile(PUBKY), PubkyLinks.parse("my key is $PUBKY thanks"))
    }

    // ── rejections ────────────────────────────────────────────────────

    @Test
    fun rejectsWhatIsNotAnAddress() {
        listOf(
            "",
            "   ",
            "hello world",
            "https://example.com/deck",
            "pubky://",
            // Too short to be a key, and with no scheme vouching for it.
            "authorpubky",
        ).forEach { input -> assertNull(PubkyLinks.parse(input), "should reject '$input'") }
    }

    @Test
    fun knowsWhatAPubkyLooksLike() {
        assertTrue(PubkyLinks.isPubky(PUBKY))
        assertFalse(PubkyLinks.isPubky(PUBKY.dropLast(1)))
        // 'l', 'v', '0' and '2' are not in the z-base-32 alphabet.
        assertFalse(PubkyLinks.isPubky("l" + PUBKY.drop(1)))
    }

    @Test
    fun buildsTheProfileUriItCanParseBack() {
        assertEquals(PubkyLink.Profile(PUBKY), PubkyLinks.parse(PubkyLinks.profileUri(PUBKY)))
    }

    private companion object {
        /** 52 z-base-32 characters — the shape a real pubky has. */
        const val PUBKY = "o1gg96ewuojmopcjbz8895478wdtxtzzuxnfjjz8o8e77csa1ngo"
    }
}
