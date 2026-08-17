package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.MutableSessionProvider
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.parseSessionPayload
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [SessionRevalidator] backed by the Pubky FFI `revalidateSession` call.
 *
 * A [Mutex] serializes concurrent revalidation attempts, and the stale-secret check inside it
 * makes them genuinely *coalesce*: waiters that were failing on a secret which has since been
 * replaced take the refreshed session instead of issuing their own round-trip. The lock alone did
 * not do this — every waiter re-ran the FFI call in turn, which matters much more now that
 * publishing fires many writes concurrently and a expiry fails all of them at once.
 */
class SessionRevalidatorImpl(
    private val pubky: PubkyClient,
    private val sessionProvider: MutableSessionProvider,
    private val sessionStore: SecureSessionStore,
) : SessionRevalidator {

    private val mutex = Mutex()

    override suspend fun revalidate(): Result<Session> {
        // Captured before the lock: this is the secret the caller found wanting.
        val staleSecret = sessionProvider.current()?.sessionSecret
        return mutex.withLock {
            val now = sessionProvider.current()
            if (now != null && staleSecret != null && now.sessionSecret != staleSecret) {
                Log.d(TAG, "session already refreshed by another caller; reusing it")
                return@withLock Result.success(now)
            }
            revalidateLocked()
        }
    }

    private suspend fun revalidateLocked(): Result<Session> =
        runSuspendCatching {
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

    companion object {
        private const val TAG = "Loopky/SessionRevalidator"
        private const val PUBKY_LOG_PREFIX_LEN = 8
    }
}
