package com.github.jvsena42.loopky.data.storage

import platform.Foundation.NSUserDefaults

/** iOS [PendingReviewStore] over `NSUserDefaults`, namespaced by [PREFERENCES_NAME]. */
class IosPendingReviewStore : PendingReviewStore {
    private val defaults = NSUserDefaults(suiteName = PREFERENCES_NAME)

    override suspend fun load(): List<PendingReview> =
        decodePendingReviews(defaults.stringForKey(KEY_PENDING_REVIEWS))

    override suspend fun save(entries: List<PendingReview>) {
        if (entries.isEmpty()) {
            defaults.removeObjectForKey(KEY_PENDING_REVIEWS)
        } else {
            defaults.setObject(encodePendingReviews(entries), KEY_PENDING_REVIEWS)
        }
        // Forced rather than left to the periodic flush: the journal is written as a study
        // session ends, and the process may be suspended before NSUserDefaults gets around to it.
        defaults.synchronize()
    }
}
