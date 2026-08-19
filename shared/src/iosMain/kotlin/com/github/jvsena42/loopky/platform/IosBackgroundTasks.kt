package com.github.jvsena42.loopky.platform

import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.util.Log
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * [BackgroundTasks] over `BGTaskScheduler`.
 *
 * **Unverified.** The iOS app has never been driven against a real homeserver (see CLAUDE.md), so
 * this compiles and is wired but has not been observed running. Treat it as a first draft rather
 * than a working feature; the Android path is the one with evidence behind it.
 *
 * iOS differs from WorkManager in two ways that shape this:
 * - The handler must be registered **before the app finishes launching**, hence [register] being
 *   called from the Koin bootstrap rather than lazily on first schedule.
 * - The system decides when — and whether — a processing task runs, so a sweep may sit for days.
 *   That is acceptable here: re-hosting is opportunistic by design, and #65 already covers the
 *   blobs the user actually looks at.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBackgroundTasks(
    private val identity: IdentityRepository,
    private val decks: DeckRepository,
) : BackgroundTasks {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Install the task handler. Must run before the app finishes launching, and the identifier
     * must also appear in `Info.plist` under `BGTaskSchedulerPermittedIdentifiers` — without it
     * `BGTaskScheduler` rejects both the registration and the submission.
     */
    fun register() {
        val registered = BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            identifier = MEDIA_REHOST_TASK_ID,
            usingQueue = null,
        ) { task -> task?.let(::runSweep) }
        Log.d(TAG, "register: $MEDIA_REHOST_TASK_ID registered=$registered")
    }

    override fun scheduleMediaRehost() {
        val request = BGProcessingTaskRequest(MEDIA_REHOST_TASK_ID).apply {
            // Matches Android's UNMETERED + battery-not-low: a cloned Anki deck's media runs to
            // hundreds of MB. `requiresExternalPower` would be stricter than the Android side and
            // in practice means "only overnight while charging".
            setRequiresNetworkConnectivity(true)
            setRequiresExternalPower(false)
            setEarliestBeginDate(NSDate.dateWithTimeIntervalSinceNow(EARLIEST_BEGIN_SECONDS))
        }
        // Throws if the identifier is not permitted in Info.plist, or on a simulator without the
        // capability. A failure to schedule must not take the caller — a clone — down with it.
        runCatching { BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null) }
            .onFailure { Log.e(TAG, "scheduleMediaRehost: submit failed — ${it.message}", it) }
    }

    private fun runSweep(task: BGTask) {
        val job = scope.launch {
            runCatching {
                if (identity.loadPersistedSession() == null) return@runCatching
                decks.decksPendingRehost().forEach { deck ->
                    decks.rehostPendingMedia(deck.id)
                        .onFailure { Log.e(TAG, "sweep of ${deck.id} failed — ${it.message}", it) }
                }
            }.onFailure { Log.e(TAG, "runSweep: FAILED — ${it.message}", it) }
            // Always ask for another pass. Unlike WorkManager there is no `retry()`, and a
            // submitted request is consumed once it runs, so a deck stopped by its chunk budget
            // would otherwise never be picked up again.
            scheduleMediaRehost()
            task.setTaskCompletedWithSuccess(true)
        }
        // iOS gives a processing task a few minutes and then pulls the plug. Cancelling here is
        // what keeps the work interruptible; the persisted cursor is what makes that survivable.
        task.expirationHandler = {
            Log.w(TAG, "runSweep: expired, cancelling")
            job.cancel()
            scheduleMediaRehost()
            task.setTaskCompletedWithSuccess(false)
        }
    }

    private companion object {
        const val TAG = "Loopky/BgTasks"

        /** A few minutes out, so a clone's sweep is not competing with the clone itself. */
        const val EARLIEST_BEGIN_SECONDS = 300.0
    }
}
