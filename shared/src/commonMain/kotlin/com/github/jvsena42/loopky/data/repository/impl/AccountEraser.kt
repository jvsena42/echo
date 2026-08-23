package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PostDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.deleteWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.data.storage.PendingReviewStore
import com.github.jvsena42.loopky.data.storage.StudyProgressStore
import com.github.jvsena42.loopky.data.storage.UnsplashKeyStore
import com.github.jvsena42.loopky.domain.model.DailyStudyProgress
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Removes every record Loopky wrote for the signed-in account, and the local state that outlives
 * a sign-out.
 *
 * Split from [IdentityRepositoryImpl] the way [DeckCompactor] and [DeckMediaSweeper] are split
 * from `DeckRepositoryImpl`: this is a multi-step pass over the homeserver rather than another
 * identity operation, and it needs half a dozen collaborators that nothing else in that class
 * touches.
 *
 * **It does not delete the Pubky account**, and no copy anywhere may say it does — see
 * [com.github.jvsena42.loopky.data.repository.IdentityRepository.deleteAccount] for what is and is
 * not in scope, and why the signup token survives.
 */
// LongParameterList: the collaborator count is the reason this class exists rather than living on
// IdentityRepositoryImpl. Taking an account apart genuinely touches decks, tags, the homeserver and
// four local stores; bundling them behind a holder would hide the blast radius rather than shrink it.
@Suppress("LongParameterList")
class AccountEraser(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val revalidator: SessionRevalidator,
    private val decks: DeckRepository,
    private val tags: TagRepository,
    private val pendingReviews: PendingReviewStore,
    private val studyProgress: StudyProgressStore,
    private val preferences: AppPreferences,
    private val unsplashKeyStore: UnsplashKeyStore,
) {

    /**
     * Sweep the homeserver for [owner].
     *
     * Throws if anything Loopky owns could not be removed, which is what keeps the caller from
     * signing the user out of a half-deleted account. Returns having also wiped local state.
     */
    suspend fun erase(owner: String, onProgress: (Int, Int) -> Unit) {
        val owned = decks.listOwned()
        // Taken before the decks go, so `total` covers the whole job. Best-effort: a listing we
        // could not read still leaves the per-deck pass to do the bulk of the work, and step 2
        // re-lists at the end anyway.
        val strays = namespaceRecords(owner)
        val progress = SweepProgress((owned.size + strays.size).coerceAtLeast(1), onProgress)

        // Counted rather than thrown on. One record that will not go must not abandon the rest —
        // that leaves the account in a worse state than before — but it must still be the
        // difference between "deleted" and "tried to delete", which is what the check below is.
        var failures = 0

        // 1. Decks, through the repository that knows how to take one apart: it holds the per-deck
        // write lock, deletes the manifest last, and clears the deck's tag records.
        for (deck in owned) {
            decks.delete(deck.id).onFailure {
                Log.e(TAG, "deck ${deck.id} FAILED — ${it.message}", it)
                failures++
            }
            progress.step()
        }

        // 2. Review state, settings, subscriptions, tag records — and whatever step 1 could not
        // see. Re-listed rather than reusing `strays`, which is stale by now.
        //
        // Counted from the returned list rather than a shared variable: these run concurrently,
        // and `failures++` from two coroutines is a race that would under-report and sign the user
        // out of an account that is still half there.
        failures += namespaceRecords(owner)
            .mapConcurrently(limit = SWEEP_IN_FLIGHT) { path -> deleteRecord(path).also { progress.step() } }
            .count { deleted -> !deleted }

        // 3. The directory entry. Nothing else takes the account out of Discover and search, so a
        // failure here counts: leaving it behind means a deleted account still shows up.
        tags.removeReservedTag(PubkyUri(PubkyPaths.profile(owner)), ReservedTags.USER)
            .onFailure {
                Log.e(TAG, "${ReservedTags.USER.value} removal FAILED — ${it.message}", it)
                failures++
            }

        // 4. Announcement posts, which would otherwise link to decks that no longer resolve.
        // Uncounted on purpose: a stale post in a feed is untidy rather than a failed deletion,
        // and a user with a busy feed should not be blocked by one unreadable record.
        deleteAnnouncementPosts(owner)

        check(failures == 0) { "$failures record(s) could not be deleted" }

        wipeLocalState()
    }

    /** Every record Loopky owns for [owner], best-effort. */
    private suspend fun namespaceRecords(owner: String): List<String> =
        pubky.listAllEntriesOrEmpty("pubky://$owner/${PubkyPaths.APP_NAMESPACE}/")

    /**
     * Delete [path], treating "already gone" as success.
     *
     * A sweep names records a previous, interrupted sweep already removed, and gone is the outcome
     * being asked for — the same reasoning as `DeckRepositoryImpl.deleteRecordLocked`. Unlike that
     * one this never rethrows: there is no manifest here whose ordering a partial failure could
     * corrupt, and one stubborn record must not strand the rest of the account.
     */
    private suspend fun deleteRecord(path: String): Boolean =
        pubky.deleteWithSessionRetry(path, session, revalidator).fold(
            onSuccess = { true },
            onFailure = { err ->
                if (err.isNotFound()) {
                    Log.d(TAG, "$path already gone")
                    true
                } else {
                    Log.e(TAG, "$path FAILED — ${err.message}", err)
                    false
                }
            },
        )

    /**
     * Remove the posts Loopky published announcing this user's decks.
     *
     * Matched on the embed URI being under **this user's own** deck root, never on the post
     * looking Loopky-ish: `posts/` is the user's ordinary pubky.app feed, shared with every other
     * app, and an over-broad match here deletes writing that has nothing to do with Loopky. A post
     * that cannot be fetched or parsed is left exactly where it is.
     */
    private suspend fun deleteAnnouncementPosts(owner: String) {
        val ownDeckRoot = PubkyPaths.decksList(owner)
        pubky.listAllEntriesOrEmpty(PubkyPaths.postsRoot(owner))
            .mapConcurrently(limit = SWEEP_IN_FLIGHT) { path ->
                val embedUri = runSuspendCatching {
                    loopkyJson.decodeFromString<PostDto>(pubky.get(path).getOrThrow()).embed?.uri
                }.getOrNull()
                if (embedUri?.startsWith(ownDeckRoot) == true) {
                    Log.d(TAG, "removing announcement $path")
                    deleteRecord(path)
                }
            }
    }

    /**
     * Device-local state that outlives sign-out and would otherwise greet a fresh account with the
     * last one's numbers.
     *
     * The signup token is deliberately not here.
     */
    private suspend fun wipeLocalState() {
        runSuspendCatching {
            pendingReviews.save(emptyList())
            studyProgress.save(DailyStudyProgress(dayIndex = 0, newCards = 0, reviews = 0))
            preferences.setCachedStudySettings("")
            unsplashKeyStore.clear()
        }.onFailure { Log.e(TAG, "local wipe FAILED — ${it.message}", it) }
    }

    private companion object {
        const val TAG = "Loopky/AccountEraser"

        /**
         * Narrower than the default write width, matching `DeckRepositoryImpl`'s sweep: the FFI
         * re-imports and revalidates the session on every authenticated call, so a long run of
         * deletes at publish width reliably 429s straight through the retry budget.
         */
        const val SWEEP_IN_FLIGHT = 2
    }
}

/**
 * Counts records off against a total and reports them, safely from concurrent callers.
 *
 * A plain `var done` incremented from inside `mapConcurrently` is a data race. The worst case is
 * only a progress number that skips, but the fix is three lines.
 */
private class SweepProgress(private val total: Int, private val report: (Int, Int) -> Unit) {
    private val lock = Mutex()
    private var done = 0

    suspend fun step() {
        val current = lock.withLock { ++done }
        report(current, total)
    }
}
