package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.MutableSessionProvider
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.parseSessionPayload
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [SessionRevalidator] backed by the Pubky FFI `revalidateSession` call.
 *
 * A [Mutex] ensures that concurrent revalidation attempts (e.g. two `putWithSession`
 * calls failing at the same time) coalesce into a single network round-trip.
 */
class SessionRevalidatorImpl(
    private val pubky: PubkyClient,
    private val sessionProvider: MutableSessionProvider,
    private val sessionStore: SecureSessionStore,
) : SessionRevalidator {

    private val mutex = Mutex()

    override suspend fun revalidate(): Result<Session> = mutex.withLock {
        runCatching {
            val current = sessionProvider.current()
                ?: error("Cannot revalidate: no active session")
            Log.d(TAG, "revalidating session for ${current.identity.pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
            val json = pubky.revalidateSession(current.sessionSecret).getOrThrow()
            val refreshed = parseSessionPayload(json, loopkyJson)
            sessionStore.save(refreshed)
            sessionProvider.set(refreshed)
            Log.d(TAG, "session revalidated successfully")
            refreshed
        }.onFailure {
            Log.e(TAG, "session revalidation failed: ${it.message}", it)
        }
    }

    companion object {
        private const val TAG = "Loopky/SessionRevalidator"
        private const val PUBKY_LOG_PREFIX_LEN = 8
    }
}
