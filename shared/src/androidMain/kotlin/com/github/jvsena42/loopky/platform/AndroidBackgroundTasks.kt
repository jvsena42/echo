package com.github.jvsena42.loopky.platform

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.github.jvsena42.loopky.util.Log
import java.util.concurrent.TimeUnit

/**
 * [BackgroundTasks] over `WorkManager`.
 *
 * No `Configuration.Provider` and no manifest entry: `work-runtime` ships its own
 * `androidx.startup.InitializationProvider`, which AGP merges in, and the default `WorkerFactory`
 * suffices because [MediaRehostWorker] resolves its dependencies from Koin rather than through its
 * constructor. Adding a `Configuration.Provider` *without* also removing `WorkManagerInitializer`
 * from the merged manifest would initialise WorkManager twice.
 */
class AndroidBackgroundTasks(private val context: Context) : BackgroundTasks {

    override fun scheduleMediaRehost() {
        val request = OneTimeWorkRequestBuilder<MediaRehostWorker>()
            .setConstraints(
                Constraints.Builder()
                    // Unmetered, not merely connected: a cloned Anki deck's media runs to
                    // hundreds of MB and the user never asked for that over cellular.
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

        // KEEP, not REPLACE: this is called after every clone and on every deck listing, and
        // replacing would restart the backoff each time and could starve a deck mid-sweep.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(MEDIA_REHOST_TASK_ID, ExistingWorkPolicy.KEEP, request)
        Log.d(TAG, "scheduleMediaRehost: enqueued (unique, KEEP)")
    }

    private companion object {
        const val TAG = "Loopky/BgTasks"
        const val BACKOFF_MINUTES = 15L
    }
}
