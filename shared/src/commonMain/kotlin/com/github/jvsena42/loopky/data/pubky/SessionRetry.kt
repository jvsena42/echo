package com.github.jvsena42.loopky.data.pubky

import kotlinx.coroutines.delay

/**
 * Returns true if this failure looks like a session-expired error from the homeserver.
 * The FFI surfaces this as a [PubkyError] with a message containing "session" plus
 * one of the common verbs. We match defensively on substrings because the FFI error
 * text is not a stable API contract.
 *
 * **A transport failure is never an expiry, however it is worded.** Offline, the FFI reports
 * `"Failed to import session: Request failed: HTTP transport error: error sending request for
 * url (…/session)"` — which contains both "session" and "import" and so matched here. The request
 * never reached the homeserver, so nothing can be concluded about the session; treating it as an
 * expiry told an offline user to sign in with Pubky Ring again, and `requiresReauth` would have
 * signed them out over a dropped connection. Checked first, because the wording overlaps.
 */
internal fun Throwable.isSessionExpired(): Boolean {
    if (this !is PubkyError) return false
    val msg = message?.lowercase() ?: return false
    if (isNetworkFailure()) return false
    return "session" in msg &&
        ("import" in msg || "expired" in msg || "invalid" in msg)
}

/**
 * Public helper for ViewModels: returns true when a repository failure means the stored
 * session could not be refreshed and the user has to sign in again. Repos already retry
 * once via [putWithSessionRetry] and friends, so by the time a failure reaches a ViewModel
 * a session-expired error is terminal.
 */
fun Throwable.requiresReauth(): Boolean = isSessionExpired()

/**
 * Run a session-authenticated write, retrying the two failures that are worth retrying.
 *
 * **Session expiry:** revalidate once and try again. The secret is read from [session] on each
 * attempt rather than captured, so the retry naturally picks up the refreshed one.
 *
 * **Rate limiting:** back off and retry, up to [MAX_RATE_LIMIT_RETRIES] times with an
 * exponentially growing delay. Measured behaviour, not speculation — a homeserver returns 429
 * when a publish pushes several writes at once, and without this a large import fails outright
 * partway through. The failure is transient and the request well-formed, so surfacing it to the
 * user would be wrong.
 *
 * **Not** retried: a 507 out-of-storage. It is terminal until the user deletes something, so a
 * retry chain against it only spends time reaching the same answer.
 *
 * [attempt] must re-read the session secret itself; it is invoked afresh for every try.
 */
private suspend fun withWriteRetry(
    session: SessionProvider,
    revalidator: SessionRevalidator,
    attempt: suspend (secret: String) -> Result<String>,
): Result<String> {
    var revalidated = false
    var backoff = INITIAL_BACKOFF_MS
    var rateLimitRetries = 0

    while (true) {
        val secret = session.requireSession().sessionSecret
        val result = attempt(secret)
        if (result.isSuccess) return result

        val error = result.exceptionOrNull() ?: return result

        when {
            error.isSessionExpired() && !revalidated -> {
                revalidated = true
                revalidator.revalidate().getOrElse { return Result.failure(it) }
            }

            // Never retried, and asserted here rather than relied on: 507 is terminal until the
            // user frees space, so backing off against it only delays the same failure (#91).
            error.isQuotaExceeded() -> return result

            error.isRateLimited() && rateLimitRetries < MAX_RATE_LIMIT_RETRIES -> {
                rateLimitRetries++
                delay(backoff)
                backoff *= 2
            }

            else -> return result
        }
    }
}

/** Session-authenticated PUT with expiry and rate-limit retries. */
internal suspend fun PubkyClient.putWithSessionRetry(
    url: String,
    content: String,
    session: SessionProvider,
    revalidator: SessionRevalidator,
): Result<String> = withWriteRetry(session, revalidator) { secret ->
    putWithSession(url, content, secret)
}

/** Same as [putWithSessionRetry], for binary payloads. */
internal suspend fun PubkyClient.putBytesWithSessionRetry(
    url: String,
    content: ByteArray,
    session: SessionProvider,
    revalidator: SessionRevalidator,
): Result<String> = withWriteRetry(session, revalidator) { secret ->
    putBytesWithSession(url, content, secret)
}

/** Same as [putWithSessionRetry], for deletes. */
internal suspend fun PubkyClient.deleteWithSessionRetry(
    url: String,
    session: SessionProvider,
    revalidator: SessionRevalidator,
): Result<String> = withWriteRetry(session, revalidator) { secret ->
    deleteWithSession(url, secret)
}

/** Enough to ride out a burst without leaving the user staring at a stalled progress bar. */
private const val MAX_RATE_LIMIT_RETRIES = 5

private const val INITIAL_BACKOFF_MS = 250L
