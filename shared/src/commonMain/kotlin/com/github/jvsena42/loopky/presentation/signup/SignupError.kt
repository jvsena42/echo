package com.github.jvsena42.loopky.presentation.signup

import com.github.jvsena42.loopky.data.homegate.HomegateError
import com.github.jvsena42.loopky.data.homegate.HomegateException

/**
 * Why a step of the signup flow failed, in terms its screen can act on.
 *
 * Kept out of `ErrorReason` on purpose, following the same reasoning as `BulkImportError`: these
 * only ever occur on five screens that exist solely for this flow, and folding them in would give
 * the eight unrelated screens that render an `ErrorReason` a branch for "your invite code was
 * already used".
 */
enum class SignupError {
    /** Homegate does not offer this method in this region. */
    Geoblocked,

    /** This phone number cannot be used for verification. */
    PhoneBlocked,

    /** Too many attempts for now — recoverable by waiting. */
    RateLimited,

    /** Two verifications per week per number. Terminal until the window rolls. */
    RateLimitedWeekly,

    /** Four per year per number. Terminal for this number. */
    RateLimitedYearly,

    /** The SMS code did not match, or the invite code is not a code. */
    CodeIncorrect,

    /** The invoice expired before it was paid. */
    InvoiceExpired,

    /** Homegate forgot this verification; the flow has to restart. */
    VerificationLost,

    /** Pubky Ring could not complete the signup. The token is untouched and can be retried. */
    RingFailed,

    /** Pubky Ring is not installed, so the deeplink has nowhere to go. */
    RingNotInstalled,

    /** Offline, or Homegate answered with something we could not use. */
    Unavailable,

    /**
     * The homeserver refused the signup token itself — spent already, or not one it issued.
     *
     * **Terminal, and that is the point.** Retrying re-sends the same dead token forever, so this
     * is the one signup error that must not offer "try again". It only became reachable when
     * Loopky started redeeming tokens itself: on the Ring path Ring saw this verdict and Loopky
     * never did, so the catch-all below reported it as "check your connection" while logcat said
     * `401 Unauthorized - Invalid token`.
     */
    TokenRejected,
}

/**
 * Classify a failure from the signup paths.
 *
 * Anything that is neither a Homegate verdict nor a rejected token is [SignupError.Unavailable] —
 * on these screens the other realistic cause is the network, and "try again in a moment" is the
 * same advice either way. The raw throwable still goes to the log.
 *
 * [isTokenRejected] is checked because Loopky now redeems tokens itself: the homeserver's verdict
 * arrives here as an ordinary FFI failure, and the catch-all turned "this token is dead" into
 * "check your connection", under a "Try again" that could only ever fail.
 */
internal fun Throwable.toSignupError(): SignupError = when {
    isTokenRejected() -> SignupError.TokenRejected
    else -> toHomegateSignupError()
}

/**
 * The homeserver refused the signup token.
 *
 * Matched on the wording `signup failure: … 401 Unauthorized - Invalid token` seen on device, and
 * on 403 for the same class of refusal. Narrow on purpose: a 401 on any *other* signup call would
 * be a session problem, and these two calls are the only ones that carry a token.
 */
private fun Throwable.isTokenRejected(): Boolean {
    val msg = message?.lowercase() ?: return false
    if ("signup" !in msg) return false
    // Explicit token wording, or a status code *with* the word that goes with it. The bare
    // substrings "401"/"403" matched a port number, a request id or a byte count — and because
    // this is the only classification that unlocks `clearPending()`, a misclassified transient
    // failure threw away a token the user may have paid sats for.
    if ("invalid token" in msg || "signup token" in msg || "invalid signup" in msg) return true
    val unauthorized = STATUS_401.containsMatchIn(msg) && "unauthorized" in msg
    val forbidden = STATUS_403.containsMatchIn(msg) && "forbidden" in msg
    return unauthorized || forbidden
}

/** Word-bounded, for the same reason `PubkyErrors` bounds its 507: ids are random alphanumerics. */
private val STATUS_401 = Regex("(?<![0-9a-z])401(?![0-9a-z])")
private val STATUS_403 = Regex("(?<![0-9a-z])403(?![0-9a-z])")

private fun Throwable.toHomegateSignupError(): SignupError = when (this) {
    is HomegateException -> when (error) {
        HomegateError.Geoblocked -> SignupError.Geoblocked
        HomegateError.PhoneBlocked -> SignupError.PhoneBlocked
        is HomegateError.RateLimitedTemporary -> SignupError.RateLimited
        HomegateError.RateLimitedWeekly -> SignupError.RateLimitedWeekly
        HomegateError.RateLimitedYearly -> SignupError.RateLimitedYearly
        HomegateError.CodeIncorrect -> SignupError.CodeIncorrect
        HomegateError.InvoiceExpired -> SignupError.InvoiceExpired
        HomegateError.VerificationLost -> SignupError.VerificationLost
        HomegateError.Unavailable -> SignupError.Unavailable
    }

    else -> SignupError.Unavailable
}
