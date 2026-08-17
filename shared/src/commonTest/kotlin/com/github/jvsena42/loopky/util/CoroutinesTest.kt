package com.github.jvsena42.loopky.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val LONG_ENOUGH_TO_BE_CANCELLED_MS = 10_000L

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutinesTest {

    @Test
    fun returnsSuccessForAValue() = runTest {
        assertEquals(42, runSuspendCatching { 42 }.getOrNull())
    }

    @Test
    fun capturesAnOrdinaryFailure() = runTest {
        val boom = IllegalStateException("boom")
        val result = runSuspendCatching { throw boom }

        assertTrue(result.isFailure)
        // Same instance, not a wrapper: callers pattern-match on the exception type and read
        // `message` straight off it, exactly as they did under runCatching.
        assertSame(boom, result.exceptionOrNull())
    }

    @Test
    fun awaitsSuspendingWork() = runTest {
        val result = runSuspendCatching {
            delay(1)
            "done"
        }

        assertEquals("done", result.getOrNull())
    }

    @Test
    fun rethrowsADirectlyThrownCancellationException() = runTest {
        assertFailsWith<CancellationException> {
            runSuspendCatching { throw CancellationException("nope") }
        }
    }

    @Test
    fun rethrowsRealCoroutineCancellation() = runTest {
        // The load-bearing case: cancelling the caller must abort the whole coroutine, not hand
        // back a failed Result that lets the code after the block carry on regardless.
        var enteredBlock = false
        var reachedCodeAfterTheBlock = false
        val job = launch {
            runSuspendCatching {
                enteredBlock = true
                delay(LONG_ENOUGH_TO_BE_CANCELLED_MS)
            }
            reachedCodeAfterTheBlock = true
        }
        // Without this the test dispatcher never starts the body, cancel() kills a coroutine that
        // never ran, and the assertions below pass for the wrong reason.
        runCurrent()
        assertTrue(enteredBlock, "the block must be suspended inside the delay before cancelling")

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(reachedCodeAfterTheBlock, "cancellation must not fall through as a failure")
    }

    @Test
    fun rethrowsEnsureActive() = runTest {
        // Mirrors ImportRepositoryImpl.parseLocked, which calls ensureActive() mid-parse so a
        // superseded run abandons its work instead of overwriting the winner's draft.
        var enteredBlock = false
        var reachedCodeAfterTheBlock = false
        val job = launch {
            runSuspendCatching {
                enteredBlock = true
                delay(LONG_ENOUGH_TO_BE_CANCELLED_MS)
                coroutineContext.ensureActive()
            }
            reachedCodeAfterTheBlock = true
        }
        runCurrent()
        assertTrue(enteredBlock)

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(reachedCodeAfterTheBlock)
    }

    @Test
    fun plainRunCatchingSwallowsCancellation() = runTest {
        // The contrast case, and the reason this helper exists. Kept executable so that anyone
        // tempted to "simplify" runSuspendCatching back to runCatching sees what they would undo.
        var swallowed: Throwable? = null
        val job = launch {
            runCatching { delay(LONG_ENOUGH_TO_BE_CANCELLED_MS) }
                .onFailure { swallowed = it }
        }
        runCurrent()

        job.cancel()
        job.join()

        assertTrue(swallowed is CancellationException, "plain runCatching captures the cancellation")
    }
}
