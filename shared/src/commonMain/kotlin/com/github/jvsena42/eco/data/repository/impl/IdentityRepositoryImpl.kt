package com.github.jvsena42.eco.data.repository.impl

import com.github.jvsena42.eco.data.pubky.MutableSessionProvider
import com.github.jvsena42.eco.data.pubky.PubkyClient
import com.github.jvsena42.eco.data.repository.AuthFlowHandle
import com.github.jvsena42.eco.data.repository.IdentityRepository
import com.github.jvsena42.eco.data.storage.SecureSessionStore
import com.github.jvsena42.eco.domain.model.Capability
import com.github.jvsena42.eco.domain.model.PubkyIdentity
import com.github.jvsena42.eco.domain.model.Session
import com.github.jvsena42.eco.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

            val session = parseSessionPayload(sessionJson)
            Log.d(TAG, "complete: parsed session pubky=${session.identity.pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
            sessionStore.save(session)
            sessionProvider.set(session)
            Log.d(TAG, "complete: session saved")
            session
        }.onFailure {
            Log.e(TAG, "complete: FAILED — ${it::class.simpleName}: ${it.message}", it)
        }
    }

    /**
     * Session payload shape from `pubky-core-ffi-fork::utils::session_to_json_with_secret`:
     *
     * ```json
     * { "pubky": "...", "capabilities": ["/pub/echo/:rw"], "session_secret": "..." }
     * ```
     *
     * Note: the FFI does NOT include a `homeserver` field on the wire. We resolve the
     * homeserver lazily on first use (or leave it empty here and fill it in elsewhere
     * when needed). Extra/aliased field names are tolerated so a future FFI bump that
     * adds `homeserver` continues to work without a code change.
     */
    private fun parseSessionPayload(payload: String): Session {
        val obj: JsonObject = echoJson.parseToJsonElement(payload).jsonObject

        val pubkey = obj.stringField("pubky", "public_key", "publicKey")
            ?: error("session payload missing 'pubky'")
        val secret = obj.stringField("session_secret", "sessionSecret", "secret")
            ?: error("session payload missing 'session_secret'")
        val homeserver = obj.stringField("homeserver", "home_server").orEmpty()
        val caps = obj["capabilities"]
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.map(::Capability)
            ?: emptyList()

        return Session(
            identity = PubkyIdentity(
                pubky = pubkey,
                displayName = null,
                avatarUrl = null,
                bio = null,
            ),
            sessionSecret = secret,
            capabilities = caps,
            homeserver = homeserver,
        )
    }

    private fun JsonObject.stringField(vararg names: String): String? {
        for (name in names) {
            val v = this[name]?.jsonPrimitive?.contentOrNull
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    companion object {
        private const val TAG = "Echo/IdentityRepo"
        private const val PUBKY_LOG_PREFIX_LEN = 8
    }
}
