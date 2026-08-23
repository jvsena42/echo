package com.github.jvsena42.loopky.data.anki

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that keeps a crafted `.apkg` from inflating into the whole heap or the whole cache
 * partition. The streaming half of the enforcement lives in `BoundedZipReads`, over
 * `java.util.zip`; what is pinned here is the policy it applies.
 */
class ApkgLimitsTest {

    private val limit = ApkgLimits.MAX_MEDIA_BYTES

    @Test
    fun anEntryDeclaringMoreThanTheLimitIsRejectedBeforeItIsRead() {
        assertTrue(ApkgLimits.exceedsDeclaredSize(limit + 1, limit))
    }

    @Test
    fun anEntryExactlyAtTheLimitIsAllowed() {
        // Off-by-one here is a real deck that stops importing, so it is worth stating.
        assertFalse(ApkgLimits.exceedsDeclaredSize(limit, limit))
    }

    @Test
    fun anUnknownSizeIsNotRejectedUpFront() {
        // ZipEntry.getSize() is -1 when the central directory does not carry one. Rejecting on
        // that would refuse ordinary archives; the streamed copy is what stops an over-long one,
        // which is also the only defence against a header that simply lies.
        assertFalse(ApkgLimits.exceedsDeclaredSize(UNKNOWN_ENTRY_SIZE, limit))
    }

    @Test
    fun anUnderReportedSizeStillPassesThisCheck() {
        // Stated so nobody mistakes this for the enforcement: the size comes from the archive, so
        // a hostile one declares 1 byte and inflates to gigabytes. BoundedZipReads.copyBounded is
        // what actually holds.
        assertFalse(ApkgLimits.exceedsDeclaredSize(1L, limit))
    }

    @Test
    fun theCollectionBudgetIsWellAboveARealCollectionAndTheMediaBudgetAboveARealPicture() {
        // The largest real AnkiWeb collections are 1-5 MB; a flashcard picture is well under 32 MB.
        assertTrue(ApkgLimits.MAX_COLLECTION_BYTES >= 5L * MEGABYTE)
        assertTrue(ApkgLimits.MAX_MEDIA_BYTES >= 5L * MEGABYTE)
    }

    private companion object {
        const val UNKNOWN_ENTRY_SIZE = -1L
        const val MEGABYTE = 1024L * 1024
    }
}
