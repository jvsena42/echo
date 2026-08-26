package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.repository.KeyBackupRepository
import com.github.jvsena42.loopky.data.repository.PhraseQuiz
import com.github.jvsena42.loopky.data.repository.PhraseQuizQuestion
import com.github.jvsena42.loopky.data.repository.RecoveryFileBlob
import com.github.jvsena42.loopky.data.storage.LocalKey
import com.github.jvsena42.loopky.data.storage.LocalKeyStore
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.encodeUriComponent
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * [KeyBackupRepository] over [LocalKeyStore] and the FFI.
 *
 * Nothing here logs a phrase, a secret key, a passphrase or a `pubkyring://` URL — not even a
 * redacted prefix. The only things that leave are destined straight for a screenshot-blocked
 * screen or the platform's own save/share sheet.
 */
internal class KeyBackupRepositoryImpl(
    private val pubky: PubkyClient,
    private val keyStore: LocalKeyStore,
    /**
     * Where the FFI's synchronous key work runs. `createRecoveryFile` is **Argon2id**, which is
     * hundreds of milliseconds by design, and `runFfi` adds no dispatcher of its own — on the main
     * thread that is an ANR rather than a slow frame. Injectable so tests drive it.
     */
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : KeyBackupRepository {

    override val custody: Flow<KeyCustody> = keyStore.custody

    override suspend fun revealRecoveryPhrase(): Result<String> = runSuspendCatching {
        requireNotNull(requireLocalKey().mnemonic) {
            "This key was restored from a recovery file, so it has no phrase"
        }
    }

    override suspend fun buildPhraseQuiz(): Result<PhraseQuiz> = runSuspendCatching {
        val words = revealRecoveryPhrase().getOrThrow().split(" ").filter { it.isNotBlank() }
        check(words.size >= QUESTION_COUNT) { "A recovery phrase should have twelve words" }

        // Positions are spread across the phrase rather than random: the point of the quiz is to
        // check the user wrote the words down, and asking about three adjacent ones tests only
        // whether they remember the last line.
        val positions = List(QUESTION_COUNT) { i -> (i + 1) * words.size / (QUESTION_COUNT + 1) }
            .map { it.coerceIn(0, words.size - 1) }
            .distinct()

        PhraseQuiz(
            questions = positions.map { index ->
                val answer = words[index]
                // Decoys come from the phrase itself. A decoy from the wider BIP-39 list would be
                // eliminable by someone who is holding the phrase but cannot read their own
                // handwriting, which is not the thing being tested.
                val decoys = words.filterIndexed { i, w -> i != index && w != answer }
                    .distinct()
                    .take(OPTION_COUNT - 1)
                PhraseQuizQuestion(
                    position = index + 1,
                    // Sorted rather than shuffled: no `Random` anywhere near key material, and a
                    // stable order is also what makes the quiz testable.
                    options = (decoys + answer).sorted(),
                    answer = answer,
                )
            },
        )
    }

    override suspend fun createRecoveryFile(passphrase: String): Result<RecoveryFileBlob> =
        runSuspendCatching {
            val key = requireLocalKey()
            val base64 = withContext(cpuDispatcher) {
                pubky.createRecoveryFile(key.secretKeyHex, passphrase)
            }.getOrThrow()
            Log.d(TAG, "createRecoveryFile: created")
            RecoveryFileBlob(base64 = base64, fileName = RECOVERY_FILE_NAME)
        }

    override suspend fun ringExportUrl(): Result<String> = runSuspendCatching {
        val key = requireLocalKey()
        // The phrase when we have one, the raw secret key otherwise. Ring's own input parser
        // accepts both — `validateImportData` tries `mnemonicPhraseToKeypair` first and falls
        // through to `getPublicKeyFromSecretKey` — so a file-restored key can still be exported.
        val payload = key.mnemonic ?: key.secretKeyHex
        "$RING_SCHEME${encodeUriComponent(payload)}"
    }

    override suspend fun markBackedUp(method: BackupMethod) {
        keyStore.markBackedUp(method)
        Log.d(TAG, "markBackedUp: $method")
    }

    private suspend fun requireLocalKey(): LocalKey =
        requireNotNull(keyStore.current()) { "Loopky is not holding a key for this account" }

    private companion object {
        const val TAG = "Loopky/KeyBackupRepo"
        const val QUESTION_COUNT = 3
        const val OPTION_COUNT = 4
        const val RECOVERY_FILE_NAME = "recovery.pkarr"

        /**
         * Pubky Ring's import scheme. Its parser URL-decodes, strips this prefix, normalises `-`,
         * `_` and `+` to spaces, misses every routed prefix (`signup?`, `session?`, …) and lands
         * on `validateImportData`. Verified against `pubky-ring/src/utils/inputParser.ts`.
         */
        const val RING_SCHEME = "pubkyring://"
    }
}
