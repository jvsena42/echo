package com.github.jvsena42.loopky.domain.model

/**
 * Where a secret key Loopky is about to hold came from.
 *
 * Deliberately a closed set: these are the only two ways a key enters Loopky that are not "we
 * minted it ourselves". Both carry material that must never be logged or put in a `UiState`, which
 * is why they are passed *through* the repository rather than held anywhere.
 */
sealed interface KeySource {

    /** Twelve BIP-39 words, as typed or pasted. Normalised by the repository, not the caller. */
    data class Phrase(val mnemonic: String) : KeySource

    /**
     * An Argon2id-encrypted recovery file with the passphrase that opens it.
     *
     * [base64] is the file's bytes Base64-encoded, because that is the envelope the FFI's
     * `decrypt_recovery_file` expects. The file **on disk** is raw — pubky-app writes it that way
     * as `recovery.pkarr` — so whoever read it is responsible for encoding, and whoever writes one
     * is responsible for decoding. Getting that backwards produces files no other Pubky app can
     * open.
     */
    data class RecoveryFile(val base64: String, val passphrase: String) : KeySource
}

/**
 * What the DHT says about whether a pubky has a homeserver account.
 *
 * **Three outcomes, not two, and that is the entire point.** A boolean would collapse "this key
 * has never been registered" and "we could not reach the network to ask" into one answer, and the
 * second dressed as the first is a confident lie about someone's recovery phrase — told at exactly
 * the moment they are anxious about having typed it wrong (#147).
 */
sealed interface HomeserverLookup {

    /** A homeserver record exists. [homeserverPubky] is the z32 of the server hosting the account. */
    data class Registered(val homeserverPubky: String) : HomeserverLookup

    /**
     * The pubky resolves, but has published no homeserver record — so it has an account nowhere.
     *
     * A stop, never an error: the phrase is *valid*, it simply is not this account's. The usual
     * cause is a checksum-passing typo (one wrong word, or two transposed), which is why the UI
     * shows the derived pubky and offers "check it again" ahead of anything else.
     */
    data object NoRecord : HomeserverLookup

    /**
     * We could not ask. Always paired with a retry, and never rendered as a verdict on the key.
     *
     * [reason] separates a plain outage from a DHT that is unreachable while HTTP works, which is
     * common on networks that drop UDP.
     */
    data class CouldNotCheck(val reason: ErrorReason) : HomeserverLookup
}
