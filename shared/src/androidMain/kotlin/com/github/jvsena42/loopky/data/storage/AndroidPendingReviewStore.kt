package com.github.jvsena42.loopky.data.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android [PendingReviewStore] over plain `SharedPreferences`, alongside [AndroidAppPreferences]. */
class AndroidPendingReviewStore(context: Context) : PendingReviewStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): List<PendingReview> = withContext(Dispatchers.IO) {
        decodePendingReviews(prefs.getString(KEY_PENDING_REVIEWS, null))
    }

    override suspend fun save(entries: List<PendingReview>) {
        withContext(Dispatchers.IO) {
            // commit(), not apply(): this is written on the way out of a study session and the
            // point of it is to survive a process that may be about to be killed. apply() defers
            // the disk write, which is exactly the window that loses the reviews.
            val editor = prefs.edit()
            if (entries.isEmpty()) {
                editor.remove(KEY_PENDING_REVIEWS)
            } else {
                editor.putString(KEY_PENDING_REVIEWS, encodePendingReviews(entries))
            }
            editor.commit()
        }
    }
}
