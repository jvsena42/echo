package com.github.jvsena42.loopky.data.pubky

/**
 * Redaction helpers for the two auth artefacts that must never reach a log sink.
 *
 * `Log.d` is **not** stripped in release — `AndroidLog.d` writes to logcat in every build type —
 * so anything passed to it on the sign-in path is readable by any process holding `READ_LOGS`,
 * by `adb logcat`, and by crash reporters that attach the log buffer. Both of the values below
 * are live bearer credentials, not identifiers:
 *
 * - the `secret` in a `pubkyauth://` URL is the client secret the auth token is encrypted to, so
 *   leaking it turns the relay's encrypted blob back into a usable token;
 * - `session_secret` in the approval payload authenticates every subsequent write.
 *
 * A signup URL additionally carries `st={signupToken}` — single-use, non-expiring, and possibly
 * paid for — which is why this lives here rather than being inlined at one call site.
 */

/** Query keys whose values are credentials. Matched case-insensitively. */
private val SENSITIVE_QUERY_KEYS = setOf("secret", "st")

/** JSON keys whose values are credentials. */
private val SENSITIVE_JSON_KEYS = setOf("session_secret", "sessionSecret", "secret")

private const val REDACTED = "…"

/**
 * A `pubkyauth://` URL with credential-bearing query values replaced, safe to log.
 *
 * Keeps the structure — scheme, intent host, and which keys were present — because that is what
 * makes the log useful for diagnosing a malformed URL, and none of it is secret.
 */
internal fun String.redactAuthUrl(): String {
    val queryStart = indexOf('?')
    if (queryStart == -1) return this
    val prefix = substring(0, queryStart)
    val redactedQuery = substring(queryStart + 1)
        .split('&')
        .joinToString("&") { param ->
            val eq = param.indexOf('=')
            if (eq == -1) {
                param
            } else {
                val key = param.substring(0, eq)
                if (key.lowercase() in SENSITIVE_QUERY_KEYS) "$key=$REDACTED" else param
            }
        }
    return "$prefix?$redactedQuery"
}

/**
 * A one-line shape summary of a session payload — never its contents.
 *
 * Deliberately not "the JSON with secrets masked": the payload comes from Pubky Ring and its
 * exact key set is not ours to predict, so an allow-list of keys to *print* is safer than a
 * deny-list of keys to hide. What a reader of the log actually needs is whether the payload
 * arrived and which fields it carried.
 */
internal fun String.redactSessionPayload(): String {
    val keys = Regex("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:")
        .findAll(this)
        .map { it.groupValues[1] }
        .map { if (it in SENSITIVE_JSON_KEYS) "$it=$REDACTED" else it }
        .toList()
    return if (keys.isEmpty()) "$length chars" else keys.joinToString(", ", "{", "}")
}
