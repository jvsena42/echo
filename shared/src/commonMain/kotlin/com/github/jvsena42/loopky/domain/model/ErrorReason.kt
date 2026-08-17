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

    /** Anything we could not classify. */
    Unknown,
}
