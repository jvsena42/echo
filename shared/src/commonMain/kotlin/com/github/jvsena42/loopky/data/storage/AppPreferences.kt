package com.github.jvsena42.loopky.data.storage

import kotlinx.coroutines.flow.Flow

/**
 * Device-local, non-secret user preferences. Deliberately separate from [SecureSessionStore]:
 * that one exists for the signed-in session and pays keystore/keychain costs for it, and a
 * boolean the user flipped in Settings has no business sharing that door.
 *
 * Backed by `SharedPreferences` on Android and `NSUserDefaults` on iOS — the platform's plain
 * settings store, which is what these are.
 *
 * Device-local by choice for v1. If a preference ever has to survive a reinstall or reach a
 * second device it belongs in a `/pub/loopky/settings.json` record instead, and this interface is
 * the seam that would move.
 */
interface AppPreferences {
    /**
     * Whether Loopky may **ask** to announce a deck on Pubky — see
     * [com.github.jvsena42.loopky.data.repository.DiscoveryRepository.announceDeck].
     *
     * Not a visibility control. Publishing a deck already writes it publicly to the homeserver;
     * there are no private decks (spec §11). This governs only whether a post goes out to the
     * user's followers saying the deck exists.
     *
     * On by default, and **off means off**: no prompt, and nothing written. There is no
     * post-silently mode.
     *
     * Emits the current value immediately and again on every change.
     */
    val shareOnPubky: Flow<Boolean>

    suspend fun setShareOnPubky(enabled: Boolean)
}

internal const val PREFERENCES_NAME = "loopky.preferences"
internal const val KEY_SHARE_ON_PUBKY = "share_on_pubky"
internal const val DEFAULT_SHARE_ON_PUBKY = true
