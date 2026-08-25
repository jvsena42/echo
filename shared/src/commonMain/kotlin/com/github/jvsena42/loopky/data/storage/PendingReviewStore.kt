package com.github.jvsena42.loopky.data.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One graded review that has not reached the homeserver yet.
 *
 * Deliberately its own shape rather than a reuse of the `srs/{n}.json` record DTO: this is a
 * device-local journal of *individual* reviews, while the homeserver record is a whole chunk of
 * them. [chunk] rides along because the chunk a state belongs to is recorded when the state is
 * first written and never recomputed — a restore that guessed differently would persist the
 * review into the wrong record.
 */
@Serializable
data class PendingReview(
    /**
     * Who did the reviewing. Distinct from [authorPubky] and not derivable from it: review state
     * is stored per-reader on the homeserver (`srs/{owner}/{author}/{deckId}`), so an entry that
     * does not say whose it is can be flushed to the wrong account. Null on a journal written
     * before this field existed — [decodePendingReviews] drops those rather than guessing, because
     * the only available guess is "whoever is signed in now", which is exactly the bug.
     */
    val ownerPubky: String? = null,
    /** The deck's author. Review state is author-keyed, since followed decks are studiable too. */
    val authorPubky: String,
    val deckId: String,
    val chunk: Int,
    val cardId: String,
    val dueAt: Long,
    val intervalDays: Int,
    val easeFactor: Double,
    val repetitions: Int,
    val lastGrade: Int? = null,
)

/**
 * Device-local journal of reviews graded but not yet flushed to the homeserver.
 *
 * Exists because the buffer it mirrors is in memory only. `SrsRepository` batches reviews and
 * writes them a chunk at a time, and re-queues them on a failed flush so the next one retries —
 * but the whole set died with the process. At a full quota (#91) every flush 507s, every retry
 * re-queues, and a user could study a full session, watch the counters move, restart the app and
 * find the progress gone, with no error at any point. This is the user's actual work; it has to
 * outlive the process.
 *
 * Non-secret and device-local, so it sits alongside [AppPreferences] rather than behind
 * [SecureSessionStore] — the same reasoning, in the same direction: a review is not a credential.
 *
 * Small by construction: it holds only what has not been written yet, and is cleared by the first
 * successful flush.
 */
interface PendingReviewStore {
    /** Everything the last [save] wrote, or empty when there is nothing pending. */
    suspend fun load(): List<PendingReview>

    /** Replace the journal wholesale. An empty list clears it. */
    suspend fun save(entries: List<PendingReview>)
}

internal const val KEY_PENDING_REVIEWS = "pending_reviews"

/**
 * Shared by both platform implementations so the on-disk shape cannot drift between them — a
 * journal written by one and read by the other is not a scenario, but a schema maintained twice
 * is how it would become one.
 */
private val journalJson = Json { ignoreUnknownKeys = true }

internal fun encodePendingReviews(entries: List<PendingReview>): String =
    journalJson.encodeToString(entries)

/**
 * Decode a stored journal, or nothing if it cannot be read. A corrupt or outdated journal costs
 * the unflushed reviews it held — the same as having no journal, which is where this started.
 *
 * Entries with no [PendingReview.ownerPubky] are dropped for the same reason: they come from a
 * journal written before reviews recorded who made them, and the only way to keep them would be to
 * credit them to whoever signs in next. Losing a handful of unflushed reviews once, on upgrade, is
 * the cheaper mistake — they are the ones a flush had already failed to send.
 */
internal fun decodePendingReviews(payload: String?): List<PendingReview> {
    if (payload.isNullOrBlank()) return emptyList()
    return runCatching { journalJson.decodeFromString<List<PendingReview>>(payload) }
        .getOrDefault(emptyList())
        .filter { it.ownerPubky != null }
}
