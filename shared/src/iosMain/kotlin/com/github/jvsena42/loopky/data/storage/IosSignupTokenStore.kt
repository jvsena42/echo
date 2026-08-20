package com.github.jvsena42.loopky.data.storage

import com.liftric.kvault.KVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** iOS [SignupTokenStore] backed by the Keychain through Liftric KVault. */
class IosSignupTokenStore : SignupTokenStore {
    private val vault: KVault = KVault(serviceName = SECRETS_SERVICE_NAME, accessGroup = null)

    private val _pending = MutableStateFlow(readPending())
    override val pending: Flow<PendingSignup?> = _pending.asStateFlow()

    override suspend fun save(pending: PendingSignup) {
        withContext(Dispatchers.Default) {
            vault.set(SIGNUP_TOKEN_STORAGE_KEY, sessionStoreJson.encodeToString(pending))
        }
        _pending.update { pending }
    }

    override suspend fun clear() {
        withContext(Dispatchers.Default) { vault.deleteObject(SIGNUP_TOKEN_STORAGE_KEY) }
        _pending.update { null }
    }

    private fun readPending(): PendingSignup? {
        val json = vault.string(SIGNUP_TOKEN_STORAGE_KEY) ?: return null
        return runCatching { sessionStoreJson.decodeFromString<PendingSignup>(json) }.getOrNull()
    }
}
