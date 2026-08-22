package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.ErrorReason

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
 * The homeserver answered 429: too many requests in flight or too quickly.
 *
 * Measured, not assumed — publishing a 1,200-card deck with 8 concurrent writes reliably trips
 * this. It is a *transient* failure: the request was well-formed and will succeed after a pause,
 * so callers back off and retry rather than surfacing it.
 */
internal fun Throwable.isRateLimited(): Boolean {
    val msg = message?.lowercase() ?: return false
    return "429" in msg || "too many requests" in msg || "rate limit" in msg
}

/**
 * The homeserver refused the write because the account is out of its storage quota: **507
 * Insufficient Storage**, body `"Disk space quota exceeded"`.
 *
 * Matched on three independent substrings because the homeserver builds this answer in two
 * places — a pre-flight check against `used_bytes` before the write, and the storage layer's own
 * `DiskSpaceQuotaExceeded` — and the wording reaching the FFI need not be identical.
 *
 * Terminal, unlike every other classifier here: [isRateLimited] and [isNetworkFailure] are
 * transient and worth retrying, this one succeeds only after the user frees space. Callers must
 * treat it as a stop, not a backoff.
 */
internal fun Throwable.isQuotaExceeded(): Boolean {
    val msg = message?.lowercase() ?: return false
    return "insufficient storage" in msg ||
        "quota exceeded" in msg ||
        "disk space" in msg ||
        STATUS_507.containsMatchIn(msg)
}

/**
 * `507` as a status code rather than as three digits inside something else. Deliberately not a
 * bare `"507" in msg`: every failure message carries a `pubky://` URL, and deck and card ids are
 * random alphanumerics — one containing "507" would classify an unrelated error as a full disk.
 */
private val STATUS_507 = Regex("(?<![0-9a-z])507(?![0-9a-z])")

/**
 * Classify a repository failure for the UI. ViewModels put the returned [ErrorReason] into
 * their state and log the original throwable, so the FFI's diagnostic text never reaches a
 * user-facing surface.
 */
fun Throwable.toErrorReason(): ErrorReason = when {
    isSessionExpired() -> ErrorReason.SessionExpired
    // Checked ahead of the transient classifiers on purpose. Nothing they match collides with the
    // 507 body *today*, but "quota" is the word a future bandwidth limit will also reach for, and
    // reading a full disk as "the server is busy" would retry against it forever.
    isQuotaExceeded() -> ErrorReason.StorageFull
    // Retries are exhausted by the time this reaches the UI; the homeserver is simply busy. Not
    // Offline — it answered, so the device's connection is fine and saying otherwise sends the
    // user to check something that is not broken.
    isRateLimited() -> ErrorReason.ServerBusy
    isNetworkFailure() -> ErrorReason.Offline
    isNotFound() -> ErrorReason.NotFound
    else -> ErrorReason.Unknown
}
