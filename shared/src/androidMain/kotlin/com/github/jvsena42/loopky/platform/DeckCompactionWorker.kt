package com.github.jvsena42.loopky.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import org.koin.mp.KoinPlatform

/**
 * Folds the holes card deletes leave in a deck's chunk table back together, a bounded number of
 * merges at a time (#51).
 *
 * Same shape as [MediaRehostWorker], and for the same reasons: dependencies from Koin so the
 * default `WorkerFactory` can build it, and the session hydrated by hand because a
 * WorkManager-started process has the Koin graph but nothing has signed it in.
 */
class DeckCompactionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runSuspendCatching {
        val identity = KoinPlatform.getKoin().get<IdentityRepository>()
        val decks = KoinPlatform.getKoin().get<DeckRepository>()

        if (identity.loadPersistedSession() == null) {
            Log.d(TAG, "doWork: not signed in, nothing to compact")
            return@runSuspendCatching Result.success()
        }

        val pending = decks.decksPendingCompaction()
        if (pending.isEmpty()) {
            Log.d(TAG, "doWork: no deck has holes worth closing")
            return@runSuspendCatching Result.success()
        }

        var unfinished = false
        for (deck in pending) {
            val outcome = decks.compactDeck(deck.id).getOrElse { err ->
                // A merge writes the landing chunk *before* emptying the source, so compaction
                // temporarily grows usage — which means at a full quota the job that would
                // reclaim space is the one that cannot run. Nothing here fixes that; retrying
                // just spends battery reaching the same 507. See #91.
                if (err.toErrorReason() == ErrorReason.StorageFull) {
                    Log.e(TAG, "doWork: out of storage compacting ${deck.id} — giving up", err)
                    return@runSuspendCatching Result.failure()
                }
                Log.e(TAG, "doWork: compaction of ${deck.id} failed — ${err.message}", err)
                unfinished = true
                continue
            }
            if (!outcome.complete) unfinished = true
        }

        // Retry rather than re-enqueueing by hand, so WorkManager's backoff owns the pacing. A
        // deck stopped by its merge budget is unfinished in exactly the same way as one stopped
        // by an outage; both want another pass later.
        if (unfinished) Result.retry() else Result.success()
    }.getOrElse {
        Log.e(TAG, "doWork: FAILED — ${it.message}", it)
        if (it.toErrorReason() == ErrorReason.StorageFull) Result.failure() else Result.retry()
    }

    private companion object {
        const val TAG = "Loopky/CompactWorker"
    }
}
