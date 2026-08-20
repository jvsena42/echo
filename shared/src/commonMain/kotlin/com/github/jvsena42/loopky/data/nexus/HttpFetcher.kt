package com.github.jvsena42.loopky.data.nexus

/** The verbs Loopky needs. Deliberately not the full HTTP set. */
enum class HttpMethod { GET, POST }

data class HttpRequest(
    val url: String,
    val method: HttpMethod = HttpMethod.GET,
    val headers: Map<String, String> = emptyMap(),
    /** UTF-8 request body; null for GET. Sent verbatim — the caller sets its own Content-Type. */
    val body: String? = null,
    /** Connect + read budget for this one call. Per-call because a long-poll needs far more. */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
)

data class HttpResponse(
    val statusCode: Int,
    /** The body as sent, on success *and* on failure — some APIs put the verdict only in here. */
    val body: String,
    /** Header names lower-cased; repeated headers joined with ", ". */
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccess: Boolean get() = statusCode in SUCCESS_RANGE

    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * Minimal HTTP abstraction for plain REST calls (Pubky Nexus, Unsplash, Homegate). Kept
 * deliberately tiny — Loopky's only non-Pubky network dependency — so each platform can back it
 * with its native stack (HttpURLConnection / NSURLSession) without pulling a client library into
 * shared.
 */
interface HttpFetcher {
    /**
     * Performs [request]. **A non-2xx status is a successful result carrying that status** —
     * only a transport failure (offline, DNS, TLS, timeout) is a `Result.failure`.
     *
     * Status-as-data on purpose: not every 4xx is a fault. A gated signup service answers 403
     * for "not available in your region", 408 for "ask me again", and 429 with the retry window
     * in the body — verdicts the caller must read, not exceptions it must catch and re-parse.
     * Modelling them as throws is how a response body, and with it the distinction between a
     * weekly and a yearly rate limit, gets discarded before anyone can look at it.
     */
    suspend fun send(request: HttpRequest): Result<HttpResponse>

    /**
     * Plain GET where any non-2xx is a failure carrying [HttpError]. The older, narrower contract;
     * callers that only ever expect 200 stay on it and are unaffected by [send]'s status-as-data.
     */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Result<String> =
        send(HttpRequest(url = url, headers = headers)).mapCatching { response ->
            // `mapCatching` rather than `runSuspendCatching`: this lambda is pure and synchronous,
            // so it cannot observe cancellation.
            if (!response.isSuccess) {
                throw HttpError(response.statusCode, "GET $url failed with HTTP ${response.statusCode}")
            }
            response.body
        }
}

const val DEFAULT_TIMEOUT_MS = 15_000L

internal val SUCCESS_RANGE = 200..299

/**
 * A non-2xx response, for callers on the [HttpFetcher.get] contract.
 *
 * Carries the status only — never the response body. Bodies from third-party services can echo
 * back whatever was sent (a phone number, a code), and this message reaches logcat. Callers that
 * need the body use [HttpFetcher.send], which hands it over without routing it through an
 * exception message.
 */
class HttpError(val statusCode: Int, message: String) : RuntimeException(message)
