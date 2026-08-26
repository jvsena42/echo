package com.github.jvsena42.loopky.data.storage

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
 * iOS [LocalKeyStore] backed by the Keychain through Liftric KVault.
 *
 * As on Android, only the custody is held in memory; the key is read per call.
 */
internal class IosLocalKeyStore : LocalKeyStore {
    private val vault: KVault = KVault(serviceName = SECRETS_SERVICE_NAME, accessGroup = null)

    private val _custody = MutableStateFlow(readKey()?.toCustody() ?: KeyCustody.External)
    override val custody: Flow<KeyCustody> = _custody.asStateFlow()

    override suspend fun save(key: LocalKey) {
        withContext(Dispatchers.Default) {
            vault.set(LOCAL_KEY_STORAGE_KEY, sessionStoreJson.encodeToString(key))
        }
        _custody.update { key.toCustody() }
    }

    override suspend fun current(): LocalKey? = withContext(Dispatchers.Default) { readKey() }

    override suspend fun markBackedUp(method: BackupMethod) {
        val updated = withContext(Dispatchers.Default) {
            val existing = readKey() ?: return@withContext null
            if (method in existing.backedUpBy) return@withContext existing
            existing.copy(backedUpBy = existing.backedUpBy + method).also {
                vault.set(LOCAL_KEY_STORAGE_KEY, sessionStoreJson.encodeToString(it))
            }
        } ?: return
        _custody.update { updated.toCustody() }
    }

    override suspend fun markRegistered() {
        val updated = withContext(Dispatchers.Default) {
            val existing = readKey() ?: return@withContext null
            if (existing.registered) return@withContext existing
            existing.copy(registered = true).also {
                vault.set(LOCAL_KEY_STORAGE_KEY, sessionStoreJson.encodeToString(it))
            }
        } ?: return
        _custody.update { updated.toCustody() }
    }

    override suspend fun clear() {
        withContext(Dispatchers.Default) { vault.deleteObject(LOCAL_KEY_STORAGE_KEY) }
        _custody.update { KeyCustody.External }
    }

    private fun readKey(): LocalKey? {
        val json = vault.string(LOCAL_KEY_STORAGE_KEY) ?: return null
        return runCatching { sessionStoreJson.decodeFromString<LocalKey>(json) }.getOrNull()
    }
}
