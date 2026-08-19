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
}

/**
 * Identifier for the media re-host job, shared by both platforms so the Android unique-work name
 * and the iOS `BGTaskSchedulerPermittedIdentifiers` entry cannot drift apart.
 */
internal const val MEDIA_REHOST_TASK_ID = "com.github.jvsena42.loopky.media-rehost"
