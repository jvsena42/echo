package com.github.jvsena42.echo.data.pubky

import com.github.jvsena42.echo.domain.model.ErrorReason

/**
 * Classifiers for FFI failures. Like [isSessionExpired] these match defensively on message
 * substrings, because the FFI error text is not a stable API contract — a miss degrades to
 * "unknown error", never to silently wrong behaviour.
 */

/** The path does not exist on the homeserver. For a list, that means "nothing here yet". */
internal fun Throwable.isNotFound(): Boolean {
    val msg = message?.lowercase() ?: return false
    return "not found" in msg || "notfound" in msg || "404" in msg
}

/**
 * The request never reached the homeserver: no connectivity, DNS failure, TLS problem or
 * timeout. Distinct from a homeserver that answered with an error, and the difference matters
 * — Pubky is the only source of truth, so "we couldn't reach it" must never be rendered as
 * "you have nothing".
 */
internal fun Throwable.isNetworkFailure(): Boolean {
    val msg = message?.lowercase() ?: return false
    return "transport" in msg ||
        "error sending request" in msg ||
        "timed out" in msg ||
        "timeout" in msg ||
        "dns" in msg ||
        "connection refused" in msg ||
        "failed to resolve" in msg ||
        "network" in msg
}

/**
 * Classify a repository failure for the UI. ViewModels put the returned [ErrorReason] into
 * their state and log the original throwable, so the FFI's diagnostic text never reaches a
 * user-facing surface.
 */
fun Throwable.toErrorReason(): ErrorReason = when {
    isSessionExpired() -> ErrorReason.SessionExpired
    isNetworkFailure() -> ErrorReason.Offline
    isNotFound() -> ErrorReason.NotFound
    else -> ErrorReason.Unknown
}
