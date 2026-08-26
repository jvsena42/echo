package com.github.jvsena42.loopky.domain.model

/**
 * Why an operation failed, in terms the UI can speak about.
 *
 * ViewModels carry this instead of an exception message: the FFI's text is a developer
 * diagnostic (`HTTP transport error: error sending request for url (https://_pubky.rc3om…)`)
 * and was being rendered verbatim to users. The raw string still goes to `Log.e`; the
 * platform layer maps this enum to localised copy.
 */
enum class ErrorReason {
    /** The homeserver could not be reached — no connectivity, DNS, TLS or timeout. */
    Offline,

    /** The stored session is no longer valid; the user has to sign in again. */
    SessionExpired,

    /** The requested record does not exist (e.g. a deck that was deleted). */
    NotFound,

    /**
     * The homeserver has no account for this pubky, so nothing can be read or written under it.
     *
     * Deliberately distinct from [SessionExpired], because the remedies are opposite: one means
     * "sign in again", the other means there is nothing to sign in to yet. Treating this as an
     * expiry signs out a user who was never signed in — discarding the pubky that *is* their
     * identity — and treating it as [NotFound] is how a blocked sign-in came to be reported as
     * "this deck no longer exists".
     *
     * Not derivable from an error string alone: see the note on `toErrorReason`.
     */
    NoHomeserverAccount,

    /** No signed-in session for an operation that requires one. */
    NotSignedIn,

    /** Pubky Ring is not installed, so the sign-in deeplink has nowhere to go. */
    RingNotInstalled,

    /** The Pubky Ring authorisation did not complete. */
    AuthFailed,

    /**
     * Pubky's authorisation relay did not answer, so the approval could never be collected.
     *
     * Deliberately distinct from [Offline]: the relay is a different host from the homeserver and
     * fails on its own — on the days this bites, deck reads and Nexus queries keep working, so
     * "you're offline" would send the user to check a connection that is fine. It is also not
     * [AuthFailed], which blames Pubky Ring for something Ring never saw (#59).
     */
    AuthRelayUnreachable,

    /**
     * The homeserver answered 429: the request was well-formed, and it is rate-limiting us.
     *
     * Distinct from [Offline] for the same reason [AuthRelayUnreachable] is: the homeserver
     * answered, so the device's connection is fine, and "you're offline — check your connection"
     * sends the user to fix something that is not broken. Seen deleting a large deck, which is
     * ~90 records and trips the limiter even after the retries back off.
     *
     * Unlike [StorageFull] this does fix itself, so "try again in a moment" is honest advice.
     */
    ServerBusy,

    /**
     * The homeserver refused the write because the account is out of storage (507).
     *
     * The odd one out among these: every other reason either fixes itself (an outage), or is
     * fixed by signing in again. This one is fixed only by the user deleting something or paying
     * for more room, and **retrying is the one thing that cannot work** — which is precisely what
     * the generic "please try again" copy it used to fall through to told them to do.
     *
     * Terminal by construction, so background work must stop on it rather than back off: a
     * WorkManager retry chain against a full quota never converges. See #91.
     */
    StorageFull,

    /**
     * We could not ask the DHT whether a pubky has an account, so we do not know.
     *
     * Deliberately distinct from [Offline], for the same reason [AuthRelayUnreachable] is: pkarr
     * resolution runs over UDP to the mainline DHT, which plenty of carrier and corporate networks
     * drop while HTTP keeps working perfectly. Rendering that as "you're offline" sends someone to
     * check a connection that is fine — and on the restore screen it would be worse than useless,
     * because the one thing this state must never do is read as a verdict on the recovery phrase
     * the user just typed.
     *
     * Always paired with a retry. "We couldn't check" is not "there is no account" (#147).
     */
    HomeserverLookupFailed,

    /** Anything we could not classify. */
    Unknown,
}
