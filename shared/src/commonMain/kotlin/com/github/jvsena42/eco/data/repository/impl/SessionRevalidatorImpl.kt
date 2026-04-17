package com.github.jvsena42.eco.data.repository.impl

import com.github.jvsena42.eco.data.pubky.MutableSessionProvider
import com.github.jvsena42.eco.data.pubky.PubkyClient
import com.github.jvsena42.eco.data.pubky.SessionRevalidator
import com.github.jvsena42.eco.data.pubky.parseSessionPayload
import com.github.jvsena42.eco.data.storage.SecureSessionStore
import com.github.jvsena42.eco.domain.model.Session
import com.github.jvsena42.eco.util.Log
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
            Log.d(TAG, "revalidating session for ${current.identity.pubky.take(8)}…")
            val json = pubky.revalidateSession(current.sessionSecret).getOrThrow()
            val refreshed = parseSessionPayload(json, echoJson)
            sessionStore.save(refreshed)
            sessionProvider.set(refreshed)
            Log.d(TAG, "session revalidated successfully")
            refreshed
        }.onFailure {
            Log.e(TAG, "session revalidation failed: ${it.message}", it)
        }
    }

    companion object {
        private const val TAG = "Echo/SessionRevalidator"
    }
}
