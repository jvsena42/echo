package com.github.jvsena42.echo.data.repository.impl

import com.github.jvsena42.echo.data.pubky.sha256Hex
import com.github.jvsena42.echo.testing.CountingRevalidator
import com.github.jvsena42.echo.testing.FakePubkyClient
import com.github.jvsena42.echo.testing.TEST_PUBKY
import com.github.jvsena42.echo.testing.signedInProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaRepositoryImplTest {

    private val pubky = FakePubkyClient()
    private val repo = MediaRepositoryImpl(
        pubky = pubky,
        session = signedInProvider(),
        revalidator = CountingRevalidator(),
    )

    private val bytes = byteArrayOf(1, 2, 3, 4, 5)
    private val sha = sha256Hex(bytes)

    @Test
    fun putImageStoresRawBytesAtSha256Path() = runTest {
        val ref = repo.putImage("deck1", bytes, "image/png").getOrThrow()

        assertEquals("media/$sha.png", ref.path)
        assertEquals("image/png", ref.mime)
        assertEquals(sha, ref.sha256)
        val (url, stored) = pubky.bytePuts.single()
        assertEquals("pubky://$TEST_PUBKY/pub/echo/decks/deck1/media/$sha.png", url)
        assertContentEquals(bytes, stored)
    }

    @Test
    fun putAudioStoresRawBytesAtSha256Path() = runTest {
        val ref = repo.putAudio("deck1", bytes, "audio/mpeg").getOrThrow()

        assertEquals("media/$sha.mp3", ref.path)
        assertEquals(sha, ref.sha256)
        assertNull(ref.durationMs)
        assertEquals(
            "pubky://$TEST_PUBKY/pub/echo/decks/deck1/media/$sha.mp3",
            pubky.bytePuts.single().first,
        )
    }

    @Test
    fun getDecodesBase64TransportPayloadBackToOriginalBytes() = runTest {
        val ref = repo.putImage("deck1", bytes, "image/jpeg").getOrThrow()

        val fetched = repo.get("deck1", ref).getOrThrow()

        assertContentEquals(bytes, fetched)
    }

    @Test
    fun mimeToExtensionMapping() = runTest {
        suspend fun extFor(mime: String): String =
            repo.putImage("deck1", bytes, mime).getOrThrow().path.substringAfterLast('.')

        assertEquals("jpg", extFor("image/jpeg"))
        assertEquals("jpg", extFor("image/jpg"))
        assertEquals("png", extFor("image/PNG"))
        assertEquals("webp", extFor("image/webp"))
        assertEquals("gif", extFor("image/gif"))
        assertEquals("m4a", extFor("audio/m4a"))
        assertEquals("mp3", extFor("audio/mp3"))
        assertEquals("ogg", extFor("audio/ogg"))
        assertEquals("wav", extFor("audio/wav"))
        // Unknown mimes fall back to the subtype.
        assertEquals("pdf", extFor("application/pdf"))
    }

    @Test
    fun identicalBytesDedupeToTheSameUrl() = runTest {
        repo.putImage("deck1", bytes, "image/png").getOrThrow()
        repo.putImage("deck1", bytes, "image/png").getOrThrow()

        assertEquals(expected = 1, actual = pubky.store.size)
        assertEquals(expected = 2, actual = pubky.bytePuts.size)
    }

    @Test
    fun deleteRemovesTheBlob() = runTest {
        val ref = repo.putImage("deck1", bytes, "image/png").getOrThrow()
        val url = "pubky://$TEST_PUBKY/pub/echo/decks/deck1/media/$sha.png"
        assertTrue(url in pubky.store)

        repo.delete("deck1", ref).getOrThrow()

        assertTrue(url !in pubky.store)
        assertEquals(listOf(url), pubky.deletes)
        assertTrue(repo.get("deck1", ref).isFailure)
    }
}
