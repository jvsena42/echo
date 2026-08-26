package com.github.jvsena42.loopky.domain.model

/**
 * How much a recovery-file passphrase is worth.
 *
 * This matters more here than it looks. The recovery file's key derivation uses **a fixed salt**
 * (`"recovery"`, in pubky-common's `recovery_file.rs`), not a per-file random one — so identical
 * passphrases produce identical keys across every user, and precomputation is available to an
 * attacker in a way it would not be with a salted file. Argon2id makes each guess expensive; only
 * length makes the guess count large.
 *
 * A nudge, never a block: someone locked out of exporting their own key by a strength meter has
 * been handed a worse outcome than a mediocre passphrase.
 */
enum class PassphraseStrength { TooShort, Weak, Fair, Strong }

/** Length-based on purpose: character-class rules push people toward `P@ssw0rd!`, which is not strong. */
fun strengthOf(passphrase: String): PassphraseStrength = when {
    passphrase.length < WEAK_MIN -> PassphraseStrength.TooShort
    passphrase.length < FAIR_MIN -> PassphraseStrength.Weak
    passphrase.length < STRONG_MIN -> PassphraseStrength.Fair
    else -> PassphraseStrength.Strong
}

private const val WEAK_MIN = 8
private const val FAIR_MIN = 12
private const val STRONG_MIN = 16
