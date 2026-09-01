package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.ErrorReason

/**
 * Classifiers for FFI failures. Like [isSessionExpired] these match defensively on message
 * substrings, because the FFI error text is not a stable API contract — a miss degrades to
 * "unknown error", never to silently wrong behaviour.
 */

/**
 * The path does not exist on the homeserver. For a list, that means "nothing here yet"; for a
 * profile, "this account has published none" — an answer, not a failure to get one.
 *
 * Prefers [PubkyError.status] when the message named one, so a 500 whose body happens to say
 * "not found" is not read as an absent record. Falls back to substrings otherwise, which is
 * where the FFI's own wording (`"not found: pubky://…"`) and every non-HTTP miss land.
 */
internal fun Throwable.isNotFound(): Boolean {
    (this as? PubkyError)?.status?.let { return it == HTTP_NOT_FOUND }
    val msg = message?.lowercase() ?: return false
    return "not found" in msg || "notfound" in msg || STATUS_404.containsMatchIn(msg)
}

private const val HTTP_NOT_FOUND = 404

/**
 * `404` as a status code rather than as three digits inside something else — same reasoning as
 * [STATUS_507], and the same hazard: every failure message carries a `pubky://` URL, and deck
 * and card ids are random alphanumerics.
 */
private val STATUS_404 = Regex("(?<![0-9a-z])404(?![0-9a-z])")

/**
 * `get_homeserver` answered `Ok(None)`: this pubky has published no homeserver record, so it has
 * never had an account anywhere. The fork turns that into an *error* carrying this exact wording
 * (`pubky-core-ffi-fork/src/lib.rs`, `get_homeserver`), which is why it needs classifying at all.
 *
 * Deliberately ordered **ahead of** [isNetworkFailure] in [toErrorReason], and that is not
 * tidiness. The `Err(...)` arm of the same FFI call reports a DHT failure as
 * `"Failed to get homeserver: ... failed to resolve ..."`, and `"failed to resolve"` is in
 * [isNetworkFailure]'s list. Letting the transport classifier win would answer "you're offline"
 * for a phrase that simply belongs to no account — a verdict on the user's connection when we
 * have the real answer in hand (#147).
 *
 * Safe this early because the string comes from one call site in the fork: no deck, card or
 * profile read can produce it, so it cannot swallow an ordinary not-found.
 */
internal fun Throwable.isNoHomeserverRecord(): Boolean =
    message?.lowercase()?.contains("no homeserver found") == true

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
 * The FFI could not import the session secret at all — the wording every `*_with_session` entry
 * point returns when `restore_session` fails (`session_cache.rs`, one call site).
 *
 * On its own it says nothing about *why*: the fork wraps whatever went wrong behind that one
 * prefix, so an expiry, a 429 and a dead connection all arrive worded identically. It is only ever
 * useful combined with a second classifier, which is why it is not mapped to a reason by itself.
 */
internal fun Throwable.isSessionImportFailure(): Boolean =
    message?.lowercase()?.contains("failed to import session") == true

/**
 * The session round trip that opens every authenticated write could not be made: an import
 * failure whose cause was transport, not an answer from the homeserver.
 *
 * This is a *narrower* statement than [isNetworkFailure], and the narrowing is the point. Every
 * authenticated write begins with `POST https://_pubky.<pubky>/session`, so when that host cannot
 * be reached the whole write path dies while everything else — Nexus reads, ordinary HTTPS —
 * keeps working. Measured on device over three separate hours-long sessions (#165): pkarr
 * resolved, `homeserver.pubky.app` answered 200, TCP to its advertised port connected, and only
 * the session preamble failed. Reporting that as [ErrorReason.Offline] sent the user to check a
 * connection that was fine, and it was the *only* thing the publish flow said.
 *
 * Not an expiry, however much the wording overlaps — see the note on [isSessionExpired]. The
 * request never arrived, so the session may well still be good; the caller retries it through a
 * fresh import rather than signing anyone out.
 */
internal fun Throwable.isSessionUnreachable(): Boolean =
    isSessionImportFailure() && isNetworkFailure()

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
    // Second, and ahead of every transport classifier: the FFI reports "this pubky has no
    // homeserver record" and "the DHT did not answer" through the same call, and only the first
    // is a fact about the user's key. See the note on isNoHomeserverRecord.
    isNoHomeserverRecord() -> ErrorReason.NoHomeserverAccount
    // Checked ahead of the transient classifiers on purpose. Nothing they match collides with the
    // 507 body *today*, but "quota" is the word a future bandwidth limit will also reach for, and
    // reading a full disk as "the server is busy" would retry against it forever.
    isQuotaExceeded() -> ErrorReason.StorageFull
    // Retries are exhausted by the time this reaches the UI; the homeserver is simply busy. Not
    // Offline — it answered, so the device's connection is fine and saying otherwise sends the
    // user to check something that is not broken.
    isRateLimited() -> ErrorReason.ServerBusy
    // Ahead of the transport classifier it is a special case of, because the two lead to opposite
    // advice: this one means the session round trip could not be made while the device's
    // connection is fine, and Offline's copy tells the user to go check that connection (#165).
    isSessionUnreachable() -> ErrorReason.SessionUnreachable
    isNetworkFailure() -> ErrorReason.Offline
    isNotFound() -> ErrorReason.NotFound
    else -> ErrorReason.Unknown
}
