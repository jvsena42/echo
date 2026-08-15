package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.MutableSessionProvider
import com.github.jvsena42.loopky.data.pubky.ProfileDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.isNetworkFailure
import com.github.jvsena42.loopky.data.pubky.parseSessionPayload
import com.github.jvsena42.loopky.data.pubky.toDomain
import com.github.jvsena42.loopky.data.repository.AuthFlowHandle
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        profileCacheLock.withLock { profileCache.clear() }
    }

    override suspend fun beginSignIn(capabilities: String): Result<AuthFlowHandle> {
        Log.d(TAG, "beginSignIn: capabilities=$capabilities")
        return pubky.startAuthFlow(capabilities)
            .onSuccess { Log.d(TAG, "beginSignIn: startAuthFlow ok — authUrl=$it") }
            .onFailure {
                Log.e(TAG, "beginSignIn: startAuthFlow FAILED — ${it::class.simpleName}: ${it.message}", it)
            }
            .map { authUrl -> RingAuthFlowHandle(authUrl.withRingCallbacks()) }
    }

    /**
     * Append Pubky Ring return-callback params so Ring re-opens Loopky after the user approves
     * (the session itself still arrives over the relay via [AuthFlowHandle.complete]). Ring opens
     * the matching `x-*` deeplink; [CALLBACK_URL] is registered in the platform manifest/Info.plist.
     */
    private fun String.withRingCallbacks(): String {
        val separator = if (contains('?')) "&" else "?"
        val cb = encodeUriComponent(CALLBACK_URL)
        return "$this${separator}x-success=$cb&x-cancel=$cb&x-error=$cb&x-source=$CALLBACK_SOURCE"
    }

    /** Minimal percent-encoder for the reserved characters in [CALLBACK_URL]. */
    private fun encodeUriComponent(value: String): String = buildString {
        for (ch in value) {
            if (ch.isLetterOrDigit() || ch in "-_.~") {
                append(ch)
            } else {
                for (b in ch.toString().encodeToByteArray()) {
                    val byte = b.toInt() and BYTE_MASK
                    append('%')
                    append((byte shr NIBBLE_BITS).toString(HEX_RADIX).uppercase())
                    append((byte and LOW_NIBBLE_MASK).toString(HEX_RADIX).uppercase())
                }
            }
        }
    }

    private inner class RingAuthFlowHandle(override val authUrl: String) : AuthFlowHandle {
        override suspend fun complete(): Result<Session> = runCatching {
            Log.d(TAG, "complete: awaiting Pubky Ring approval")
            val sessionJson = awaitApprovalWithRetry().getOrThrow()
            Log.d(TAG, "complete: got session payload=$sessionJson")

            val session = parseSessionPayload(sessionJson, loopkyJson)
            Log.d(TAG, "complete: parsed session pubky=${session.identity.pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
            sessionStore.save(session)
            sessionProvider.set(session)
            Log.d(TAG, "complete: session saved")

            // Best-effort profile fetch — don't fail sign-in if this fails
            val profile = runCatching { fetchProfile(session.identity.pubky).getOrNull() }.getOrNull()
            if (profile != null && (profile.displayName != null || profile.bio != null)) {
                val enriched = session.copy(
                    identity = session.identity.copy(
                        displayName = profile.displayName,
                        avatarUrl = profile.avatarUrl,
                        bio = profile.bio,
                    ),
                )
                sessionStore.save(enriched)
                sessionProvider.set(enriched)
                Log.d(TAG, "complete: session enriched with profile")
                return@runCatching enriched
            }

            session
        }.onFailure {
            Log.e(TAG, "complete: FAILED — ${it::class.simpleName}: ${it.message}", it)
        }
    }

    /**
     * `awaitAuthApproval` polls the auth relay, and that poll is flaky in practice — signing in
     * on a device failed twice with `HTTP transport error: error sending request for url
     * (https://httprelay.pubky.app/inbox/…)` and then succeeded on an identical third attempt.
     * Retry only transport failures; a declined or expired request is terminal and retrying it
     * would just make the user wait.
     */
    private suspend fun awaitApprovalWithRetry(): Result<String> {
        repeat(AUTH_RETRIES) { attempt ->
            val result = pubky.awaitAuthApproval()
            if (result.isSuccess) return result

            val error = result.exceptionOrNull() ?: return result
            if (!error.isNetworkFailure()) {
                Log.e(TAG, "awaitApproval: terminal failure — ${error::class.simpleName}: ${error.message}", error)
                return result
            }
            Log.w(TAG, "awaitApproval: transport failure on attempt ${attempt + 1}, retrying — ${error.message}")
            delay(AUTH_RETRY_DELAY_MS)
        }
        return pubky.awaitAuthApproval().onFailure {
            Log.e(TAG, "awaitApproval: FAILED after $AUTH_RETRIES retries — ${it::class.simpleName}: ${it.message}", it)
        }
    }

    /**
     * Successful lookups only. A miss (no profile published yet) stays uncached so a profile
     * created later still shows up without restarting the app.
     */
    private val profileCache = mutableMapOf<String, PubkyIdentity>()
    private val profileCacheLock = Mutex()

    override suspend fun fetchProfile(pubky: String, forceRefresh: Boolean): Result<PubkyIdentity> {
        if (!forceRefresh) {
            profileCacheLock.withLock { profileCache[pubky] }?.let { return Result.success(it) }
        }
        return runCatching {
            Log.d(TAG, "fetchProfile: pubky=${pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
            val json = this.pubky.get(PubkyPaths.profile(pubky)).getOrThrow()
            val dto = loopkyJson.decodeFromString<ProfileDto>(json)
            dto.toDomain(pubky).also { cacheProfile(it) }
        }.onFailure {
            Log.e(TAG, "fetchProfile: FAILED — ${it::class.simpleName}: ${it.message}", it)
        }
    }

    private suspend fun cacheProfile(identity: PubkyIdentity) {
        profileCacheLock.withLock { profileCache[identity.pubky] = identity }
    }

    override suspend fun updateProfile(name: String?, bio: String?): Result<PubkyIdentity> = runCatching {
        val session = sessionProvider.current() ?: error("Not signed in")
        val currentPubky = session.identity.pubky
        Log.d(TAG, "updateProfile: pubky=${currentPubky.take(PUBKY_LOG_PREFIX_LEN)}…")

        val dto = ProfileDto(
            name = name,
            bio = bio,
            image = session.identity.avatarUrl,
        )
        val json = loopkyJson.encodeToString(ProfileDto.serializer(), dto)
        val putResult = pubky.putWithSession(PubkyPaths.profile(currentPubky), json, session.sessionSecret)

        if (putResult.isFailure) {
            val err = putResult.exceptionOrNull()
            if (err?.message?.contains("403") == true || err?.message?.contains("write access") == true) {
                error("Please sign out and sign back in to enable profile editing.")
            }
            putResult.getOrThrow()
        }

        val updatedIdentity = dto.toDomain(currentPubky)
        val updatedSession = session.copy(identity = updatedIdentity)
        sessionStore.save(updatedSession)
        sessionProvider.set(updatedSession)
        // Keep the cache honest — otherwise every other screen keeps showing the old name.
        cacheProfile(updatedIdentity)
        Log.d(TAG, "updateProfile: saved")
        updatedIdentity
    }.onFailure {
        Log.e(TAG, "updateProfile: FAILED — ${it::class.simpleName}: ${it.message}", it)
    }

    companion object {
        private const val TAG = "Loopky/IdentityRepo"
        private const val PUBKY_LOG_PREFIX_LEN = 8

        // Percent-encoding bit math (see [encodeUriComponent]).
        private const val HEX_RADIX = 16
        private const val BYTE_MASK = 0xFF
        private const val LOW_NIBBLE_MASK = 0x0F
        private const val NIBBLE_BITS = 4

        /** Deeplink Pubky Ring re-opens after approval; registered in the platform manifest. */
        private const val CALLBACK_URL = "loopky://login-callback"
        private const val CALLBACK_SOURCE = "Loopky"

        /** Extra attempts at the flaky auth-relay poll before giving up. */
        private const val AUTH_RETRIES = 2
        private const val AUTH_RETRY_DELAY_MS = 1_500L
    }
}
