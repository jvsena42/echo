package com.github.jvsena42.echo.data.nexus

/**
 * Minimal HTTP GET abstraction for plain REST reads (Pubky Nexus). Kept deliberately tiny —
 * Echo's only non-Pubky network dependency — so each platform can back it with its native
 * stack (HttpURLConnection / NSURLSession) without pulling a client library into shared.
 *
 * Implementations must treat non-2xx responses as failures carrying [HttpError].
 */
interface HttpFetcher {
    suspend fun get(url: String): Result<String>
}

class HttpError(val statusCode: Int, message: String) : RuntimeException(message)
