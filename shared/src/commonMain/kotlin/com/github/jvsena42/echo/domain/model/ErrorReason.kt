package com.github.jvsena42.echo.domain.model

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

    /** Anything we could not classify. */
    Unknown,
}
