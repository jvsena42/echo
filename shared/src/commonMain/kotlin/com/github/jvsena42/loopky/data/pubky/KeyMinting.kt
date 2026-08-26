package com.github.jvsena42.loopky.data.pubky

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minting and deriving key material, with the validation that has to run on **our** side of the
 * FFI boundary.
 *
 * Where the entropy comes from is already right: `generate_mnemonic` reaches bip39's
 * `Mnemonic::generate_in`, which draws from `rand::thread_rng()` reseeded from the OS
 * (`getrandom(2)` on Android, `SecRandomCopyBytes` on iOS), and `rand` panics rather than
 * degrading if the OS refuses entropy. So there is no silent userspace fallback to worry about.
 *
 * What this file exists for is the one failure nobody can see: key material that is *valid-looking*
 * but wrong. A user cannot detect it, the homeserver will happily accept it, and the account is
 * orphaned the moment they try to restore from the phrase they were shown. So every generated key
 * is round-tripped and asserted before it reaches a screen.
 *
 * Four rules, and none of them may be relaxed:
 *
 * 1. **Never mint key material outside the FFI.** No `kotlin.random.Random`, no `SecureRandom`
 *    seeded by hand, nowhere near a key, a passphrase or a salt. A scoped `ForbiddenImport` in
 *    `config/detekt/detekt.yml` keeps it that way, and `maxIssues: 0` makes it a build failure.
 * 2. **Fail loudly, never fall back.** A failure here is terminal and the screen offers Pubky Ring.
 *    Retrying with a weaker source is not an option that exists.
 * 3. **Validate in release too.** These are plain `if`s returning [Result.failure], deliberately not
 *    `require`/`assert` and deliberately not behind a debug flag — release is the only build where
 *    a weak key actually costs someone their account.
 * 4. **Check the payload, not the `Result`.** See [isValidMnemonic].
 */

/** A keypair and, when there is one, the phrase that derives it. Never logged. */
internal data class MintedKeypair(
    val secretKeyHex: String,
    val pubky: String,
    val mnemonic: String?,
)

/**
 * Generated key material failed its own round-trip. [stage] names which check, never a value.
 *
 * Terminal by construction: the caller surfaces it and offers Ring. There is no retry, because a
 * second draw from a source that just produced this is not evidence of anything.
 */
internal class WeakKeyMaterial(val stage: String) :
    RuntimeException("Generated key material failed validation at $stage")

/**
 * Mint a new keypair inside the FFI and prove it before returning it.
 *
 * The proof is the point: generation and derivation are two different code paths in the fork
 * (`generate_mnemonic_and_keypair` vs `mnemonic_to_keypair`), and the phrase we show the user is
 * only worth anything if the second one reproduces the first exactly.
 */
internal fun PubkyClient.mintValidatedKeypair(): Result<MintedKeypair> = runCatching {
    // Plain `runCatching`, not `runSuspendCatching`: every call in here is synchronous, so there is
    // no cancellation to swallow.
    val minted = generateMnemonicPhraseAndKeypair().getOrThrow().parseKeypairJson()

    val mnemonic = minted.mnemonic
        ?: throw WeakKeyMaterial("generation returned no mnemonic")

    if (!isValidMnemonic(mnemonic)) {
        throw WeakKeyMaterial("the generated phrase does not validate")
    }

    val derived = mnemonicPhraseToKeypair(mnemonic).getOrThrow().parseKeypairJson()

    // Both halves, not just the pubky. A public key that matches while the secret does not is the
    // shape that would sign in today and fail to restore forever.
    if (derived.secretKeyHex != minted.secretKeyHex) {
        throw WeakKeyMaterial("the phrase derives a different secret key")
    }
    if (derived.pubky != minted.pubky) {
        throw WeakKeyMaterial("the phrase derives a different pubky")
    }

    degenerateSecretReason(minted.secretKeyHex)?.let { throw WeakKeyMaterial(it) }

    minted.copy(mnemonic = mnemonic)
}

/**
 * Derive a keypair from a user-supplied recovery phrase.
 *
 * No degeneracy check here, unlike [mintValidatedKeypair]: that check exists to catch a broken RNG,
 * and this key did not come from ours. If someone's real key were somehow degenerate it would still
 * be their key, and refusing it would lock them out of their own account to no purpose.
 */
internal fun PubkyClient.keypairFromMnemonic(mnemonic: String): Result<MintedKeypair> {
    val normalised = mnemonic.normaliseMnemonic()
    if (!isValidMnemonic(normalised)) {
        return Result.failure(InvalidMnemonic())
    }
    return mnemonicPhraseToKeypair(normalised)
        .mapCatching { it.parseKeypairJson() }
        .map { it.copy(mnemonic = normalised) }
}

/** Derive the pubky for a bare secret key — the recovery-file path, which has no phrase. */
internal fun PubkyClient.keypairFromSecretKey(secretKeyHex: String): Result<MintedKeypair> =
    getPublicKeyFromSecretKey(secretKeyHex).map { pubky ->
        MintedKeypair(secretKeyHex = secretKeyHex, pubky = pubky.trim(), mnemonic = null)
    }

/** The phrase is not twelve valid BIP-39 words. Distinct from "valid, but no account exists". */
internal class InvalidMnemonic : RuntimeException("Invalid recovery phrase")

/**
 * Whether the FFI considers [mnemonic] a valid BIP-39 phrase.
 *
 * **Reads the payload, not `isSuccess`.** `validate_mnemonic_phrase` never returns an error — it
 * answers `["false", "true"]` or `["false", "false"]`, where the first element is the FFI's
 * "is this an error" flag and the second is the answer. So `Result.isSuccess` is true for an
 * invalid phrase just as much as a valid one, and a caller checking it would be validating nothing
 * at all while looking like it was.
 */
private fun PubkyClient.isValidMnemonic(mnemonic: String): Boolean =
    validateMnemonicPhrase(mnemonic).getOrNull()?.trim()?.equals("true", ignoreCase = true) == true

/**
 * Collapse the whitespace a pasted or hand-typed phrase arrives with.
 *
 * Also accepts the `-`, `_` and `+` separators, matching how Pubky Ring normalises an imported
 * phrase (`formatImportData` in its `inputParser.ts`), so a phrase exported from Ring in one of
 * those forms comes back in.
 */
internal fun String.normaliseMnemonic(): String =
    trim().lowercase().replace(MNEMONIC_SEPARATORS, " ")

private val MNEMONIC_SEPARATORS = Regex("[\\s\\-_+]+")

/**
 * Why [secretKeyHex] cannot have come from a working CSPRNG, or null if it looks fine.
 *
 * Not a strength test — 32 bytes of real entropy cannot be judged by looking at them. These are the
 * two patterns that mean the entropy source returned nothing at all: a zero-filled buffer, and a
 * buffer filled with one repeated byte. Both are what a broken or stubbed RNG produces, and both
 * would otherwise sail through every other check in this file.
 */
private fun degenerateSecretReason(secretKeyHex: String): String? {
    val hex = secretKeyHex.trim().lowercase()
    if (hex.length != SECRET_KEY_HEX_LENGTH) return "the secret key is not 32 bytes"
    val bytes = hex.chunked(2)
    if (bytes.all { it == "00" }) return "the secret key is all zeroes"
    if (bytes.distinct().size == 1) return "the secret key is a single repeated byte"
    return null
}

private const val SECRET_KEY_HEX_LENGTH = 64

/**
 * Parse the fork's keypair JSON: `{"secret_key", "public_key", "uri", "mnemonic"?}`
 * (`pubky-core-ffi-fork/src/utils.rs::keypair_to_json_string`). `mnemonic` is present only for
 * `generate_mnemonic_phrase_and_keypair`; derivation returns the same object without it.
 */
private fun String.parseKeypairJson(): MintedKeypair {
    val obj = keypairJson.parseToJsonElement(this).jsonObject
    fun field(name: String): String? = obj[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    val secret = field("secret_key") ?: throw WeakKeyMaterial("the FFI returned no secret_key")
    val pubky = field("public_key") ?: throw WeakKeyMaterial("the FFI returned no public_key")
    return MintedKeypair(secretKeyHex = secret, pubky = pubky, mnemonic = field("mnemonic"))
}

private val keypairJson: Json = Json { ignoreUnknownKeys = true }
