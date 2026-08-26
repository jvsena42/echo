package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.MintedKeypair
import com.github.jvsena42.loopky.data.pubky.MutableSessionProvider
import com.github.jvsena42.loopky.data.pubky.ProfileDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.asSignupUrl
import com.github.jvsena42.loopky.data.pubky.isNoHomeserverRecord
import com.github.jvsena42.loopky.data.pubky.keypairFromMnemonic
import com.github.jvsena42.loopky.data.pubky.keypairFromSecretKey
import com.github.jvsena42.loopky.data.pubky.parseSessionPayload
import com.github.jvsena42.loopky.data.pubky.redactAuthUrl
import com.github.jvsena42.loopky.data.pubky.redactSessionPayload
import com.github.jvsena42.loopky.data.pubky.toDomain
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.AuthFlowHandle
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.storage.LocalKey
import com.github.jvsena42.loopky.data.storage.LocalKeyStore
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.HomeserverLookup
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.domain.model.KeySource
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.encodeUriComponent
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [IdentityRepository] backed by [PubkyClient] and [SecureSessionStore].
 *
 * Sign-in is a two-phase deeplink flow: [beginSignIn] asks the FFI for an auth URL, the caller
 * opens the URL in Pubky Ring, then [AuthFlowHandle.complete] blocks on `awaitAuthApproval` and
 * finalises the session.
 */
internal class IdentityRepositoryImpl(
    private val pubky: PubkyClient,
    private val sessionStore: SecureSessionStore,
    private val sessionProvider: MutableSessionProvider,
    private val tagRepository: TagRepository,
    /**
     * The homeserver sweep behind [deleteAccount], which needs half a dozen collaborators nothing
     * else here touches. Injecting it is safe in both directions: it reaches `DeckRepository`,
     * which depends on the session *provider* rather than on this repository, so there is no Koin
     * cycle to create.
     */
    private val eraser: AccountEraser,
    private val localKeyStore: LocalKeyStore,
) : IdentityRepository {

    override val keyCustody: Flow<KeyCustody> = localKeyStore.custody

    override suspend fun currentSession(): Session? = sessionProvider.current()

    override suspend fun loadPersistedSession(): Session? {
        val persisted = sessionStore.load() ?: return null
        sessionProvider.set(persisted)
        selfTagAsLoopkyUser(persisted)
        return persisted
    }

    override suspend fun signIn(): Result<Session> {
        val handle = beginSignIn().getOrElse { return Result.failure(it) }
        return handle.complete()
    }

    override suspend fun signOut(): Result<Unit> = runSuspendCatching {
        val current = sessionProvider.current()
        if (current != null) {
            pubky.signOut(current.sessionSecret)
        }
        sessionStore.clear()
        // The key goes with the session: a signed-out device holding a secret key is a credential
        // nobody is watching. Safe to do unguarded *today* because the only keys that exist are
        // restored ones, and their owner demonstrably holds the phrase or file they restored from.
        // Minting a key here (#147 phase 3) introduces the first key nobody has a copy of, and
        // that is what the sign-out confirm is for — it arrives with the code that creates the risk.
        localKeyStore.clear()
        sessionProvider.set(null)
        selfTaggedThisProcess = false
        profileCacheLock.withLock { profileCache.clear() }
    }

    override suspend fun beginSignIn(capabilities: String): Result<AuthFlowHandle> {
        Log.d(TAG, "beginSignIn: capabilities=$capabilities")
        return pubky.startAuthFlow(capabilities)
            .onSuccess { Log.d(TAG, "beginSignIn: startAuthFlow ok — authUrl=${it.redactAuthUrl()}") }
            .onFailure {
                Log.e(TAG, "beginSignIn: startAuthFlow FAILED — ${it::class.simpleName}: ${it.message}", it)
            }
            .map { authUrl -> RingAuthFlowHandle(authUrl.withRingCallbacks()) }
    }

    override suspend fun beginSignUp(
        homeserverPubky: String,
        signupToken: String,
        capabilities: String,
    ): Result<AuthFlowHandle> {
        Log.d(TAG, "beginSignUp: capabilities=$capabilities homeserver=$homeserverPubky")
        return pubky.startAuthFlow(capabilities)
            // `mapCatching`, not `runSuspendCatching`: `asSignupUrl` is pure and synchronous, so
            // it cannot swallow a cancellation.
            .mapCatching { it.asSignupUrl(homeserverPubky, signupToken) }
            .onSuccess { Log.d(TAG, "beginSignUp: startAuthFlow ok — authUrl=${it.redactAuthUrl()}") }
            .onFailure {
                Log.e(TAG, "beginSignUp: FAILED — ${it::class.simpleName}: ${it.message}", it)
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

    /**
     * A single-use handle: the FFI's auth flow is global state that `awaitAuthApproval` *takes*,
     * so the first poll — successful or not — consumes it, and a second poll on the same handle can
     * only answer "No auth flow in progress". A failed approval therefore has no in-place retry;
     * recovering means a fresh [beginSignIn], which mints a new secret and a new `pubkyauth://` URL
     * and so requires the user to approve in Ring again (#59).
     */
    private inner class RingAuthFlowHandle(override val authUrl: String) : AuthFlowHandle {
        override suspend fun complete(): Result<Session> = runSuspendCatching {
            Log.d(TAG, "complete: awaiting Pubky Ring approval")
            val sessionJson = pubky.awaitAuthApproval().getOrThrow()
            Log.d(TAG, "complete: got session payload=${sessionJson.redactSessionPayload()}")

            val session = parseSessionPayload(sessionJson, loopkyJson)
            Log.d(TAG, "complete: parsed session pubky=${session.identity.pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
            // Shared with the local-key paths on purpose: everything after "we have a session" is
            // identical, and letting the two drift is how one of them stops self-tagging and
            // quietly falls out of the only global directory Loopky has.
            persistSession(session).also { Log.d(TAG, "complete: session saved") }
        }.onFailure {
            Log.e(TAG, "complete: FAILED — ${it::class.simpleName}: ${it.message}", it)
        }
    }

    // --- Local keys ------------------------------------------------------------

    override suspend fun derivePubky(source: KeySource): Result<String> =
        deriveKeypair(source).map { it.pubky }

    override suspend fun lookupHomeserver(pubky: String): HomeserverLookup {
        Log.d(TAG, "lookupHomeserver: ${pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
        val result = runSuspendCatching { this.pubky.getHomeserver(pubky).getOrThrow() }
        return result.fold(
            onSuccess = { HomeserverLookup.Registered(it.trim()) },
            onFailure = { error ->
                // The FFI reports "this pubky published no homeserver record" and "the DHT did not
                // answer" through the same call, and only the first is a fact about the key. The
                // classifier ordering in toErrorReason is what keeps them apart.
                if (error.isNoHomeserverRecord()) {
                    Log.d(TAG, "lookupHomeserver: no record for this pubky")
                    HomeserverLookup.NoRecord
                } else {
                    Log.w(TAG, "lookupHomeserver: could not check — ${error::class.simpleName}")
                    HomeserverLookup.CouldNotCheck(error.toErrorReason())
                }
            },
        )
    }

    override suspend fun signInWithKey(
        source: KeySource,
        knownHomeserver: String?,
    ): Result<Session> = runSuspendCatching {
        val keypair = deriveKeypair(source).getOrThrow()
        Log.d(TAG, "signInWithKey: signing in as ${keypair.pubky.take(PUBKY_LOG_PREFIX_LEN)}…")

        val payload = pubky.signIn(keypair.secretKeyHex).getOrThrow()
        val session = parseSessionPayload(payload, loopkyJson)

        // The homeserver we actually landed on is not in the payload — the grant flow's session
        // JSON carries no `homeserver` field at all, so parseSessionPayload defaults it to "".
        // Resolving it here keeps Settings from showing "Unknown" for every restored account.
        // Prefer what the caller already learned: on the restore path the pre-flight resolved this
        // moments ago, and asking the DHT twice costs a second round trip on the critical path.
        val resolved = session.homeserver.takeIf { it.isNotBlank() }
            ?: knownHomeserver
            ?: (lookupHomeserver(keypair.pubky) as? HomeserverLookup.Registered)?.homeserverPubky
            ?: ""

        // The key is persisted only once the homeserver has accepted it. Storing it earlier would
        // leave a device holding a key for an account it could not sign into.
        localKeyStore.save(
            LocalKey(
                secretKeyHex = keypair.secretKeyHex,
                pubky = keypair.pubky,
                mnemonic = keypair.mnemonic,
                // A restored key is already backed up, and recording that is not a shortcut: the
                // user just demonstrated they hold the phrase or the file by signing in with it.
                // Nagging them to back up what they only just typed in would be describing a risk
                // that does not exist. A *minted* key is the un-backed-up case.
                backedUpBy = setOf(source.backupMethod()),
            ),
        )
        persistSession(session.copy(homeserver = resolved))
    }.onFailure {
        Log.e(TAG, "signInWithKey: FAILED — ${it::class.simpleName}: ${it.message}", it)
    }

    /**
     * Derive a keypair from [source] off the caller's thread.
     *
     * The FFI's key calls are synchronous and un-dispatched, and a recovery file is Argon2id, so
     * this hop is what keeps a ViewModel from running a key-derivation function on the main thread.
     */
    private suspend fun deriveKeypair(source: KeySource): Result<MintedKeypair> =
        withContext(Dispatchers.Default) {
            when (source) {
                is KeySource.Phrase -> pubky.keypairFromMnemonic(source.mnemonic)
                is KeySource.RecoveryFile ->
                    pubky.decryptRecoveryFile(source.base64, source.passphrase)
                        .mapCatching { pubky.keypairFromSecretKey(it.trim()).getOrThrow() }
            }
        }

    /** What restoring from [this] proves the user already has a copy of. */
    private fun KeySource.backupMethod(): BackupMethod = when (this) {
        is KeySource.Phrase -> BackupMethod.RecoveryPhrase
        is KeySource.RecoveryFile -> BackupMethod.EncryptedFile
    }

    /**
     * Save a session, publish it, announce the account, and enrich it from the published profile.
     *
     * Extracted so the Ring path and the local-key paths cannot drift: everything after "we have a
     * session" is identical, and duplicating it is how one of them ends up not self-tagging and
     * quietly staying out of the only global directory Loopky has.
     */
    private suspend fun persistSession(session: Session): Session {
        sessionStore.save(session)
        sessionProvider.set(session)
        selfTagAsLoopkyUser(session)

        val profile = runSuspendCatching { fetchProfile(session.identity.pubky).getOrNull() }.getOrNull()
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
            return enriched
        }
        return session
    }

    /**
     * Announce this account as a Loopky user by tagging its own `profile.json` with
     * [ReservedTags.USER].
     *
     * This is the whole reason a stranger can find a brand-new account: with no backend, the tag
     * index is the only global directory Loopky has, and a user who tags nothing is invisible to
     * it no matter how many decks they publish (#40). Tagger and subject are the same account,
     * which is what makes the entry verifiable rather than someone's claim about someone else.
     *
     * Best-effort and idempotent — the tag id is derived from subject + label, so every write
     * lands on the same record. Once per process is enough; repeating it on each login and each
     * app start is how accounts that predate this get into the directory.
     */
    private suspend fun selfTagAsLoopkyUser(session: Session) {
        if (selfTaggedThisProcess) return
        val profileUri = PubkyUri(PubkyPaths.profile(session.identity.pubky))
        tagRepository.putReservedTag(profileUri, ReservedTags.USER)
            .onSuccess {
                selfTaggedThisProcess = true
                Log.d(TAG, "selfTag: ${ReservedTags.USER.value} written")
            }
            .onFailure { Log.w(TAG, "selfTag: FAILED — ${it.message}") }
    }

    /** Reset on sign-out so the next account announces itself too. */
    private var selfTaggedThisProcess = false

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
        return runSuspendCatching {
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

    override suspend fun updateProfile(name: String?, bio: String?): Result<PubkyIdentity> = runSuspendCatching {
        val session = sessionProvider.current() ?: error("Not signed in")
        val currentPubky = session.identity.pubky
        Log.d(TAG, "updateProfile: pubky=${currentPubky.take(PUBKY_LOG_PREFIX_LEN)}…")

        // The picture comes off the *published* profile, not the session. Sign-in only enriches
        // the session when the profile has a name or a bio (see RingAuthFlowHandle.complete), so
        // an account with a picture and neither carries avatarUrl = null — and echoing that back
        // here wrote `image: null`, deleting the user's avatar the first time they renamed
        // themselves. Falling back to the session covers a homeserver that cannot be reached.
        val publishedAvatar = fetchProfile(currentPubky, forceRefresh = true).getOrNull()?.avatarUrl
        val dto = ProfileDto(
            name = name,
            bio = bio,
            image = publishedAvatar ?: session.identity.avatarUrl,
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
        // The other moment the profile provably exists, so the other chance to get into the
        // directory if the write at login failed.
        selfTagAsLoopkyUser(updatedSession)
        Log.d(TAG, "updateProfile: saved")
        updatedIdentity
    }.onFailure {
        Log.e(TAG, "updateProfile: FAILED — ${it::class.simpleName}: ${it.message}", it)
    }

    override suspend fun deleteAccount(onProgress: (Int, Int) -> Unit): Result<Unit> = runSuspendCatching {
        val pubky = requireNotNull(sessionProvider.current()) { "Not signed in" }.identity.pubky
        Log.d(TAG, "deleteAccount: starting for ${pubky.take(PUBKY_LOG_PREFIX_LEN)}…")

        // Throws unless every record Loopky owns is gone, which is what keeps the sign-out below
        // from stranding a half-deleted account: signing back in is what a retry needs.
        eraser.erase(pubky, onProgress)

        // So a later sign-in on this same process announces the account again rather than
        // believing it already did.
        selfTaggedThisProcess = false
        signOut().getOrThrow()
        Log.d(TAG, "deleteAccount: done")
    }.onFailure {
        Log.e(TAG, "deleteAccount: FAILED — ${it::class.simpleName}: ${it.message}", it)
    }

    companion object {
        private const val TAG = "Loopky/IdentityRepo"
        private const val PUBKY_LOG_PREFIX_LEN = 8

        /** Deeplink Pubky Ring re-opens after approval; registered in the platform manifest. */
        private const val CALLBACK_URL = "loopky://login-callback"
        private const val CALLBACK_SOURCE = "Loopky"
    }
}
