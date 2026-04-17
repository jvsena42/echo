package com.github.jvsena42.eco.data.repository.impl

import com.github.jvsena42.eco.data.pubky.MutableSessionProvider
import com.github.jvsena42.eco.data.pubky.PubkyClient
import com.github.jvsena42.eco.data.pubky.parseSessionPayload
import com.github.jvsena42.eco.data.repository.AuthFlowHandle
import com.github.jvsena42.eco.data.repository.IdentityRepository
import com.github.jvsena42.eco.data.storage.SecureSessionStore
import com.github.jvsena42.eco.domain.model.Session
import com.github.jvsena42.eco.util.Log

/**
 * [IdentityRepository] backed by [PubkyClient] and [SecureSessionStore].
 *
 * Sign-in is a two-phase deeplink flow: [beginSignIn] asks the FFI for an auth URL, the caller
 * opens the URL in Pubky Ring, then [AuthFlowHandle.complete] blocks on `awaitAuthApproval` and
 * finalises the session.
 */
class IdentityRepositoryImpl(
    private val pubky: PubkyClient,
    private val sessionStore: SecureSessionStore,
    private val sessionProvider: MutableSessionProvider,
) : IdentityRepository {

    override suspend fun currentSession(): Session? = sessionProvider.current()

    override suspend fun loadPersistedSession(): Session? {
        val persisted = sessionStore.load() ?: return null
        sessionProvider.set(persisted)
        return persisted
    }

    override suspend fun signIn(): Result<Session> {
        val handle = beginSignIn().getOrElse { return Result.failure(it) }
        return handle.complete()
    }

    override suspend fun signOut(): Result<Unit> = runCatching {
        val current = sessionProvider.current()
        if (current != null) {
            pubky.signOut(current.sessionSecret)
        }
        sessionStore.clear()
        sessionProvider.set(null)
    }

    override suspend fun beginSignIn(capabilities: String): Result<AuthFlowHandle> {
        Log.d(TAG, "beginSignIn: capabilities=$capabilities")
        return pubky.startAuthFlow(capabilities)
            .onSuccess { Log.d(TAG, "beginSignIn: startAuthFlow ok — authUrl=$it") }
            .onFailure {
                Log.e(TAG, "beginSignIn: startAuthFlow FAILED — ${it::class.simpleName}: ${it.message}", it)
            }
            .map { authUrl -> RingAuthFlowHandle(authUrl) }
    }

    private inner class RingAuthFlowHandle(override val authUrl: String) : AuthFlowHandle {
        override suspend fun complete(): Result<Session> = runCatching {
            Log.d(TAG, "complete: awaiting Pubky Ring approval")
            val sessionJson = pubky.awaitAuthApproval()
                .onFailure {
                    Log.e(TAG, "complete: awaitAuthApproval FAILED — ${it::class.simpleName}: ${it.message}", it)
                }
                .getOrThrow()
            Log.d(TAG, "complete: got session payload=$sessionJson")

            val session = parseSessionPayload(sessionJson, echoJson)
            Log.d(TAG, "complete: parsed session pubky=${session.identity.pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
            sessionStore.save(session)
            sessionProvider.set(session)
            Log.d(TAG, "complete: session saved")
            session
        }.onFailure {
            Log.e(TAG, "complete: FAILED — ${it::class.simpleName}: ${it.message}", it)
        }
    }

    companion object {
        private const val TAG = "Echo/IdentityRepo"
        private const val PUBKY_LOG_PREFIX_LEN = 8
    }
}
