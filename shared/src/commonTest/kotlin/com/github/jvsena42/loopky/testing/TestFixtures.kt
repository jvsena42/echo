package com.github.jvsena42.loopky.testing

import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.pubky.MutableSessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.ChunkMeta
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.domain.model.Tag

const val TEST_PUBKY = "ownerpk"

fun fakeSession(pubky: String = TEST_PUBKY, displayName: String? = "Tester"): Session = Session(
    identity = PubkyIdentity(
        pubky = pubky,
        displayName = displayName,
        avatarUrl = null,
        bio = null,
    ),
    sessionSecret = "session-secret-$pubky",
    capabilities = listOf(Capability("/pub/loopky/:rw"), Capability("/pub/pubky.app/:rw")),
    homeserver = "homeserverpk",
)

/** A [MutableSessionProvider] already holding a signed-in session for [pubky]. */
fun signedInProvider(pubky: String = TEST_PUBKY): MutableSessionProvider =
    MutableSessionProvider().apply { set(fakeSession(pubky)) }

/** [SessionRevalidator] that always succeeds and counts invocations. */
class CountingRevalidator(private val pubky: String = TEST_PUBKY) : SessionRevalidator {
    var invocations = 0
        private set

    override suspend fun revalidate(): Result<Session> {
        invocations++
        return Result.success(fakeSession(pubky))
    }
}

/** [HttpFetcher] returning canned responses keyed by exact url. */
class FakeHttpFetcher(
    private val responses: MutableMap<String, Result<String>> = mutableMapOf(),
) : HttpFetcher {
    val requestedUrls = mutableListOf<String>()

    fun respond(url: String, body: String) {
        responses[url] = Result.success(body)
    }

    fun fail(url: String, error: Throwable) {
        responses[url] = Result.failure(error)
    }

    val requestedHeaders = mutableListOf<Map<String, String>>()

    override suspend fun get(url: String, headers: Map<String, String>): Result<String> {
        requestedUrls.add(url)
        requestedHeaders.add(headers)
        return responses[url] ?: Result.failure(IllegalStateException("No canned response for $url"))
    }
}

fun testCard(
    id: String,
    deckId: String = "deck1",
    front: String = "front of $id",
    back: String = "back of $id",
    updatedAt: Long = 1_000L,
    ord: Long = 0L,
): Card = Card(
    id = id,
    deckId = deckId,
    updatedAt = updatedAt,
    front = CardSide(text = front),
    back = CardSide(text = back),
    ord = ord,
)

/** A blob-backed cover image, as a published deck's manifest carries one. */
fun testCoverImage(sha: String = "abc123"): MediaRef.Image = MediaRef.Image(
    path = "media/$sha.png",
    mime = "image/png",
    sha256 = sha,
    width = null,
    height = null,
)

fun testDeck(
    id: String = "deck1",
    authorPubky: String = TEST_PUBKY,
    title: String = "Deck $id",
    tags: List<Tag> = emptyList(),
    cardCount: Int = 0,
    chunks: List<ChunkMeta> = emptyList(),
    createdAt: Long = 1_000L,
    updatedAt: Long = 2_000L,
    coverImageRef: MediaRef.Image? = null,
): Deck = Deck(
    id = id,
    authorPubky = authorPubky,
    title = title,
    description = null,
    coverEmoji = null,
    coverImageRef = coverImageRef,
    tags = tags,
    createdAt = createdAt,
    updatedAt = updatedAt,
    cardCount = cardCount,
    chunks = chunks,
)

/**
 * A deck whose manifest describes [cards] laid out in chunks of [chunkSize], mirroring what
 * `publish` would have written. Use with [seedChunks] to stand up a readable deck in a test.
 */
fun testDeckWithCards(
    cards: List<Card>,
    id: String = "deck1",
    authorPubky: String = TEST_PUBKY,
    chunkSize: Int = 100,
    updatedAt: Long = 2_000L,
): Deck = testDeck(
    id = id,
    authorPubky = authorPubky,
    cardCount = cards.size,
    chunks = cards.chunked(chunkSize).mapIndexed { n, batch ->
        ChunkMeta(n = n, count = batch.size, updatedAt = updatedAt)
    },
    updatedAt = updatedAt,
)
