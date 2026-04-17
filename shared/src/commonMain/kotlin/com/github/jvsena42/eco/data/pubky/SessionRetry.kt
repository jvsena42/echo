package com.github.jvsena42.eco.data.pubky

/**
 * Returns true if this failure looks like a session-expired error from the homeserver.
 * The FFI surfaces this as a [PubkyError] with a message containing "session" plus
 * one of the common verbs. We match defensively on substrings because the FFI error
 * text is not a stable API contract.
 */
internal fun Throwable.isSessionExpired(): Boolean {
    if (this !is PubkyError) return false
    val msg = message?.lowercase() ?: return false
    return "session" in msg &&
            ("import" in msg || "expired" in msg || "invalid" in msg)
}

/**
 * Attempt [PubkyClient.putWithSession] with the current session. If it fails with a
 * session-expired error, revalidate once via [revalidator] and retry with the
 * refreshed secret.
 *
 * The secret is read from [session] on each attempt (not captured eagerly) so that
 * after revalidation updates the provider, the retry naturally gets the new secret.
 */
internal suspend fun PubkyClient.putWithSessionRetry(
    url: String,
    content: String,
    session: SessionProvider,
    revalidator: SessionRevalidator,
): Result<String> {
    val secret = session.requireSession().sessionSecret
    val first = putWithSession(url, content, secret)
    if (first.isSuccess) return first

    val error = first.exceptionOrNull() ?: return first
    if (!error.isSessionExpired()) return first

    revalidator.revalidate().getOrElse { return Result.failure(it) }
    val newSecret = session.requireSession().sessionSecret
    return putWithSession(url, content, newSecret)
}

/**
 * Same retry pattern for [PubkyClient.deleteWithSession].
 */
internal suspend fun PubkyClient.deleteWithSessionRetry(
    url: String,
    session: SessionProvider,
    revalidator: SessionRevalidator,
): Result<String> {
    val secret = session.requireSession().sessionSecret
    val first = deleteWithSession(url, secret)
    if (first.isSuccess) return first

    val error = first.exceptionOrNull() ?: return first
    if (!error.isSessionExpired()) return first

    revalidator.revalidate().getOrElse { return Result.failure(it) }
    val newSecret = session.requireSession().sessionSecret
    return deleteWithSession(url, newSecret)
}
