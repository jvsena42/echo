package com.github.jvsena42.loopky.platform

import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
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
 * - The system decides when — and whether — a processing task runs, so a pass may sit for days.
 *   That is acceptable for both jobs: re-hosting is opportunistic by design (#65 already covers
 *   the blobs the user actually looks at), and compaction only ever buys request count back.
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

        val compaction = BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            identifier = DECK_COMPACTION_TASK_ID,
            usingQueue = null,
        ) { task -> task?.let(::runCompaction) }
        Log.d(TAG, "register: $DECK_COMPACTION_TASK_ID registered=$compaction")
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

    override fun scheduleDeckCompaction() {
        val request = BGProcessingTaskRequest(DECK_COMPACTION_TASK_ID).apply {
            setRequiresNetworkConnectivity(true)
            setRequiresExternalPower(false)
            setEarliestBeginDate(NSDate.dateWithTimeIntervalSinceNow(EARLIEST_BEGIN_SECONDS))
        }
        runCatching { BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null) }
            .onFailure { Log.e(TAG, "scheduleDeckCompaction: submit failed — ${it.message}", it) }
    }

    private fun runCompaction(task: BGTask) = run(task, ::scheduleDeckCompaction) {
        decks.decksPendingCompaction().forEach { deck ->
            decks.compactDeck(deck.id)
                .onFailure { Log.e(TAG, "compaction of ${deck.id} failed — ${it.message}", it) }
        }
    }

    private fun runSweep(task: BGTask) = run(task, ::scheduleMediaRehost) {
        decks.decksPendingRehost().forEach { deck ->
            decks.rehostPendingMedia(deck.id)
                .onFailure { Log.e(TAG, "sweep of ${deck.id} failed — ${it.message}", it) }
        }
    }

    /**
     * Run [work] as a `BGTask`: signed in first, always asking for another pass through
     * [reschedule], and cancellable from the expiration handler.
     *
     * iOS gives a processing task a few minutes and then pulls the plug, so cancelling is what
     * keeps the work interruptible; the persisted cursor (re-host) and the fact that each merge
     * commits on its own (compaction) are what make that survivable. And unlike WorkManager there
     * is no `retry()` — a submitted request is consumed once it runs — so a pass stopped by its
     * budget would otherwise never be picked up again.
     */
    private fun run(task: BGTask, reschedule: () -> Unit, work: suspend () -> Unit) {
        val job = scope.launch {
            // runSuspendCatching, not runCatching: the expiration handler cancels this job, and a
            // plain runCatching would swallow that and go on to reschedule and report success —
            // both of which the handler has already done.
            runSuspendCatching {
                if (identity.loadPersistedSession() == null) return@runSuspendCatching
                work()
            }.onFailure { Log.e(TAG, "run: FAILED — ${it.message}", it) }
            reschedule()
            task.setTaskCompletedWithSuccess(true)
        }
        task.expirationHandler = {
            Log.w(TAG, "run: expired, cancelling")
            job.cancel()
            reschedule()
            task.setTaskCompletedWithSuccess(false)
        }
    }

    private companion object {
        const val TAG = "Loopky/BgTasks"

        /** A few minutes out, so a clone's sweep is not competing with the clone itself. */
        const val EARLIEST_BEGIN_SECONDS = 300.0
    }
}
