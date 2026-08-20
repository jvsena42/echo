package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.data.homegate.SignupGrant
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Platform-keystore-backed store for a signup token the user has obtained but not yet spent.
 *
 * **Why this is persisted at all.** A signup token is single-use and never expires, and the user
 * may have paid sats for it or spent one of the two SMS verifications they get per week. It is
 * minted *before* Pubky Ring is asked to redeem it, so any failure in between — Ring declining,
 * the relay dropping, the process being killed — would otherwise lose something they paid for with
 * no way to recover it. Holding it only in a ViewModel's state is therefore not an option.
 *
 * **Why the secrets vault and not the session one.** A token is spent before there is a session at
 * all, and has to survive signing out — which is exactly the split [SECRETS_SERVICE_NAME] and
 * [SESSION_SERVICE_NAME] exist for. Sharing the session vault would mean sign-out could discard a
 * paid-for token.
 *
 * **Why it is a credential.** Anyone holding the token can redeem it against the homeserver, so it
 * goes through the keystore rather than [AppPreferences], and nothing here logs.
 */
interface SignupTokenStore {
    /** The unspent token, or null. Emits the current value immediately, then changes. */
    val pending: Flow<PendingSignup?>

    suspend fun save(pending: PendingSignup)

    /**
     * Drop the stored token.
     *
     * Only call this on **proof** the token was redeemed. If Ring's signup fails while it holds
     * another already-signed-up pubky, it quietly authorises that one instead — which returns a
     * perfectly valid session while the token is still unspent. Clearing on "we got a session"
     * would throw the token away in exactly that case.
     */
    suspend fun clear()
}

/**
 * A minted, unspent signup token and the homeserver it is good for.
 *
 * [homeserverPubky] travels with the token because it is not interchangeable: the token is only
 * redeemable on the homeserver whose Homegate issued it. The Ring hand-off reads this value rather
 * than the configured environment, so switching environments mid-flow cannot misdirect a token
 * that already exists.
 */
@Serializable
data class PendingSignup(
    val token: String,
    val homeserverPubky: String,
    /** How it was obtained — lets the UI say "your payment is still good". */
    val source: Source,
) {
    enum class Source { Sms, Lightning, Invite }

    companion object {
        fun from(grant: SignupGrant, source: Source): PendingSignup =
            PendingSignup(token = grant.token, homeserverPubky = grant.homeserverPubky, source = source)
    }
}

internal const val SIGNUP_TOKEN_STORAGE_KEY = "signup.pending.v1"
