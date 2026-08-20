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
 * Copies a cloned deck's still-pinned media under the clone's own pubky, a bounded slice at a
 * time (#53).
 *
 * Dependencies come from Koin rather than the constructor, so the default `WorkerFactory` can
 * build this. Koin is started in `LoopkyApp.onCreate`, which runs whether the process was launched
 * by the user or by WorkManager.
 */
class MediaRehostWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runSuspendCatching {
        val identity = KoinPlatform.getKoin().get<IdentityRepository>()
        val decks = KoinPlatform.getKoin().get<DeckRepository>()

        // WorkManager can start the process with no Activity. Koin is up, but nothing has
        // hydrated the session — `loadPersistedSession` is only ever called from ViewModels — so
        // without this every write below fails on "Not signed in", silently and forever.
        if (identity.loadPersistedSession() == null) {
            Log.d(TAG, "doWork: not signed in, nothing to re-host")
            return@runSuspendCatching Result.success()
        }

        val pending = decks.decksPendingRehost()
        if (pending.isEmpty()) {
            Log.d(TAG, "doWork: no clones awaiting a media sweep")
            return@runSuspendCatching Result.success()
        }

        var unfinished = false
        for (deck in pending) {
            val outcome = decks.rehostPendingMedia(deck.id).getOrElse { err ->
                // The one failure that must not become a retry. Re-hosting copies blobs *under
                // your own pubky*, so it is itself a quota consumer — at a full disk every later
                // attempt fails identically, and WorkManager would back off against it forever.
                // Stop and let the next explicit enqueue (or the user freeing space) restart it.
                if (err.toErrorReason() == ErrorReason.StorageFull) {
                    Log.e(TAG, "doWork: out of storage sweeping ${deck.id} — giving up", err)
                    return@runSuspendCatching Result.failure()
                }
                Log.e(TAG, "doWork: sweep of ${deck.id} failed — ${err.message}", err)
                unfinished = true
                continue
            }
            if (!outcome.complete) unfinished = true
        }

        // Retry rather than re-enqueueing by hand, so WorkManager's exponential backoff owns the
        // pacing. A deck stopped by its chunk budget is "unfinished" in exactly the same way as
        // one stopped by an outage; both want another pass later.
        if (unfinished) Result.retry() else Result.success()
    }.getOrElse {
        Log.e(TAG, "doWork: FAILED — ${it.message}", it)
        if (it.toErrorReason() == ErrorReason.StorageFull) Result.failure() else Result.retry()
    }

    private companion object {
        const val TAG = "Loopky/RehostWorker"
    }
}
