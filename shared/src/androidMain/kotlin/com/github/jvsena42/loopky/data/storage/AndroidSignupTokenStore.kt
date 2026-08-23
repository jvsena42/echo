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
 * Android [SignupTokenStore] backed by `EncryptedSharedPreferences` through Liftric KVault, in the
 * same secrets vault as [AndroidUnsplashKeyStore] so that signing out cannot discard it.
 */
class AndroidSignupTokenStore(context: Context) : SignupTokenStore {
    private val vault: KVault? = openVaultOrNull(context, SECRETS_SERVICE_NAME)

    private val _pending = MutableStateFlow(readPending())
    override val pending: Flow<PendingSignup?> = _pending.asStateFlow()

    override suspend fun save(pending: PendingSignup) {
        withContext(Dispatchers.IO) {
            vault?.set(SIGNUP_TOKEN_STORAGE_KEY, sessionStoreJson.encodeToString(pending))
        }
        _pending.update { pending }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { vault?.deleteObject(SIGNUP_TOKEN_STORAGE_KEY) }
        _pending.update { null }
    }

    // A blob we can no longer decode is treated as no token rather than a crash, matching
    // AndroidSecureSessionStore. The cost is bounded: the user re-verifies, where a throw here
    // would make the app unopenable — and this runs in a field initialiser, so "here" is the
    // moment Koin first resolves the store.
    private fun readPending(): PendingSignup? {
        val json = vault.stringOrNull(SIGNUP_TOKEN_STORAGE_KEY) ?: return null
        return runCatching { sessionStoreJson.decodeFromString<PendingSignup>(json) }.getOrNull()
    }
}
