package com.github.jvsena42.echo.data.pubky

import com.github.jvsena42.echo.data.repository.impl.echoJson
import com.github.jvsena42.echo.domain.model.CardIndexEntry
import com.github.jvsena42.echo.domain.model.Deck
import com.github.jvsena42.echo.domain.model.MediaRef
import com.github.jvsena42.echo.domain.model.Tag
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckDtosTest {

    private fun deck(
        listenEnabled: Boolean = true,
        speakEnabled: Boolean = true,
        cover: MediaRef.Image? = null,
    ) = Deck(
        id = "deck1",
        authorPubky = "pk:author",
        title = "Spanish Basics",
        description = "Greetings",
        coverEmoji = "🇪🇸",
        coverImageRef = cover,
        tags = listOf(Tag("spanish"), Tag("a1")),
        createdAt = 1_739_000_000_000L,
        updatedAt = 1_739_000_500_000L,
        cardIndex = listOf(CardIndexEntry("c1", 1L)),
        listenEnabled = listenEnabled,
        speakEnabled = speakEnabled,
    )

    @Test
    fun manifestRoundTripPreservesOptions() {
        val deck = deck(listenEnabled = false, speakEnabled = true)
        val json = echoJson.encodeToString(deck.toDto())
        val back = echoJson.decodeFromString<ManifestDto>(json).toDomain()
        assertFalse(back.listenEnabled)
        assertTrue(back.speakEnabled)
    }

    @Test
    fun legacyManifestWithoutOptionsDefaultsToEnabled() {
        // A manifest written before listen/speak existed.
        val legacy = """
            {"schema_version":1,"deck_id":"d","author_pubky":"pk","title":"T",
             "created_at":1,"updated_at":2,"cards":[]}
        """.trimIndent()
        val back = echoJson.decodeFromString<ManifestDto>(legacy).toDomain()
        assertTrue(back.listenEnabled)
        assertTrue(back.speakEnabled)
    }

    @Test
    fun webCoverImageRoundTripsWithUrlAndNoBlob() {
        val cover = MediaRef.Image(
            path = "",
            mime = "image/jpeg",
            sha256 = "",
            width = null,
            height = null,
            url = "https://images.unsplash.com/photo-1.jpg",
        )
        val back = echoJson.decodeFromString<ManifestDto>(
            echoJson.encodeToString(deck(cover = cover).toDto()),
        ).toDomain()
        val image = back.coverImageRef!!
        assertTrue(image.isRemote)
        assertEquals("https://images.unsplash.com/photo-1.jpg", image.url)
        assertEquals("", image.sha256)
    }

    @Test
    fun blobCoverImageHasNoUrl() {
        val cover = MediaRef.Image(
            path = "media/abc.jpg",
            mime = "image/jpeg",
            sha256 = "abc",
            width = 512,
            height = 512,
        )
        val back = echoJson.decodeFromString<ManifestDto>(
            echoJson.encodeToString(deck(cover = cover).toDto()),
        ).toDomain()
        val image = back.coverImageRef!!
        assertFalse(image.isRemote)
        assertNull(image.url)
        assertEquals("abc", image.sha256)
    }
}
