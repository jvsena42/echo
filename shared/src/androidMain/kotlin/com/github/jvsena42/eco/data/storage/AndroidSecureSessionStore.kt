package com.github.jvsena42.eco.data.storage

import android.content.Context
import com.github.jvsena42.eco.domain.model.Session
import com.liftric.kvault.KVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * Android [SecureSessionStore] backed by `EncryptedSharedPreferences` through Liftric KVault.
 * The master encryption key is held in the Android Keystore.
 */
class AndroidSecureSessionStore(context: Context) : SecureSessionStore {
    private val vault: KVault = KVault(context, SESSION_SERVICE_NAME)

    override suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        val json = sessionStoreJson.encodeToString(StoredSession.fromDomain(session))
        vault.set(SESSION_STORAGE_KEY, json)
        Unit
    }

    override suspend fun load(): Session? = withContext(Dispatchers.IO) {
        val json = vault.string(SESSION_STORAGE_KEY) ?: return@withContext null
        runCatching {
            sessionStoreJson.decodeFromString<StoredSession>(json).toDomain()
        }.getOrNull()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        vault.deleteObject(SESSION_STORAGE_KEY)
        Unit
    }
}
