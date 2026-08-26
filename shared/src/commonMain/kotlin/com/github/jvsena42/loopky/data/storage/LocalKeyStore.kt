package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.KeyCustody
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Platform-keystore-backed store for a secret key **Loopky itself holds** — one it minted, or one
 * restored from a recovery phrase or an encrypted recovery file.
 *
 * **Why this exists at all.** Until local keys, Loopky held only a session secret scoped to its own
 * storage area and Pubky Ring held the key (Architecture.md §7.8). A key created here has nowhere
 * else to live, and it *is* the account: lose it with no backup and the identity is gone, decks and
 * followers with it.
 *
 * **Why the secrets vault and not the session one.** Three reasons, and none of them is sign-out:
 *
 * 1. The key exists **before a session does** — on the generated-phrase screen `signUp` has not
 *    been called yet, so there is no [Session] to hang a field on without inventing half of one.
 * 2. [custody] is asked constantly by surfaces that must never see a secret — the Settings nag, the
 *    home banner, the export row, the sign-out warning. Deriving that from a session blob would
 *    mean decrypting the key to answer a question *about* the key.
 * 3. Handing custody to Ring later becomes a flag transition rather than a schema migration of a
 *    blob every install already holds.
 *
 * [SignupTokenStore] documents the same vault choice for a signup token; a secret key belongs there
 * at least as much.
 *
 * **Nothing here logs, ever.** Not the key, not the mnemonic, not a redacted prefix of either.
 *
 * **This interface is `internal` on purpose.** It is the only door to the secret, and only
 * repositories go through it. The rest of the app asks [custody], which carries no key material.
 */
internal interface LocalKeyStore {

    /**
     * Who holds the key, and whether it has been backed up. Emits the current value immediately,
     * then on every change. Never carries the secret.
     */
    val custody: Flow<KeyCustody>

    suspend fun save(key: LocalKey)

    /**
     * The stored key, or null when Ring holds it (or nobody is signed in).
     *
     * Deliberately a suspend *read of the vault* rather than a value cached in a field: the secret
     * then exists in memory only for as long as a caller is using it, instead of for the life of
     * the process. Called rarely — backup export, and re-deriving a session — so the read costs
     * nothing that matters.
     */
    suspend fun current(): LocalKey?

    /**
     * Record that the key has been put somewhere that survives losing this device.
     *
     * Additive and idempotent: methods accumulate, because having written the words down is not a
     * reason to stop offering the encrypted file.
     */
    suspend fun markBackedUp(method: BackupMethod)

    /**
     * Drop the key.
     *
     * **Unguarded by design.** Whether it is safe to destroy an un-backed-up key is a question
     * about what the user was told, not about storage, so the warning-or-refusal lives on
     * `IdentityRepository.signOut(force)` where the confirm can be raised. A guard here as well
     * would be a second place to keep in sync and a second place to get wrong.
     */
    suspend fun clear()
}

/**
 * A secret key Loopky holds, with everything needed to back it up.
 *
 * Never leaves the data layer: repositories read it, use it, and hand the UI a [KeyCustody] or a
 * one-shot value for a `FLAG_SECURE` screen. It is not a `UiState` field in any screen.
 */
@Serializable
internal data class LocalKey(
    /** Hex, as the FFI's `secret_key` field gives it. */
    val secretKeyHex: String,
    /** z32, derived from the key — stored so [custody] can name the account without decrypting. */
    val pubky: String,
    /**
     * The twelve words, when we have them.
     *
     * Null for a key restored from a recovery file: that yields a secret key, and BIP-39 derivation
     * runs one way — there is no phrase to recover from it. Kept for the ones we do have because
     * the confirm quiz and "show my recovery phrase again" both need the words themselves.
     */
    val mnemonic: String? = null,
    val backedUpBy: Set<BackupMethod> = emptySet(),
) {
    fun toCustody(): KeyCustody.Loopky = KeyCustody.Loopky(
        pubky = pubky,
        backedUpBy = backedUpBy,
        hasPhrase = mnemonic != null,
    )
}

internal const val LOCAL_KEY_STORAGE_KEY = "identity.key.v1"
