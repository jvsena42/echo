package com.github.jvsena42.loopky.domain.model

import kotlin.math.roundToLong

/**
 * Render [sats] as an approximate US dollar amount, or null when no rate is known.
 *
 * Null in, null out: the caller then shows the sats-only string it always did, rather than a
 * placeholder. `$0.00` in particular would read as *free*.
 *
 * Deliberately marked approximate and explicitly `US$`. A bare `$` shown to a reader in Canada,
 * Australia or Brazil is a number in a currency they will assume is theirs.
 */
fun formatSatsAsUsd(sats: Long, usdPerBtc: Double?): String? {
    if (usdPerBtc == null || usdPerBtc <= 0 || sats <= 0) return null

    val usd = sats.toDouble() / SATS_PER_BTC * usdPerBtc
    // A few hundred sats is well under a cent, and rounding it to $0.00 says "free" about
    // something that costs money.
    if (usd < MIN_DISPLAYABLE) return "< US\$0.01"

    val cents = (usd * CENTS_PER_UNIT).roundToLong()
    val whole = cents / CENTS_PER_UNIT
    val remainder = (cents % CENTS_PER_UNIT).toString().padStart(2, '0')
    return "≈ US\$$whole.$remainder"
}

private const val SATS_PER_BTC = 100_000_000.0
private const val MIN_DISPLAYABLE = 0.005
private const val CENTS_PER_UNIT = 100
