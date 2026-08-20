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
}

/**
 * Classify a failure from the signup paths.
 *
 * Anything that is not a Homegate verdict is [SignupError.Unavailable] — on these screens the only
 * other realistic cause is the network, and "try again in a moment" is the same advice either way.
 * The raw throwable still goes to the log.
 */
internal fun Throwable.toSignupError(): SignupError = when (this) {
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
