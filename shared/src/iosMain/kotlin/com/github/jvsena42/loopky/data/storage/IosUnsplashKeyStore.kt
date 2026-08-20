package com.github.jvsena42.loopky.data.storage

import com.liftric.kvault.KVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * iOS [UnsplashKeyStore] backed by the native Keychain (`kSecClassGenericPassword`) through
 * Liftric KVault, under its own service name so signing out cannot clear it.
 */
class IosUnsplashKeyStore : UnsplashKeyStore {
    private val vault: KVault = KVault(serviceName = SECRETS_SERVICE_NAME, accessGroup = null)

    private val _key = MutableStateFlow(vault.string(UNSPLASH_KEY_STORAGE_KEY).orEmpty())
    override val key: Flow<String> = _key.asStateFlow()

    override suspend fun save(key: String) {
        withContext(Dispatchers.Default) { vault.set(UNSPLASH_KEY_STORAGE_KEY, key) }
        _key.update { key }
    }

    override suspend fun clear() {
        withContext(Dispatchers.Default) { vault.deleteObject(UNSPLASH_KEY_STORAGE_KEY) }
        _key.update { "" }
    }
}
