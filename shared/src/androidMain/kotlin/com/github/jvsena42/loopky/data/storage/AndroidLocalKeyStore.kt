package com.github.jvsena42.loopky.data.storage

import android.content.Context
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.liftric.kvault.KVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Android [LocalKeyStore] backed by `EncryptedSharedPreferences` through Liftric KVault, in the
 * same secrets vault as [AndroidSignupTokenStore] and [AndroidUnsplashKeyStore].
 *
 * Only the *custody* is held in memory. The key itself is read from the vault per call, so the
 * secret is resident for the duration of a caller rather than the life of the process.
 */
internal class AndroidLocalKeyStore(context: Context) : LocalKeyStore {
    private val vault: KVault? = openVaultOrNull(context, SECRETS_SERVICE_NAME)

    private val _custody = MutableStateFlow(readKey()?.toCustody() ?: KeyCustody.External)
    override val custody: Flow<KeyCustody> = _custody.asStateFlow()

    override suspend fun save(key: LocalKey) {
        withContext(Dispatchers.IO) {
            vault?.set(LOCAL_KEY_STORAGE_KEY, sessionStoreJson.encodeToString(key))
        }
        _custody.update { key.toCustody() }
    }

    override suspend fun current(): LocalKey? = withContext(Dispatchers.IO) { readKey() }

    override suspend fun markBackedUp(method: BackupMethod) {
        val updated = withContext(Dispatchers.IO) {
            val existing = readKey() ?: return@withContext null
            // Already recorded: skip the write rather than rewriting an identical blob, so a screen
            // that marks on every entry does not churn the keystore.
            if (method in existing.backedUpBy) return@withContext existing
            existing.copy(backedUpBy = existing.backedUpBy + method).also {
                vault?.set(LOCAL_KEY_STORAGE_KEY, sessionStoreJson.encodeToString(it))
            }
        } ?: return
        _custody.update { updated.toCustody() }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { vault?.deleteObject(LOCAL_KEY_STORAGE_KEY) }
        _custody.update { KeyCustody.External }
    }

    // A blob we can no longer decode is treated as no key rather than a crash, matching
    // AndroidSignupTokenStore. This also runs in a field initialiser, so a throw here would land
    // the moment Koin first resolves the store — during onboarding, on a device that did nothing
    // wrong. The user signs in again; a throw would make the app unopenable.
    private fun readKey(): LocalKey? {
        val json = vault.stringOrNull(LOCAL_KEY_STORAGE_KEY) ?: return null
        return runCatching { sessionStoreJson.decodeFromString<LocalKey>(json) }.getOrNull()
    }
}
