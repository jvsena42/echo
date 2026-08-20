package com.github.jvsena42.loopky.data.homegate

/**
 * A redeemable signup token and the homeserver it is good for.
 *
 * The two always travel together. A token minted by one Homegate instance is only valid on *its*
 * homeserver, and spending it against another is rejected — permanently, because the token is
 * single-use. Keeping them in one object is what stops a configured default from being
 * substituted for the server the token actually belongs to.
 */
data class SignupGrant(
    val token: String,
    val homeserverPubky: String,
)

/** Whether Homegate will serve a given verification method to this device. */
sealed interface MethodAvailability {
    /** Ready to use. [priceSat] is set only for the Lightning method. */
    data class Available(val priceSat: Long? = null) : MethodAvailability

    /** Homegate answered 403 — not offered in this region. */
    data object Unavailable : MethodAvailability

    /**
     * Could not ask. Rendered as **enabled**, deliberately: a flaky availability check must not
     * lock someone out of the only route into the app. The method's own screen will fail
     * honestly if it really is blocked.
     */
    data object Unknown : MethodAvailability
}

/** What the approval step can fail with, in terms the screen can act on. */
sealed interface HomegateError {
    /** Not offered in this region. */
    data object Geoblocked : HomegateError

    /** This number cannot be used for verification. */
    data object PhoneBlocked : HomegateError

    /** Retry later; [retryAfterSeconds] is present when Homegate said when. */
    data class RateLimitedTemporary(val retryAfterSeconds: Int?) : HomegateError

    /** Two per week per number. Terminal for this number until the window rolls. */
    data object RateLimitedWeekly : HomegateError

    /** Four per year per number. Terminal for this number. */
    data object RateLimitedYearly : HomegateError

    /** The SMS code did not match. */
    data object CodeIncorrect : HomegateError

    /** The invoice expired before it was paid. */
    data object InvoiceExpired : HomegateError

    /** Homegate no longer knows this verification — the id is gone, so the flow must restart. */
    data object VerificationLost : HomegateError

    /** Anything else: offline, 5xx, a body we could not parse. */
    data object Unavailable : HomegateError
}

class HomegateException(val error: HomegateError) : RuntimeException(error.toString())

/** A Lightning invoice awaiting payment. */
data class LnInvoice(
    val id: String,
    val bolt11: String,
    val amountSat: Long,
    /** Unix millis. The await loop stops here rather than polling a dead invoice forever. */
    val expiresAtMillis: Long,
)
