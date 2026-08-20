package com.github.jvsena42.loopky.data.storage

import android.content.Context
import com.liftric.kvault.KVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Android [UnsplashKeyStore] backed by `EncryptedSharedPreferences` through Liftric KVault, with
 * the master encryption key in the Android Keystore — the same door [AndroidSecureSessionStore]
 * uses, behind a different service name.
 */
class AndroidUnsplashKeyStore(context: Context) : UnsplashKeyStore {
    private val vault: KVault = KVault(context, SECRETS_SERVICE_NAME)

    // Mirrored into a StateFlow for the same reason AndroidAppPreferences does it: only this class
    // writes the value, so collectors get the current key without a decrypt on every read.
    private val _key = MutableStateFlow(vault.string(UNSPLASH_KEY_STORAGE_KEY).orEmpty())
    override val key: Flow<String> = _key.asStateFlow()

    override suspend fun save(key: String) {
        withContext(Dispatchers.IO) { vault.set(UNSPLASH_KEY_STORAGE_KEY, key) }
        _key.update { key }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { vault.deleteObject(UNSPLASH_KEY_STORAGE_KEY) }
        _key.update { "" }
    }
}
