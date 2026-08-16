package com.github.jvsena42.loopky.data.pubky

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * How many homeserver requests may be in flight at once.
 *
 * Every repository operation used to be a serial loop, so publishing a 20k-card deck meant 201
 * round trips end to end. Bounded rather than unlimited: the FFI's concurrency characteristics
 * are undocumented (see `docs/Architecture.md §8.4`), and a homeserver is well within its rights
 * to rate-limit a client that opens hundreds of parallel connections.
 */
internal const val MAX_IN_FLIGHT = 8

/**
 * Map [items] concurrently, at most [limit] at a time, preserving input order in the result.
 *
 * Fails fast: the first [transform] to throw cancels the rest and propagates, which is what
 * callers writing to a homeserver want — carrying on after a failed write would leave a deck in a
 * state the manifest does not describe.
 */
internal suspend fun <T, R> List<T>.mapConcurrently(
    limit: Int = MAX_IN_FLIGHT,
    transform: suspend (T) -> R,
): List<R> {
    if (isEmpty()) return emptyList()
    if (size == 1) return listOf(transform(first()))
    val gate = Semaphore(limit)
    return coroutineScope {
        map { item -> async { gate.withPermit { transform(item) } } }.awaitAll()
    }
}
