package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.domain.model.DailyStudyProgress
import platform.Foundation.NSUserDefaults

/** iOS [StudyProgressStore] over `NSUserDefaults`, alongside [IosAppPreferences]. */
class IosStudyProgressStore : StudyProgressStore {
    private val defaults = NSUserDefaults(suiteName = PREFERENCES_NAME)

    override suspend fun load(ownerPubky: String): DailyStudyProgress? =
        decodeStudyProgress(defaults.stringForKey(KEY_STUDY_PROGRESS), ownerPubky)

    override suspend fun save(ownerPubky: String, progress: DailyStudyProgress) {
        defaults.setObject(encodeStudyProgress(ownerPubky, progress), KEY_STUDY_PROGRESS)
    }
}
