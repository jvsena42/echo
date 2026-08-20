package com.github.jvsena42.loopky.data.storage

import kotlinx.coroutines.flow.Flow

/**
 * Platform-keystore-backed store for the user's own Unsplash access key. A credential, so it goes
 * through the keystore/keychain rather than [AppPreferences], which is explicitly for non-secrets.
 *
 * Deliberately kept in its own vault service ([SECRETS_SERVICE_NAME]) rather than sharing
 * [SESSION_SERVICE_NAME]: signing out clears the session, and it must not take the user's API key
 * with it.
 *
 * The value never leaves this store except into
 * [com.github.jvsena42.loopky.data.unsplash.UnsplashClient]'s `Authorization` header. It is never
 * logged, never put in a URL, and never read back into an editable field — the UI only ever sees
 * the four-character suffix from
 * [com.github.jvsena42.loopky.data.unsplash.maskedKeySuffix].
 */
interface UnsplashKeyStore {
    /** The user's key, blank when none is stored. Emits the current value immediately, then changes. */
    val key: Flow<String>

    suspend fun save(key: String)

    suspend fun clear()
}

internal const val SECRETS_SERVICE_NAME = "loopky.secrets"
internal const val UNSPLASH_KEY_STORAGE_KEY = "unsplash.access_key.v1"
