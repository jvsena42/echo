package com.github.jvsena42.loopky.testing

import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyError
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

    /** Fails the next N session-authenticated writes with 429, as a busy homeserver would. */
    var rateLimitNextCalls: Int = 0

    /** When set, every [list] call fails with this error (simulates an unreachable homeserver). */
    var failListWith: Throwable? = null

    /**
     * Entries returned per [list] call, so tests can force the caller to page. Real homeservers
     * cap this; nothing in the app paged until the delete sweep needed to.
     */
    var listPageSize: Int? = null

    /** When set, every [get] call fails with this error (simulates an unreachable homeserver). */
    var failGetWith: Throwable? = null

    override suspend fun putWithSession(
        url: String,
        content: String,
        sessionSecret: String,
    ): Result<String> {
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

    override suspend fun deleteWithSession(url: String, sessionSecret: String): Result<String> {
        consumeInjectedFailure()?.let { return Result.failure(it) }
        store.remove(url)
        deletes.add(url)
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
        failListWith?.let { return Result.failure(it) }
        var matches = store.keys.filter { it.startsWith(url) }.sorted()
        if (cursor != null) matches = matches.filter { it > cursor }
        val cap = listOfNotNull(listPageSize, limit?.toInt()).minOrNull()
        if (cap != null) matches = matches.take(cap)
        return Result.success(matches.joinToString(",", "[", "]") { "\"$it\"" })
    }

    override fun createTagId(uri: String, label: String): Result<String> =
        Result.success("TAGID-" + (uri + label).hashCode().toUInt().toString(16))

    private fun consumeInjectedFailure(): Throwable? {
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
    override suspend fun startAuthFlow(capabilities: String): Result<String> = unused()
    override suspend fun awaitAuthApproval(): Result<String> = unused()
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
