package com.github.jvsena42.loopky.data.pubky

/**
 * Thin wrapper around `pubky-core-ffi-fork`. Each function mirrors a UniFFI-generated
 * primitive (see `../../../../../../../../../pubky-core-ffi-fork/bindings/android/pubkycore.kt`)
 * and returns a [Result] instead of the raw `List<String>` `[status, payload]` convention.
 *
 * Higher-level domain operations (e.g. "publish a deck") compose these primitives inside
 * the repositories layer — do not add deck/card concepts here.
 *
 * - JVM actual (Android and the desktop `jvm()` target the CLI runs on):
 *   [com.github.jvsena42.loopky.data.pubky.UniffiPubkyClient] (JNA + UniFFI).
 * - iOS actual: implemented in Swift in `iosApp/iosApp/Pubky/IosPubkyClient.swift`,
 *   injected into the shared layer at app start.
 */
interface PubkyClient {

    // --- Keys & mnemonics -----------------------------------------------------
    fun generateSecretKey(): Result<String>
    fun getPublicKeyFromSecretKey(secretKey: String): Result<String>
    fun generateMnemonicPhrase(): Result<String>
    fun generateMnemonicPhraseAndKeypair(): Result<String>
    fun mnemonicPhraseToKeypair(mnemonicPhrase: String): Result<String>
    fun validateMnemonicPhrase(mnemonicPhrase: String): Result<String>

    // --- Recovery files -------------------------------------------------------
    fun createRecoveryFile(secretKey: String, passphrase: String): Result<String>
    fun decryptRecoveryFile(recoveryFile: String, passphrase: String): Result<String>

    // --- Auth / sessions ------------------------------------------------------

    /**
     * Sign up / sign in with a secret key Loopky holds — the local alternative to the Ring
     * deeplink (#147).
     *
     * **Both bind to the FFI's cookie variants**, like [startAuthFlow] and for an overlapping
     * reason. The fork's plain `sign_in`/`sign_up` delegate to the *grant* flow, and measured
     * against Synonym's staging homeserver that flow fails: `export_grant_session_secret` writes
     * outside `/pub/`, which the homeserver refuses with
     * `403 Forbidden - Writing to directories other than '/pub/' is forbidden`. The cookie flow
     * signs in cleanly and answers a pubky with no account with an honest 404.
     *
     * It is also what the rest of this client already expects: [signOut], [revalidateSession] and
     * `put_with_session` take the `session_secret` the cookie flow returns, and every Ring session
     * is a cookie session, so the local paths now produce the same kind of session as every other
     * path in the app rather than a second kind.
     *
     * Revisit alongside #130 — upstream marks the cookie flow deprecated, so this is a hold rather
     * than a destination, and the grant flow needs a homeserver that accepts it before it can be
     * the answer here.
     */
    suspend fun signUp(
        secretKey: String,
        homeserver: String,
        signupToken: String?,
    ): Result<String>

    suspend fun getSignupToken(homeserverPubky: String, adminPassword: String): Result<String>
    suspend fun signIn(secretKey: String): Result<String>
    suspend fun signOut(sessionSecret: String): Result<String>
    suspend fun revalidateSession(sessionSecret: String): Result<String>

    /**
     * Pubky Ring-style deeplink flow.
     *
     * Both bind to the FFI's **cookie** variant (`start_cookie_auth_flow` /
     * `await_cookie_auth_approval`), not the grant variant its plain `start_auth_flow` now
     * delegates to. That is a compatibility choice about the app on the other end of the
     * deeplink, not a preference:
     *
     * - pubky 0.10's grant flow mints `pubkyauth://signin_grant?…&cid=…&cpk=…`. Every released
     *   Pubky Ring bundles `react-native-pubky@0.13.0` — pubky 0.9.x, whose deeplink parser knows
     *   `signin`, `signup`, `direct_signup` and `session` and nothing else. It answers a grant URL
     *   with "Unrecognized format" and the user cannot sign in at all.
     * - The grant flow also returns its session secret as `grant_secret`, where the rest of this
     *   client — [signOut], [revalidateSession], `put_with_session` — expects the `session_secret`
     *   the cookie flow returns. (`restore_session` takes either, so that half is survivable;
     *   the deeplink half is not.)
     *
     * The cookie flow emits `pubkyauth://signin?caps=…&relay=…&secret=…`, which is what Loopky
     * sent before the 0.10 bindings bump and what Ring understands today. It carries no
     * `ClientId`, which is why these two are the only calls here that do not pass one.
     *
     * Revisit when Ring ships a release built on pubky 0.10 — the work is started on its
     * `chore/pubky-0.10.0` branch, blocked on `react-native-pubky` publishing a 0.10 build.
     * Upstream marks the cookie flow deprecated, so this is a hold, not a destination: #130
     * tracks undoing it, and lists what has to ship first.
     */
    suspend fun startAuthFlow(capabilities: String): Result<String>
    suspend fun awaitAuthApproval(): Result<String>
    fun parseAuthUrl(url: String): Result<String>
    suspend fun auth(url: String, secretKey: String): Result<String>

    // --- Records (secret-key auth) --------------------------------------------
    suspend fun publish(
        recordName: String,
        recordContent: String,
        secretKey: String,
    ): Result<String>

    suspend fun publishHttps(
        recordName: String,
        target: String,
        secretKey: String,
    ): Result<String>

    suspend fun put(url: String, content: String, secretKey: String): Result<String>

    /** Raw binary PUT — content lands on the homeserver as-is (no Base64 envelope). */
    suspend fun putBytes(url: String, content: ByteArray, secretKey: String): Result<String>

    suspend fun get(url: String): Result<String>

    /** Raw binary GET — the FFI returns the payload Base64-encoded for transport. */
    suspend fun getBytes(url: String): Result<String>

    /**
     * Directory listing with homeserver pagination. All filters are optional; the
     * no-arg form lists everything (legacy behaviour).
     */
    suspend fun list(
        url: String,
        cursor: String? = null,
        reverse: Boolean? = null,
        limit: UShort? = null,
        shallow: Boolean? = null,
    ): Result<String>

    suspend fun deleteFile(url: String, secretKey: String): Result<String>
    suspend fun republishHomeserver(secretKey: String, homeserver: String): Result<String>

    // --- Records (session auth) -----------------------------------------------
    suspend fun putWithSession(
        url: String,
        content: String,
        sessionSecret: String,
    ): Result<String>

    /** Raw binary PUT under session auth — the Pubky Ring flow never exposes the secret key. */
    suspend fun putBytesWithSession(
        url: String,
        content: ByteArray,
        sessionSecret: String,
    ): Result<String>

    suspend fun deleteWithSession(url: String, sessionSecret: String): Result<String>

    // --- pubky-app-specs helpers ------------------------------------------------

    /**
     * Derive a pubky-app-specs tag id (Crockford-base32 of half a blake3 hash of
     * `"$uri:$label"`). [label] must already be sanitized (trimmed, lowercase).
     */
    fun createTagId(uri: String, label: String): Result<String>

    // --- DHT resolution -------------------------------------------------------
    suspend fun resolve(publicKey: String): Result<String>
    suspend fun resolveHttps(publicKey: String): Result<String>
    suspend fun getHomeserver(pubky: String): Result<String>

    // --- Network --------------------------------------------------------------
    fun switchNetwork(useTestnet: Boolean): Result<String>
}

/** Error returned by [PubkyClient] when the FFI replies with `["error", message]`. */
class PubkyError(message: String) : RuntimeException(message) {
    /**
     * The HTTP status the homeserver answered with, when the FFI's message carries one.
     *
     * The FFI has no typed error surface — a homeserver answer reaches us as prose
     * (`"Request failed: Server responded with an error: 404 Not Found - Not Found"`), so the
     * status has to be read back out of it. Parsed once here rather than at each call site,
     * because a bare `"404" in message` is the bug [isNotFound] used to have: every failure
     * message carries a `pubky://` URL, and deck and card ids are random alphanumerics.
     *
     * `null` when the message names no status — a transport failure that never reached the
     * homeserver, or wording this parser does not know. Classifiers fall back to substrings
     * there, so a miss degrades to today's behaviour rather than to a wrong verdict.
     */
    val status: Int? = STATUS_IN_MESSAGE.find(message)?.groupValues?.get(1)?.toIntOrNull()
}

/**
 * Matches the status in the one shape the homeserver's answers reach us in. Deliberately
 * anchored to the phrase rather than hunting for three digits: an unanchored match would read a
 * status out of the URL in the same message.
 */
private val STATUS_IN_MESSAGE =
    Regex("""responded with an error:\s*(\d{3})""", RegexOption.IGNORE_CASE)
