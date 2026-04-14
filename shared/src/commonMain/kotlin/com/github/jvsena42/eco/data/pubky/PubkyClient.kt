package com.github.jvsena42.eco.data.pubky

/**
 * Thin wrapper around `pubky-core-ffi-fork`. Each function mirrors a UniFFI-generated
 * primitive (see `../../../../../../../../../pubky-core-ffi-fork/bindings/android/pubkycore.kt`)
 * and returns a [Result] instead of the raw `List<String>` `[status, payload]` convention.
 *
 * Higher-level domain operations (e.g. "publish a deck") compose these primitives inside
 * the repositories layer — do not add deck/card concepts here.
 *
 * - Android actual: [com.github.jvsena42.eco.data.pubky.AndroidPubkyClient] (JNA + UniFFI).
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
    suspend fun signUp(
        secretKey: String,
        homeserver: String,
        signupToken: String?,
    ): Result<String>

    suspend fun getSignupToken(homeserverPubky: String, adminPassword: String): Result<String>
    suspend fun signIn(secretKey: String): Result<String>
    suspend fun signOut(sessionSecret: String): Result<String>
    suspend fun revalidateSession(sessionSecret: String): Result<String>

    /** Pubky Ring-style deeplink flow. */
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
    suspend fun get(url: String): Result<String>
    suspend fun list(url: String): Result<String>
    suspend fun deleteFile(url: String, secretKey: String): Result<String>
    suspend fun republishHomeserver(secretKey: String, homeserver: String): Result<String>

    // --- Records (session auth) -----------------------------------------------
    suspend fun putWithSession(
        url: String,
        content: String,
        sessionSecret: String,
    ): Result<String>

    suspend fun deleteWithSession(url: String, sessionSecret: String): Result<String>

    // --- DHT resolution -------------------------------------------------------
    suspend fun resolve(publicKey: String): Result<String>
    suspend fun resolveHttps(publicKey: String): Result<String>
    suspend fun getHomeserver(pubky: String): Result<String>

    // --- Network --------------------------------------------------------------
    fun switchNetwork(useTestnet: Boolean): Result<String>
}

/** Error returned by [PubkyClient] when the FFI replies with `["error", message]`. */
class PubkyError(message: String) : RuntimeException(message)
