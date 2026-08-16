package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.sha256Hex
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.signedInProvider
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
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
        assertEquals("pubky://$TEST_PUBKY/pub/loopky/decks/deck1/media/$sha.png", url)
        assertContentEquals(bytes, stored)
    }

    @Test
    fun putAudioStoresRawBytesAtSha256Path() = runTest {
        val ref = repo.putAudio("deck1", bytes, "audio/mpeg").getOrThrow()

        assertEquals("media/$sha.mp3", ref.path)
        assertEquals(sha, ref.sha256)
        assertNull(ref.durationMs)
        assertEquals(
            "pubky://$TEST_PUBKY/pub/loopky/decks/deck1/media/$sha.mp3",
            pubky.bytePuts.single().first,
        )
    }

    @Test
    fun getDecodesBase64TransportPayloadBackToOriginalBytes() = runTest {
        val ref = repo.putImage("deck1", bytes, "image/jpeg").getOrThrow()

        val fetched = repo.get(TEST_PUBKY, "deck1", ref).getOrThrow()

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
        val url = "pubky://$TEST_PUBKY/pub/loopky/decks/deck1/media/$sha.png"
        assertTrue(url in pubky.store)

        repo.delete("deck1", ref).getOrThrow()

        assertTrue(url !in pubky.store)
        assertEquals(listOf(url), pubky.deletes)
        assertTrue(repo.get(TEST_PUBKY, "deck1", ref).isFailure)
    }

    @Test
    fun getReadsMediaFromAnotherAuthorsDeck() = runTest {
        // The blob lives on someone else's homeserver. This used to resolve against the *session*
        // author, so every followed deck's media was unreadable (#33 blocker 2).
        val ref = MediaRef.Image(
            path = "media/abc.jpg",
            mime = "image/jpeg",
            sha256 = "abc",
            width = null,
            height = null,
        )
        val url = "pubky://friendpk/pub/loopky/decks/deck1/media/abc.jpg"
        pubky.store[url] = Base64.encode(bytes)

        assertContentEquals(bytes, repo.get("friendpk", "deck1", ref).getOrThrow())
    }

    @Test
    fun anAbsoluteRefIsFollowedToItsOrigin() = runTest {
        val origin = "pubky://friendpk/pub/loopky/decks/original/media/abc.jpg"
        pubky.store[origin] = Base64.encode(bytes)
        val ref = MediaRef.Image(
            path = "media/abc.jpg",
            mime = "image/jpeg",
            sha256 = "abc",
            width = null,
            height = null,
            uri = origin,
        )

        // Resolved by uri, not by the deck it is being displayed in — which is what lets a clone
        // reference the original's blobs instead of re-uploading them.
        assertContentEquals(bytes, repo.get(TEST_PUBKY, "my-clone", ref).getOrThrow())
    }

    @Test
    fun rehostCopiesTheBlobUnderTheOwningDeckAndDropsTheUri() = runTest {
        val sha = sha256Hex(bytes)
        val origin = "pubky://friendpk/pub/loopky/decks/original/media/$sha.jpg"
        pubky.store[origin] = Base64.encode(bytes)
        val ref = MediaRef.Image(
            path = "media/$sha.jpg",
            mime = "image/jpeg",
            sha256 = sha,
            width = null,
            height = null,
            uri = origin,
        )

        val rehosted = repo.rehost("my-clone", ref).getOrThrow()

        assertNull(rehosted.uri, "the clone still depends on the origin")
        // The digest is recomputed from the bytes, so the copy is content-addressed under the
        // clone exactly as a fresh upload would be.
        val own = "pubky://$TEST_PUBKY/pub/loopky/decks/my-clone/media/" + sha256Hex(bytes) + ".jpg"
        assertTrue(own in pubky.store, "blob not copied under the clone: " + pubky.store.keys)
    }

    @Test
    fun getServesARepeatedBlobFromMemory() = runTest {
        val ref = repo.putImage("deck1", bytes, "image/jpeg").getOrThrow()
        repo.get(TEST_PUBKY, "deck1", ref).getOrThrow()
        val gets = pubky.gets.size

        repo.get(TEST_PUBKY, "deck1", ref).getOrThrow()

        // A card's image must not re-download on every recomposition.
        assertEquals(gets, pubky.gets.size)
    }
}
