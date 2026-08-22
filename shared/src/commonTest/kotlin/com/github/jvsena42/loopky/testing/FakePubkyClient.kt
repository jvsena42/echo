package com.github.jvsena42.loopky.testing

import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyError
import kotlinx.coroutines.CompletableDeferred
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * In-memory [PubkyClient] test double shared by repository and ViewModel tests.
 *
 * - Text records live in [store] keyed by url. Binary records are stored Base64-encoded
 *   (matching the FFI transport contract: [getBytes] returns a Base64 payload).
 * - [puts], [bytePuts], [deletes] and [listedPrefixes] record calls for assertions.
 * - [list] returns a JSON array of the stored urls under the given prefix.
 * - [createTagId] is deterministic so tests can derive the expected tag path.
 * - Every method no repository under test uses fails with [UnsupportedOperationException].
 */
@OptIn(ExperimentalEncodingApi::class)
class FakePubkyClient : PubkyClient {

    val store = mutableMapOf<String, String>()
    val gets = mutableListOf<String>()
    val puts = mutableListOf<Pair<String, String>>()
    val bytePuts = mutableListOf<Pair<String, ByteArray>>()
    val deletes = mutableListOf<String>()
    val listedPrefixes = mutableListOf<String>()
    val signOuts = mutableListOf<String>()

    /** When set, the next session-authenticated write/delete fails once with this error. */
    var failNextSessionCallWith: Throwable? = null

    /**
     * When set, *every* session-authenticated write/delete fails with this error. For conditions
     * that do not clear on their own — a full storage quota is the one this exists for, where the
     * point is that retrying cannot help.
     */
    var failAllSessionCallsWith: Throwable? = null

    /** Fails the next N session-authenticated writes with 429, as a busy homeserver would. */
    var rateLimitNextCalls: Int = 0

    /** When set, every [list] call fails with this error (simulates an unreachable homeserver). */
    var failListWith: Throwable? = null

    /**
     * Hard cap on entries per [list] call, overriding what the caller asked for. For tests that
     * need to force paging with a handful of records rather than hundreds.
     */
    var listPageSize: Int? = null

    /**
     * What the homeserver returns when the caller sends no `limit` — its `DEFAULT_LIST_LIMIT`.
     *
     * Modelling this is the point: the fake used to answer an unpaged call with *everything*,
     * which let a listing that never paged pass every test and then lose whole decks on device.
     */
    var defaultListLimit: Int = 100

    /** The homeserver's `DEFAULT_MAX_LIST_LIMIT`: a larger `limit` is clamped to it. */
    var maxListLimit: Int = 1000

    /**
     * Whether [list] honours `shallow` by collapsing to one entry per first-level child. Set false
     * to model a homeserver that ignores the flag, which the paging fallback has to survive.
     */
    var honoursShallow: Boolean = true

    /** When set, [list] succeeds this many times and fails afterwards, as a mid-listing drop would. */
    var failListAfterPages: Int? = null

    /** Every [list] call, with the arguments it was made with. */
    val listCalls = mutableListOf<ListCall>()

    /** When set, every [get] call fails with this error (simulates an unreachable homeserver). */
    var failGetWith: Throwable? = null

    /** What [startAuthFlow] hands back, and the capabilities it was asked for. */
    var authFlowResult: Result<String> = Result.success("pubkyauth:///?caps=&secret=test")
    val authFlowCapabilities = mutableListOf<String>()

    /**
     * What [awaitAuthApproval] answers, and how often it was asked. The count matters: the real FFI
     * consumes its auth flow on the first poll, so a second call could only ever fail (#59).
     */
    var approvalResult: Result<String> = Result.failure(PubkyError("No auth flow in progress"))
    var awaitApprovalCalls = 0

    /**
     * When set, [putWithSession] parks until it completes. `runTest` runs on one thread, so without
     * a real suspension point inside the write two "concurrent" writers can never interleave and a
     * race test passes vacuously.
     */
    var putGate: CompletableDeferred<Unit>? = null

    override suspend fun putWithSession(
        url: String,
        content: String,
        sessionSecret: String,
    ): Result<String> {
        putGate?.await()
        consumeInjectedFailure()?.let { return Result.failure(it) }
        store[url] = content
        puts.add(url to content)
        return Result.success("ok")
    }

    override suspend fun putBytesWithSession(
        url: String,
        content: ByteArray,
        sessionSecret: String,
    ): Result<String> {
        consumeInjectedFailure()?.let { return Result.failure(it) }
        store[url] = Base64.encode(content)
        bytePuts.add(url to content)
        return Result.success("ok")
    }

    /**
     * Deleting a path that is not there is a 404, as on a real homeserver. This used to succeed
     * silently, which is how a deck whose manifest listed chunk records that were never written —
     * a half-finished import — could be undeletable on device while every delete test passed.
     */
    override suspend fun deleteWithSession(url: String, sessionSecret: String): Result<String> {
        consumeInjectedFailure()?.let { return Result.failure(it) }
        deletes.add(url)
        if (store.remove(url) == null) return Result.failure(PubkyError("not found: $url"))
        return Result.success("ok")
    }

    override suspend fun get(url: String): Result<String> {
        gets.add(url)
        failGetWith?.let { return Result.failure(it) }
        return store[url]?.let { Result.success(it) }
            ?: Result.failure(PubkyError("not found: $url"))
    }

    override suspend fun getBytes(url: String): Result<String> = get(url)

    override suspend fun list(
        url: String,
        cursor: String?,
        reverse: Boolean?,
        limit: UShort?,
        shallow: Boolean?,
    ): Result<String> {
        listedPrefixes.add(url)
        listCalls.add(ListCall(url, cursor, limit, shallow))
        failListWith?.let { return Result.failure(it) }
        failListAfterPages?.let { allowed ->
            if (listCalls.size > allowed) return Result.failure(PubkyError("HTTP transport error: list page failed"))
        }
        // Mirrors the homeserver's own order: collapse first, then cursor, then limit.
        var matches = store.keys.filter { it.startsWith(url) }
        matches = if (shallow == true && honoursShallow) collapseToChildren(url, matches) else matches
        matches = matches.distinct().sorted()
        if (cursor != null) matches = matches.filter { it > cursor }
        // `limit.unwrap_or(DEFAULT_LIST_LIMIT).min(DEFAULT_MAX_LIST_LIMIT)`, as the server does.
        val cap = listPageSize ?: (limit?.toInt() ?: defaultListLimit).coerceAtMost(maxListLimit)
        matches = matches.take(cap)
        return Result.success(matches.joinToString(",", "[", "]") { "\"$it\"" })
    }

    /**
     * One entry per first-level child of [prefix], as the homeserver's `list_shallow` does: a
     * directory keeps its trailing slash, a file directly under the prefix is returned as-is.
     */
    private fun collapseToChildren(prefix: String, paths: List<String>): List<String> =
        paths.map { path ->
            val rest = path.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash == -1) path else prefix + rest.substring(0, slash + 1)
        }

    /** One [list] call, so a test can assert what was asked for and not only which prefix. */
    data class ListCall(
        val url: String,
        val cursor: String?,
        val limit: UShort?,
        val shallow: Boolean?,
    )

    override fun createTagId(uri: String, label: String): Result<String> =
        Result.success("TAGID-" + (uri + label).hashCode().toUInt().toString(16))

    private fun consumeInjectedFailure(): Throwable? {
        failAllSessionCallsWith?.let { return it }
        if (rateLimitNextCalls > 0) {
            rateLimitNextCalls--
            return PubkyError("Request failed: Server responded with an error: 429 Too Many Requests")
        }
        return failNextSessionCallWith?.also { failNextSessionCallWith = null }
    }

    // --- Surface unused by the repositories under test -------------------------

    override fun generateSecretKey(): Result<String> = unused()
    override fun getPublicKeyFromSecretKey(secretKey: String): Result<String> = unused()
    override fun generateMnemonicPhrase(): Result<String> = unused()
    override fun generateMnemonicPhraseAndKeypair(): Result<String> = unused()
    override fun mnemonicPhraseToKeypair(mnemonicPhrase: String): Result<String> = unused()
    override fun validateMnemonicPhrase(mnemonicPhrase: String): Result<String> = unused()
    override fun createRecoveryFile(secretKey: String, passphrase: String): Result<String> = unused()
    override fun decryptRecoveryFile(recoveryFile: String, passphrase: String): Result<String> = unused()
    override suspend fun signUp(
        secretKey: String,
        homeserver: String,
        signupToken: String?,
    ): Result<String> = unused()

    override suspend fun getSignupToken(
        homeserverPubky: String,
        adminPassword: String,
    ): Result<String> = unused()

    override suspend fun signIn(secretKey: String): Result<String> = unused()
    override suspend fun signOut(sessionSecret: String): Result<String> {
        signOuts.add(sessionSecret)
        return Result.success("ok")
    }

    override suspend fun revalidateSession(sessionSecret: String): Result<String> = unused()

    override suspend fun startAuthFlow(capabilities: String): Result<String> {
        authFlowCapabilities.add(capabilities)
        return authFlowResult
    }

    override suspend fun awaitAuthApproval(): Result<String> {
        awaitApprovalCalls++
        return approvalResult
    }

    override fun parseAuthUrl(url: String): Result<String> = unused()
    override suspend fun auth(url: String, secretKey: String): Result<String> = unused()
    override suspend fun publish(
        recordName: String,
        recordContent: String,
        secretKey: String,
    ): Result<String> = unused()

    override suspend fun publishHttps(
        recordName: String,
        target: String,
        secretKey: String,
    ): Result<String> = unused()

    override suspend fun put(url: String, content: String, secretKey: String): Result<String> = unused()
    override suspend fun putBytes(url: String, content: ByteArray, secretKey: String): Result<String> = unused()
    override suspend fun deleteFile(url: String, secretKey: String): Result<String> = unused()
    override suspend fun republishHomeserver(secretKey: String, homeserver: String): Result<String> = unused()
    override suspend fun resolve(publicKey: String): Result<String> = unused()
    override suspend fun resolveHttps(publicKey: String): Result<String> = unused()
    override suspend fun getHomeserver(pubky: String): Result<String> = unused()
    override fun switchNetwork(useTestnet: Boolean): Result<String> = unused()

    private fun unused(): Nothing =
        throw UnsupportedOperationException("Not used by the code under test")
}
