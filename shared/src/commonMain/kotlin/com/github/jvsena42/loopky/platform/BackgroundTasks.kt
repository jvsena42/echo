package com.github.jvsena42.loopky.platform

/**
 * Deferred, retryable, constraint-gated work that has to survive the app being backgrounded.
 * Platform-provided via Koin (like [Speaker]) rather than `expect`/`actual`, because each side
 * wraps a stateful platform scheduler with its own lifecycle — Android `WorkManager`, iOS
 * `BGTaskScheduler` — and neither is a top-level function.
 */
interface BackgroundTasks {
    /**
     * Ask for a media re-host pass over cloned decks (#53), when the platform judges the moment
     * suitable. Idempotent: an already-queued pass is kept rather than replaced, so calling this
     * on every deck listing costs nothing.
     *
     * Constrained to an unmetered network and a healthy battery. Re-hosting a media-heavy Anki
     * deck moves hundreds of MB, and nobody consented to that over cellular.
     */
    fun scheduleMediaRehost()

    /**
     * Ask for a chunk-compaction pass over decks whose card deletes left holes (#51), when the
     * platform judges the moment suitable. Idempotent in the same way as [scheduleMediaRehost]:
     * an already-queued pass is kept rather than replaced, so calling this after every card
     * delete costs nothing.
     *
     * A separate job rather than a second stage of the re-host one: they qualify different decks
     * (clones vs. heavily edited decks), and folding them together would make a media-heavy sweep
     * that keeps hitting its budget starve compaction indefinitely.
     */
    fun scheduleDeckCompaction()
}

/**
 * Identifier for the media re-host job, shared by both platforms so the Android unique-work name
 * and the iOS `BGTaskSchedulerPermittedIdentifiers` entry cannot drift apart.
 */
internal const val MEDIA_REHOST_TASK_ID = "com.github.jvsena42.loopky.media-rehost"

/** Identifier for the chunk-compaction job. Shared by both platforms, like [MEDIA_REHOST_TASK_ID]. */
internal const val DECK_COMPACTION_TASK_ID = "com.github.jvsena42.loopky.deck-compaction"
