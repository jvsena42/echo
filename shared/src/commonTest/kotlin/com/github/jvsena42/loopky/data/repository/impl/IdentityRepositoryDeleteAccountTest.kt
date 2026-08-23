package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.domain.model.DailyStudyProgress
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakePendingReviewStore
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.FakeStudyProgressStore
import com.github.jvsena42.loopky.testing.FakeUnsplashKeyStore
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.identityRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What "delete my account" is allowed to reach, and — more importantly — what it is not.
 *
 * The blast radius is the whole point of these tests. The sweep runs over the same homeserver that
 * holds the user's pubky.app identity and social graph, which belong to every Pubky app rather
 * than to Loopky, and a delete is not undoable.
 */
class IdentityRepositoryDeleteAccountTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val tags = RecordingTagRepository()
    private val decks = FakeDeckRepository()
    private val pendingReviews = FakePendingReviewStore()
    private val studyProgress = FakeStudyProgressStore()
    private val preferences = FakeAppPreferences()
    private val unsplashKey = FakeUnsplashKeyStore()

    private val repo = identityRepository(
        pubky = pubky,
        sessionProvider = session,
        tagRepository = tags,
        deckRepository = decks,
        pendingReviews = pendingReviews,
        studyProgress = studyProgress,
        preferences = preferences,
        unsplashKeyStore = unsplashKey,
    )

    private val loopkyRoot = "pubky://$TEST_PUBKY/${PubkyPaths.APP_NAMESPACE}"

    private fun seedHomeserver() {
        // Loopky's own namespace: review state, settings, a subscription, a tag record.
        pubky.store["$loopkyRoot/srs/$TEST_PUBKY/deck1/0.json"] = "{}"
        pubky.store["$loopkyRoot/settings.json"] = "{}"
        pubky.store["$loopkyRoot/subscriptions/otherpk/deck9.json"] = "{}"
        pubky.store["$loopkyRoot/tags/abc123"] = "{}"
        // Not Loopky's: the wider Pubky identity and social graph.
        pubky.store[PubkyPaths.profile(TEST_PUBKY)] = """{"name":"Tester","bio":null,"image":null}"""
        pubky.store[PubkyPaths.follow(TEST_PUBKY, "friendpk")] = "{}"
    }

    @Test
    fun everythingUnderTheLoopkyNamespaceIsRemoved() = runTest {
        seedHomeserver()

        repo.deleteAccount().getOrThrow()

        assertTrue(
            pubky.store.keys.none { it.startsWith("$loopkyRoot/") },
            "left behind: ${pubky.store.keys.filter { it.startsWith("$loopkyRoot/") }}",
        )
    }

    @Test
    fun theProfileAndSocialFollowsAreLeftAlone() = runTest {
        seedHomeserver()

        repo.deleteAccount().getOrThrow()

        // These are the user's presence in every other Pubky app. Deleting them would be Loopky
        // reaching well past what it was asked to remove.
        assertTrue(PubkyPaths.profile(TEST_PUBKY) in pubky.store, "profile.json is not Loopky's")
        assertTrue(PubkyPaths.follow(TEST_PUBKY, "friendpk") in pubky.store, "follows are not Loopky's")
    }

    @Test
    fun everyOwnedDeckGoesThroughTheDeckRepositoryRatherThanARawSweep() = runTest {
        // DeckRepository.delete takes the per-deck lock, deletes the manifest last, and clears the
        // deck's tag records. A raw path sweep does none of that.
        decks.decks["deck1"] = testDeck(id = "deck1")
        decks.decks["deck2"] = testDeck(id = "deck2")

        repo.deleteAccount().getOrThrow()

        assertEquals(listOf("deck1", "deck2"), decks.deleted.sorted())
    }

    @Test
    fun theLoopkyUserSelfTagIsRemovedSoTheAccountLeavesTheDirectory() = runTest {
        val profileUri = PubkyUri(PubkyPaths.profile(TEST_PUBKY))
        tags.putReservedTag(profileUri, ReservedTags.USER)

        repo.deleteAccount().getOrThrow()

        // Without this the account keeps turning up in Discover and search after deletion — the
        // tag index is the only user directory Loopky has.
        assertFalse(tags.isSelfTagged(TEST_PUBKY, ReservedTags.USER))
    }

    @Test
    fun anAnnouncementPostForOwnDecksIsRemoved() = runTest {
        val post = PubkyPaths.post(TEST_PUBKY, "post1")
        pubky.store[post] = announcement("$loopkyRoot/decks/deck1/manifest.json")

        repo.deleteAccount().getOrThrow()

        assertFalse(post in pubky.store, "an announcement would link to a deck that no longer resolves")
    }

    @Test
    fun anOrdinaryPostIsNeverTouched() = runTest {
        // posts/ is the user's whole pubky.app feed. Matching on anything looser than "embeds one
        // of my own deck manifests" deletes writing that has nothing to do with Loopky.
        val plain = PubkyPaths.post(TEST_PUBKY, "post2")
        val aboutSomeoneElsesDeck = PubkyPaths.post(TEST_PUBKY, "post3")
        pubky.store[plain] = """{"content":"good morning","kind":"short"}"""
        pubky.store[aboutSomeoneElsesDeck] =
            announcement("pubky://otherpk/${PubkyPaths.APP_NAMESPACE}/decks/deck1/manifest.json")

        repo.deleteAccount().getOrThrow()

        assertTrue(plain in pubky.store)
        assertTrue(aboutSomeoneElsesDeck in pubky.store, "someone else's deck is not our announcement")
    }

    @Test
    fun localStateIsClearedButAnUnspentSignupTokenIsNot() = runTest {
        pendingReviews.save(listOf(pendingReview()))
        studyProgress.save(DailyStudyProgress(dayIndex = 7, newCards = 3, reviews = 12))
        preferences.setCachedStudySettings("""{"newCardsPerDayGoal":40}""")
        unsplashKey.save("a-key")

        repo.deleteAccount().getOrThrow()

        assertTrue(pendingReviews.load().isEmpty())
        assertEquals(0, studyProgress.load()?.reviews)
        assertEquals("", preferences.cachedStudySettings.first())
        assertEquals("", unsplashKey.key.first())
        // The token is deliberately absent from this flow: it cost sats or one of two SMS
        // verifications a week, never expires, and still redeems against a new account.
    }

    @Test
    fun theSessionIsClearedOnlyAfterTheSweepSucceeds() = runTest {
        seedHomeserver()

        repo.deleteAccount().getOrThrow()

        assertEquals(null, session.current())
    }

    @Test
    fun aFailedSweepLeavesTheUserSignedIn() = runTest {
        // Signed out and half-deleted is unrecoverable: signing back in is what a retry needs, and
        // the records left behind are unreachable until then.
        decks.decks["deck1"] = testDeck(id = "deck1")
        decks.deleteError = IllegalStateException("homeserver unreachable")
        pubky.failAllSessionCallsWith = IllegalStateException("homeserver unreachable")

        repo.deleteAccount()

        assertEquals(TEST_PUBKY, session.current()?.identity?.pubky)
    }

    @Test
    fun deletingWithoutASessionFails() = runTest {
        session.set(null)

        val result = repo.deleteAccount()

        assertTrue(result.isFailure)
    }

    @Test
    fun progressIsReportedAsRecordsGo() = runTest {
        decks.decks["deck1"] = testDeck(id = "deck1")
        seedHomeserver()

        val reports = mutableListOf<Pair<Int, Int>>()
        repo.deleteAccount { done, total -> reports += done to total }.getOrThrow()

        assertTrue(reports.isNotEmpty(), "a sweep with no feedback is a frozen dialog")
        // Monotonic, so a progress bar never jumps backwards.
        assertEquals(reports.map { it.first }.sorted(), reports.map { it.first })
    }

    private fun announcement(deckUri: String): String =
        """{"content":"new deck","kind":"link","embed":{"kind":"link","uri":"$deckUri"}}"""

    private fun pendingReview() = com.github.jvsena42.loopky.data.storage.PendingReview(
        authorPubky = TEST_PUBKY,
        deckId = "deck1",
        chunk = 0,
        cardId = "c1",
        dueAt = 0,
        intervalDays = 1,
        easeFactor = 2.5,
        repetitions = 1,
    )
}
