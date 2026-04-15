package com.github.jvsena42.eco.data.pubky

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.pubkycore.auth as ffiAuth
import uniffi.pubkycore.awaitAuthApproval as ffiAwaitAuthApproval
import uniffi.pubkycore.createRecoveryFile as ffiCreateRecoveryFile
import uniffi.pubkycore.decryptRecoveryFile as ffiDecryptRecoveryFile
import uniffi.pubkycore.deleteFile as ffiDeleteFile
import uniffi.pubkycore.deleteWithSession as ffiDeleteWithSession
import uniffi.pubkycore.generateMnemonicPhrase as ffiGenerateMnemonicPhrase
import uniffi.pubkycore.generateMnemonicPhraseAndKeypair as ffiGenerateMnemonicPhraseAndKeypair
import uniffi.pubkycore.generateSecretKey as ffiGenerateSecretKey
import uniffi.pubkycore.get as ffiGet
import uniffi.pubkycore.getHomeserver as ffiGetHomeserver
import uniffi.pubkycore.getPublicKeyFromSecretKey as ffiGetPublicKeyFromSecretKey
import uniffi.pubkycore.getSignupToken as ffiGetSignupToken
import uniffi.pubkycore.list as ffiList
import uniffi.pubkycore.mnemonicPhraseToKeypair as ffiMnemonicPhraseToKeypair
import uniffi.pubkycore.parseAuthUrl as ffiParseAuthUrl
import uniffi.pubkycore.publish as ffiPublish
import uniffi.pubkycore.publishHttps as ffiPublishHttps
import uniffi.pubkycore.put as ffiPut
import uniffi.pubkycore.putWithSession as ffiPutWithSession
import uniffi.pubkycore.republishHomeserver as ffiRepublishHomeserver
import uniffi.pubkycore.resolve as ffiResolve
import uniffi.pubkycore.resolveHttps as ffiResolveHttps
import uniffi.pubkycore.revalidateSession as ffiRevalidateSession
import uniffi.pubkycore.signIn as ffiSignIn
import uniffi.pubkycore.signOut as ffiSignOut
import uniffi.pubkycore.signUp as ffiSignUp
import uniffi.pubkycore.startAuthFlow as ffiStartAuthFlow
import uniffi.pubkycore.switchNetwork as ffiSwitchNetwork
import uniffi.pubkycore.validateMnemonicPhrase as ffiValidateMnemonicPhrase

/**
 * Android implementation of [PubkyClient] delegating to the UniFFI-generated Kotlin
 * bindings in `uniffi.pubkycore`. JNI libraries are shipped in `shared/androidMain/jniLibs`.
 *
 * The UniFFI surface returns `List<String>` of shape `[status, payload]` where status is
 * `"success"` or `"error"`. [runFfi]/[runFfiSuspend] translate that into [Result].
 *
 * Blocking network calls are routed through [Dispatchers.IO] so callers on the main
 * dispatcher stay responsive.
 */
class AndroidPubkyClient : PubkyClient {

    // --- Keys & mnemonics -----------------------------------------------------
    override fun generateSecretKey() = runFfi { ffiGenerateSecretKey() }
    override fun getPublicKeyFromSecretKey(secretKey: String) =
        runFfi { ffiGetPublicKeyFromSecretKey(secretKey) }

    override fun generateMnemonicPhrase() = runFfi { ffiGenerateMnemonicPhrase() }
    override fun generateMnemonicPhraseAndKeypair() =
        runFfi { ffiGenerateMnemonicPhraseAndKeypair() }

    override fun mnemonicPhraseToKeypair(mnemonicPhrase: String) =
        runFfi { ffiMnemonicPhraseToKeypair(mnemonicPhrase) }

    override fun validateMnemonicPhrase(mnemonicPhrase: String) =
        runFfi { ffiValidateMnemonicPhrase(mnemonicPhrase) }

    // --- Recovery files -------------------------------------------------------
    override fun createRecoveryFile(secretKey: String, passphrase: String) =
        runFfi { ffiCreateRecoveryFile(secretKey, passphrase) }

    override fun decryptRecoveryFile(recoveryFile: String, passphrase: String) =
        runFfi { ffiDecryptRecoveryFile(recoveryFile, passphrase) }

    // --- Auth / sessions ------------------------------------------------------
    override suspend fun signUp(
        secretKey: String,
        homeserver: String,
        signupToken: String?,
    ) = runFfiSuspend { ffiSignUp(secretKey, homeserver, signupToken) }

    override suspend fun getSignupToken(homeserverPubky: String, adminPassword: String) =
        runFfiSuspend { ffiGetSignupToken(homeserverPubky, adminPassword) }

    override suspend fun signIn(secretKey: String) = runFfiSuspend { ffiSignIn(secretKey) }
    override suspend fun signOut(sessionSecret: String) =
        runFfiSuspend { ffiSignOut(sessionSecret) }

    override suspend fun revalidateSession(sessionSecret: String) =
        runFfiSuspend { ffiRevalidateSession(sessionSecret) }

    override suspend fun startAuthFlow(capabilities: String) =
        runFfiSuspend { ffiStartAuthFlow(capabilities) }

    override suspend fun awaitAuthApproval() = runFfiSuspend { ffiAwaitAuthApproval() }
    override fun parseAuthUrl(url: String) = runFfi { ffiParseAuthUrl(url) }
    override suspend fun auth(url: String, secretKey: String) =
        runFfiSuspend { ffiAuth(url, secretKey) }

    // --- Records (secret-key auth) --------------------------------------------
    override suspend fun publish(recordName: String, recordContent: String, secretKey: String) =
        runFfiSuspend { ffiPublish(recordName, recordContent, secretKey) }

    override suspend fun publishHttps(recordName: String, target: String, secretKey: String) =
        runFfiSuspend { ffiPublishHttps(recordName, target, secretKey) }

    override suspend fun put(url: String, content: String, secretKey: String) =
        runFfiSuspend { ffiPut(url, content, secretKey) }

    override suspend fun get(url: String) = runFfiSuspend { ffiGet(url) }
    override suspend fun list(url: String) = runFfiSuspend { ffiList(url) }
    override suspend fun deleteFile(url: String, secretKey: String) =
        runFfiSuspend { ffiDeleteFile(url, secretKey) }

    override suspend fun republishHomeserver(secretKey: String, homeserver: String) =
        runFfiSuspend { ffiRepublishHomeserver(secretKey, homeserver) }

    // --- Records (session auth) -----------------------------------------------
    override suspend fun putWithSession(url: String, content: String, sessionSecret: String) =
        runFfiSuspend { ffiPutWithSession(url, content, sessionSecret) }

    override suspend fun deleteWithSession(url: String, sessionSecret: String) =
        runFfiSuspend { ffiDeleteWithSession(url, sessionSecret) }

    // --- DHT resolution -------------------------------------------------------
    override suspend fun resolve(publicKey: String) = runFfiSuspend { ffiResolve(publicKey) }
    override suspend fun resolveHttps(publicKey: String) =
        runFfiSuspend { ffiResolveHttps(publicKey) }

    override suspend fun getHomeserver(pubky: String) =
        runFfiSuspend { ffiGetHomeserver(pubky) }

    // --- Network --------------------------------------------------------------
    override fun switchNetwork(useTestnet: Boolean) = runFfi { ffiSwitchNetwork(useTestnet) }

    // --- Helpers --------------------------------------------------------------
    private inline fun runFfi(block: () -> List<String>): Result<String> =
        try {
            block().toResult()
        } catch (t: Throwable) {
            Result.failure(t)
        }

    private suspend inline fun runFfiSuspend(
        crossinline block: () -> List<String>,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            block().toResult()
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * FFI convention from `pubky-core-ffi-fork::utils::create_response_vector`:
     *   `[error.to_string(), data]` → `["false", "<payload>"]` on success,
     *   `["true", "<message>"]` on error.
     *
     * We treat everything that is not the literal `"false"` as an error to stay defensive.
     */
    private fun List<String>.toResult(): Result<String> {
        if (size < 2) return Result.failure(PubkyError("Unexpected FFI response: $this"))
        return when (this[0]) {
            "false" -> Result.success(this[1])
            else -> Result.failure(PubkyError(this[1]))
        }
    }
}
