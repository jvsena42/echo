package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.AppSettingsDto
import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.StudySettingsDto
import com.github.jvsena42.loopky.data.repository.SettingsOrigin
import com.github.jvsena42.loopky.domain.model.StudySettings
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.signedInProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The interesting behaviour here is not reading and writing a record — it is refusing to write one.
 * A settings record holds scheduling rules, and overwriting it with defaults after a failed read
 * would silently reset the user's intervals with nothing on screen to say so.
 */
class SettingsRepositoryImplTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val preferences = FakeAppPreferences()
    private val url = "pubky://$TEST_PUBKY/pub/loopky/settings.json"

    private fun repo(prefs: FakeAppPreferences = preferences) = SettingsRepositoryImpl(
        pubky = pubky,
        session = session,
        revalidator = CountingRevalidator(),
        preferences = prefs,
    )

    private fun seedRecord(settings: StudySettings) {
        pubky.store[url] = loopkyJson.encodeToString(
            AppSettingsDto(
                study = StudySettingsDto(
                    new_cards_per_day = settings.newCardsPerDayGoal,
                    first_hard_days = settings.firstHardDays,
                    first_good_days = settings.firstGoodDays,
                    first_easy_days = settings.firstEasyDays,
                ),
            ),
        )
    }

    @Test
    fun defaultsAreAvailableBeforeAnythingIsRead() = runTest {
        val repo = repo()
        assertEquals(StudySettings.Default, repo.studySettings.value.settings)
        assertEquals(SettingsOrigin.Defaults, repo.studySettings.value.origin)
        assertFalse(repo.studySettings.value.isEditable)
    }

    @Test
    fun anAbsentRecordIsASuccessfulRead() = runTest {
        // Every new account has no settings.json. Treating that as "not loaded" would leave the
        // Settings screen permanently disabled for everyone on their first run.
        val repo = repo()
        repo.ensureLoaded()

        assertEquals(StudySettings.Default, repo.studySettings.value.settings)
        assertEquals(SettingsOrigin.Remote, repo.studySettings.value.origin)
        assertTrue(repo.studySettings.value.isEditable)
        assertFalse(pubky.store.containsKey(url), "an absent record must not be written on read")
    }

    @Test
    fun anExistingRecordIsLoaded() = runTest {
        seedRecord(StudySettings(newCardsPerDayGoal = 40, firstEasyDays = 10))
        val repo = repo()

        repo.ensureLoaded()

        assertEquals(40, repo.studySettings.value.settings.newCardsPerDayGoal)
        assertEquals(10, repo.studySettings.value.settings.firstEasyDays)
        assertEquals(SettingsOrigin.Remote, repo.studySettings.value.origin)
    }

    @Test
    fun aFailedReadLeavesTheSettingsUneditable() = runTest {
        pubky.failGetWith = PubkyError("Request failed: connection reset")
        val repo = repo()

        repo.ensureLoaded()

        assertEquals(SettingsOrigin.Defaults, repo.studySettings.value.origin)
        assertFalse(repo.studySettings.value.isEditable)
    }

    @Test
    fun aWriteIsRefusedUntilTheRecordHasBeenRead() = runTest {
        // The destructive case: read fails, the flow holds defaults, one tap in Settings would
        // otherwise put 1/3/7 over whatever the user actually had.
        seedRecord(StudySettings(firstEasyDays = 30))
        pubky.failGetWith = PubkyError("Request failed: connection reset")
        val repo = repo()
        repo.ensureLoaded()

        val result = repo.update(StudySettings.Default)

        assertTrue(result.isFailure, "a write went out without the record ever being read")
        pubky.failGetWith = null
        assertEquals(
            expected = 30,
            actual = loopkyJson.decodeFromString<AppSettingsDto>(pubky.store.getValue(url)).study.first_easy_days,
            message = "the stored record was overwritten",
        )
    }

    @Test
    fun anUpdateWritesTheRecordAndTheOfflineMirror() = runTest {
        val repo = repo()
        repo.ensureLoaded()

        repo.update(StudySettings(newCardsPerDayGoal = 5, firstEasyDays = 14)).getOrThrow()

        val written = loopkyJson.decodeFromString<AppSettingsDto>(pubky.store.getValue(url))
        assertEquals(5, written.study.new_cards_per_day)
        assertEquals(14, written.study.first_easy_days)
        assertEquals(5, repo.studySettings.value.settings.newCardsPerDayGoal)
        assertTrue(preferences.cachedStudySettingsValue.isNotBlank(), "no offline copy was kept")
    }

    @Test
    fun anUpdateIsSanitizedBeforeItIsWritten() = runTest {
        val repo = repo()
        repo.ensureLoaded()

        repo.update(StudySettings(newCardsPerDayGoal = 0, firstGoodDays = 9_999)).getOrThrow()

        val written = loopkyJson.decodeFromString<AppSettingsDto>(pubky.store.getValue(url))
        assertEquals(1, written.study.new_cards_per_day)
        assertEquals(365, written.study.first_good_days)
    }

    @Test
    fun anOfflineSessionSchedulesWithTheUsersOwnIntervals() = runTest {
        // Without the mirror, a launch with no network silently reverts to 1/3/7 and writes
        // review state computed from rules the user never chose.
        val warm = repo()
        warm.ensureLoaded()
        warm.update(StudySettings(firstEasyDays = 14)).getOrThrow()

        pubky.failGetWith = PubkyError("Request failed: connection reset")
        val coldStart = repo()
        coldStart.ensureLoaded()

        assertEquals(14, coldStart.studySettings.value.settings.firstEasyDays)
        assertEquals(SettingsOrigin.Cached, coldStart.studySettings.value.origin)
        assertFalse(
            coldStart.studySettings.value.isEditable,
            "a cached copy is good enough to schedule with, never good enough to overwrite with",
        )
    }

    @Test
    fun theRecordIsReadOncePerSession() = runTest {
        val repo = repo()
        repo.ensureLoaded()
        val readsAfterFirst = pubky.gets.count { it == url }
        repeat(5) { repo.ensureLoaded() }

        assertEquals(readsAfterFirst, pubky.gets.count { it == url }, "re-read a warm cache")
    }

    @Test
    fun anUpdateKeepsSectionsThisClientDoesNotKnowAbout() = runTest {
        // The record is written whole, so a naive write would drop a section added by a newer
        // client. Re-reading inside the lock is what stops that.
        pubky.store[url] = """{"schema_version":1,"study":{"first_easy_days":9},"future":{"x":1}}"""
        val repo = repo()
        repo.ensureLoaded()

        repo.update(StudySettings(firstEasyDays = 12)).getOrThrow()

        val raw = pubky.store.getValue(url)
        assertEquals(12, loopkyJson.decodeFromString<AppSettingsDto>(raw).study.first_easy_days)
        assertTrue(raw.contains("\"future\""), "a section this build cannot parse was dropped: $raw")
    }
}
