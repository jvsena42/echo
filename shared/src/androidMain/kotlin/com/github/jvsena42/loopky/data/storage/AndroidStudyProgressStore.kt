package com.github.jvsena42.loopky.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.github.jvsena42.loopky.domain.model.DailyStudyProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android [StudyProgressStore] over plain `SharedPreferences`, alongside [AndroidAppPreferences]. */
class AndroidStudyProgressStore(context: Context) : StudyProgressStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun load(ownerPubky: String): DailyStudyProgress? = withContext(Dispatchers.IO) {
        decodeStudyProgress(prefs.getString(KEY_STUDY_PROGRESS, null), ownerPubky)
    }

    override suspend fun save(ownerPubky: String, progress: DailyStudyProgress) {
        withContext(Dispatchers.IO) {
            // apply(), unlike the review journal's commit(): losing today's counter to a killed
            // process costs a progress bar, where losing a review costs the user their work.
            prefs.edit().putString(KEY_STUDY_PROGRESS, encodeStudyProgress(ownerPubky, progress)).apply()
        }
    }
}
