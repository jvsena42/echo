package com.github.jvsena42.echo.data.nexus

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithURL
import kotlin.coroutines.resume

/**
 * [HttpFetcher] backed by [NSURLSession] — keeps the shared module free of any HTTP
 * client dependency for Echo's single plain-GET use case (Nexus reads).
 */
class IosHttpFetcher : HttpFetcher {

    override suspend fun get(url: String): Result<String> {
        val nsUrl = NSURL.URLWithString(url)
            ?: return Result.failure(IllegalArgumentException("Invalid URL: $url"))

        return suspendCancellableCoroutine { continuation ->
            val task = NSURLSession.sharedSession.dataTaskWithURL(nsUrl) { data, response, error ->
                val result: Result<String> = when {
                    error != null ->
                        Result.failure(RuntimeException(error.localizedDescription))

                    else -> {
                        val statusCode =
                            (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                        if (statusCode !in 200..299) {
                            Result.failure(HttpError(statusCode, "GET $url failed with HTTP $statusCode"))
                        } else {
                            Result.success(data.toUtf8String())
                        }
                    }
                }
                continuation.resume(result)
            }
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }

    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun NSData?.toUtf8String(): String {
        if (this == null) return ""
        return NSString.create(data = this, encoding = NSUTF8StringEncoding) as? String ?: ""
    }
}
