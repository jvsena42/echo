package com.github.jvsena42.loopky.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FiatFormatTest {

    @Test
    fun aTypicalSignupPriceRendersAsAnApproximateDollarAmount() {
        // 2000 sats at $100k/BTC is $2.00.
        assertEquals("≈ US\$2.00", formatSatsAsUsd(sats = 2_000, usdPerBtc = 100_000.0))
    }

    @Test
    fun subCentAmountsSayLessThanACentRatherThanZero() {
        // Anything under half a cent would round to "$0.00", which reads as *free* about something
        // that costs money.
        assertEquals("< US\$0.01", formatSatsAsUsd(sats = 3, usdPerBtc = 100_000.0))
    }

    @Test
    fun theStagingPriceOfTenSatsRendersAsARealCentRatherThanTheUnderflowString() {
        // 10 sats at $100k/BTC is exactly one cent — the boundary, and the figure a developer will
        // actually see on staging.
        assertEquals("≈ US\$0.01", formatSatsAsUsd(sats = 10, usdPerBtc = 100_000.0))
    }

    @Test
    fun noRateMeansNoStringAtAllRatherThanAPlaceholder() {
        // The caller then renders the sats-only line unchanged. This is the path that actually
        // runs for anyone offline or geoblocked, and it is the one that regresses silently.
        assertNull(formatSatsAsUsd(sats = 2_000, usdPerBtc = null))
    }

    @Test
    fun anImpossibleRateIsTreatedAsNoRate() {
        assertNull(formatSatsAsUsd(sats = 2_000, usdPerBtc = 0.0))
        assertNull(formatSatsAsUsd(sats = 2_000, usdPerBtc = -1.0))
    }

    @Test
    fun theCurrencyIsLabelledSoItIsNotMistakenForALocalDollar() {
        // A bare "$" shown in Canada, Australia or Brazil is a number in a currency the reader
        // will assume is theirs.
        val rendered = formatSatsAsUsd(sats = 1_000_000, usdPerBtc = 100_000.0)

        assertEquals("≈ US\$1000.00", rendered)
    }

    @Test
    fun centsAreZeroPaddedRatherThanTruncated() {
        assertEquals("≈ US\$1.05", formatSatsAsUsd(sats = 1_050, usdPerBtc = 100_000.0))
    }
}
