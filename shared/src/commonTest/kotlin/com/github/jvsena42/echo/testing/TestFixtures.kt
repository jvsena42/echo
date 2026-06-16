package com.github.jvsena42.echo.testing

import com.github.jvsena42.echo.data.nexus.HttpFetcher
import com.github.jvsena42.echo.data.pubky.MutableSessionProvider
import com.github.jvsena42.echo.data.pubky.SessionRevalidator
import com.github.jvsena42.echo.domain.model.Capability
import com.github.jvsena42.echo.domain.model.Card
import com.github.jvsena42.echo.domain.model.CardIndexEntry
import com.github.jvsena42.echo.domain.model.CardSide
import com.github.jvsena42.echo.domain.model.Deck
import com.github.jvsena42.echo.domain.model.PubkyIdentity
import com.github.jvsena42.echo.domain.model.Session
import com.github.jvsena42.echo.domain.model.Tag

const val TEST_PUBKY = "ownerpk"

fun fakeSession(pubky: String = TEST_PUBKY, displayName: String? = "Tester"): Session = Session(
    identity = PubkyIdentity(
        pubky = pubky,
        displayName = displayName,
        avatarUrl = null,
        bio = null,
    ),
    sessionSecret = "session-secret-$pubky",
    capabilities = listOf(Capability("/pub/echo/:rw"), Capability("/pub/pubky.app/:rw")),
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
): Card = Card(
    id = id,
    deckId = deckId,
    updatedAt = updatedAt,
    front = CardSide(text = front),
    back = CardSide(text = back),
)

fun testDeck(
    id: String = "deck1",
    authorPubky: String = TEST_PUBKY,
    title: String = "Deck $id",
    tags: List<Tag> = emptyList(),
    cardIndex: List<CardIndexEntry> = emptyList(),
    createdAt: Long = 1_000L,
    updatedAt: Long = 2_000L,
): Deck = Deck(
    id = id,
    authorPubky = authorPubky,
    title = title,
    description = null,
    coverEmoji = null,
    coverImageRef = null,
    tags = tags,
    createdAt = createdAt,
    updatedAt = updatedAt,
    cardIndex = cardIndex,
)
