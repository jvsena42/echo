package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.storage.LocalKey
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.testing.FakeLocalKeyStore
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.VALID_TEST_MNEMONIC
import com.github.jvsena42.loopky.testing.fakePubkyFor
import com.github.jvsena42.loopky.testing.fakeSecretKeyFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KeyBackupRepositoryImplTest {

    private val pubky = FakePubkyClient()
    private val secret = fakeSecretKeyFor(VALID_TEST_MNEMONIC)

    private fun store(mnemonic: String? = VALID_TEST_MNEMONIC) = FakeLocalKeyStore(
        LocalKey(secretKeyHex = secret, pubky = fakePubkyFor(secret), mnemonic = mnemonic),
    )

    private fun repository(keyStore: FakeLocalKeyStore = store()) =
        KeyBackupRepositoryImpl(pubky = pubky, keyStore = keyStore)

    @Test
    fun theRecoveryPhraseIsTheOneTheKeyWasCreatedWith() = runTest {
        assertEquals(VALID_TEST_MNEMONIC, repository().revealRecoveryPhrase().getOrThrow())
    }

    @Test
    fun aFileRestoredKeyHasNoPhraseToRevealAndSaysSo() = runTest {
        // BIP-39 runs one way. Returning something plausible here would be inventing words that
        // do not recover the account.
        assertTrue(repository(store(mnemonic = null)).revealRecoveryPhrase().isFailure)
    }

    @Test
    fun theQuizAsksAboutSpreadOutPositionsAndAlwaysContainsTheRightAnswer() = runTest {
        val quiz = repository().buildPhraseQuiz().getOrThrow()

        assertTrue(quiz.questions.isNotEmpty())
        quiz.questions.forEach { question ->
            assertTrue(question.answer in question.options, "the answer must be among the options")
            assertTrue(question.options.size > 1, "a single option is not a question")
        }
        // Spread rather than adjacent: three consecutive words test only the last line of what
        // the user wrote down.
        assertEquals(quiz.questions.map { it.position }.distinct(), quiz.questions.map { it.position })
    }

    @Test
    fun theQuizAnswerIsTheWordThatActuallySitsAtThatPosition() = runTest {
        val words = VALID_TEST_MNEMONIC.split(" ")
        val quiz = repository().buildPhraseQuiz().getOrThrow()

        quiz.questions.forEach { question ->
            assertEquals(words[question.position - 1], question.answer)
        }
    }

    @Test
    fun theRingExportUrlCarriesThePhraseAndIsShapedAsRingsImportScheme() = runTest {
        // Verified against pubky-ring's inputParser: it decodes, strips this prefix, misses every
        // routed prefix, and lands on validateImportData.
        val url = repository().ringExportUrl().getOrThrow()

        assertTrue(url.startsWith("pubkyring://"), "got: ${url.take(20)}…")
        assertFalse(" " in url, "the phrase must be URL-encoded, not raw")
    }

    @Test
    fun aFileRestoredKeyExportsItsSecretKeyBecauseRingAcceptsThatToo() = runTest {
        // Ring's validateImportData tries the phrase first and falls through to a raw secret key,
        // so a key with no words can still be exported rather than being stuck.
        val url = repository(store(mnemonic = null)).ringExportUrl().getOrThrow()

        assertTrue(url.startsWith("pubkyring://"))
        assertTrue(secret in url, "a phraseless key exports the secret key itself")
    }

    @Test
    fun aRecoveryFileComesBackBase64AsTheFfiReturnsIt() = runTest {
        // The caller decodes before writing: the file on disk is raw, which is what pubky-app and
        // Pubky Ring read.
        val blob = repository().createRecoveryFile("correct horse battery staple").getOrThrow()

        assertEquals("recovery.pkarr", blob.fileName)
        assertTrue(blob.base64.isNotEmpty())
    }

    @Test
    fun aCreatedFileRoundTripsBackToTheSameSecretKey() = runTest {
        val blob = repository().createRecoveryFile("hunter2hunter2").getOrThrow()

        val decrypted = pubky.decryptRecoveryFile(blob.base64, "hunter2hunter2").getOrThrow()

        assertEquals(secret, decrypted)
    }

    @Test
    fun markingBackedUpAccumulatesAcrossMethods() = runTest {
        val keyStore = store()
        val repo = repository(keyStore)

        repo.markBackedUp(BackupMethod.RecoveryPhrase)
        repo.markBackedUp(BackupMethod.PubkyRing)

        val custody = assertIs<KeyCustody.Loopky>(keyStore.custody.first())
        assertEquals(setOf(BackupMethod.RecoveryPhrase, BackupMethod.PubkyRing), custody.backedUpBy)
    }

    @Test
    fun everythingFailsCleanlyWhenLoopkyHoldsNoKey() = runTest {
        // Ring custody: there is nothing on this device to back up, and each call must say so
        // rather than throwing something the UI cannot classify.
        val repo = KeyBackupRepositoryImpl(pubky = pubky, keyStore = FakeLocalKeyStore())

        assertTrue(repo.revealRecoveryPhrase().isFailure)
        assertTrue(repo.buildPhraseQuiz().isFailure)
        assertTrue(repo.createRecoveryFile("x").isFailure)
        assertTrue(repo.ringExportUrl().isFailure)
    }

    @Test
    fun theThreeQuestionsDoNotShareTheirDecoysSoTheAnswersCannotBeDeducedFromEachOther() = runTest {
        // All three questions are on screen at once. When they drew decoys from the same place,
        // two of them had identical option sets and every answer was simply the option missing
        // from the others — so the quiz was passable without ever having seen the phrase. Passing
        // it marks the key backed up and drops the sign-out guard, so this was a route to erasing
        // your only key with no warning.
        val distinctWords = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima"
        pubky.validMnemonics.add(distinctWords)
        val secret = fakeSecretKeyFor(distinctWords)
        val store = FakeLocalKeyStore(
            LocalKey(secretKeyHex = secret, pubky = fakePubkyFor(secret), mnemonic = distinctWords),
        )

        val quiz = repository(store).buildPhraseQuiz().getOrThrow()

        val optionSets = quiz.questions.map { it.options.toSet() }
        assertEquals(optionSets.size, optionSets.distinct().size, "no two questions may share an option set")

        // Stronger: an answer must not be identifiable as "the word the other questions omit".
        quiz.questions.forEachIndexed { i, question ->
            val others = quiz.questions.filterIndexed { j, _ -> j != i }.flatMap { it.options }.toSet()
            val uniqueToThis = question.options.filterNot { it in others }
            assertTrue(
                uniqueToThis.size != 1 || uniqueToThis.single() != question.answer,
                "the answer to question $i is the only option the others do not mention",
            )
        }
    }
}
