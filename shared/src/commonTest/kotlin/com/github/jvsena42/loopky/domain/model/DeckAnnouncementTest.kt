package com.github.jvsena42.loopky.domain.model

import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.testCoverImage
import com.github.jvsena42.loopky.testing.testDeck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckAnnouncementTest {

    @Test
    fun `created announcement names the deck and links its manifest`() {
        val deck = testDeck(id = "d1", title = "Kanji N5")
        val content = DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created).content

        assertTrue(content.startsWith("📚 I published a new deck on Loopky: Kanji N5"), content)
        assertTrue(content.endsWith("pubky://$TEST_PUBKY/pub/loopky/decks/d1/manifest.json"), content)
    }

    @Test
    fun `follow and clone credit the original author`() {
        val deck = testDeck(title = "Kanji N5")
        val followed = DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Followed, "Ada").content
        val cloned = DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Cloned, "Ada").content

        assertTrue(followed.contains("Now following the Loopky deck Kanji N5 by Ada"), followed)
        assertTrue(cloned.contains("Cloned the Loopky deck Kanji N5 by Ada into my library"), cloned)
    }

    @Test
    fun `an unresolved author is omitted rather than printed as a raw key`() {
        val deck = testDeck(title = "Kanji N5")
        val content = DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Followed, authorName = "  ").content

        assertTrue(content.contains("deck Kanji N5\n"), content)
        assertTrue(!content.contains(" by "), content)
    }

    @Test
    fun `the cover emoji opens the post`() {
        val deck = testDeck(title = "Kanji N5").copy(coverEmoji = "🇯🇵")
        assertTrue(
            DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created).content.startsWith("🇯🇵 "),
        )
    }

    @Test
    fun `a homeserver cover is attached as an absolute pubky url`() {
        val deck = testDeck(id = "d1", coverImageRef = testCoverImage(sha = "cafe"))
        assertEquals(
            "pubky://$TEST_PUBKY/pub/loopky/decks/d1/media/cafe.png",
            DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created).coverUrl,
        )
    }

    @Test
    fun `a web cover is attached by its own url`() {
        val deck = testDeck(
            coverImageRef = testCoverImage().copy(path = "", sha256 = "", url = "https://img.test/c.jpg"),
        )
        assertEquals(
            "https://img.test/c.jpg",
            DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created).coverUrl,
        )
    }

    @Test
    fun `a cloned cover keeps the origin uri it is pinned to`() {
        val origin = "pubky://otherpk/pub/loopky/decks/src/media/cafe.png"
        val deck = testDeck(coverImageRef = testCoverImage(sha = "cafe").copy(uri = origin))
        assertEquals(origin, DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created).coverUrl)
    }

    @Test
    fun `no cover means no attachment`() {
        assertNull(DeckAnnouncement.of(testDeck(), DeckAnnouncement.Kind.Created).coverUrl)
    }

    @Test
    fun `an over-long cover url is dropped rather than failing the post`() {
        // pubky-app-specs rejects the whole post over post_attachment_url_max_length, and a
        // rejected post is written and then silently never indexed.
        val long = "https://img.test/" + "q".repeat(200)
        val deck = testDeck(coverImageRef = testCoverImage().copy(path = "", sha256 = "", url = long))
        assertNull(DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created).coverUrl)
    }

    @Test
    fun `a foreign title is truncated to stay inside the content limit`() {
        val deck = testDeck(title = "x".repeat(500))
        val content = DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Followed, "y".repeat(200)).content

        assertTrue(content.contains("…"), content)
        assertTrue(content.length < SHORT_CONTENT_LIMIT, "was ${content.length}")
    }

    private companion object {
        /** `post_short_content_max_length` in pubky-app-specs. */
        const val SHORT_CONTENT_LIMIT = 2_000
    }
}
