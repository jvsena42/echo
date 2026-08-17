package com.github.jvsena42.loopky.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspending code: identical, except a [CancellationException] is rethrown
 * rather than captured as a [Result.failure].
 *
 * `runCatching` catches `Throwable`, and coroutine cancellation *is* a `Throwable`. Wrapping a
 * suspending call in it turns "the caller went away" into an ordinary failure: the work stops being
 * cancellable from the caller's side, and every consumer downstream has to re-check
 * `err is CancellationException` before it can trust its own error path. Not theoretical here — a
 * paste parse superseded by the next keystroke used to render as "…was cancelled", and a
 * user-cancelled publish raced its own cancel handler for the error slot.
 *
 * Plain [runCatching] stays right for pure, synchronous blocks (JSON decoding, an `Intent` launch,
 * a `Cursor` query) — they cannot observe cancellation, so there is nothing to rethrow.
 */
@Suppress("TooGenericExceptionCaught")
suspend inline fun <T> runSuspendCatching(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (e: Throwable) {
        Result.failure(e)
    }
