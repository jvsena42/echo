package com.github.jvsena42.eco.data.nexus

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [HttpFetcher] backed by [HttpURLConnection] — no client library needed for Echo's
 * single plain-GET use case (Nexus reads).
 */
class AndroidHttpFetcher : HttpFetcher {

    override suspend fun get(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                if (code !in SUCCESS_RANGE) {
                    throw HttpError(code, "GET $url failed with HTTP $code")
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
        val SUCCESS_RANGE = 200..299
    }
}
