package com.github.jvsena42.loopky.testing

import com.github.jvsena42.loopky.data.price.PriceSource

/**
 * A rate that is [rate], or null.
 *
 * Defaults to **null** on purpose: the no-quote path is the one that runs for anyone offline or
 * geoblocked, so it is the one a test gets unless it says otherwise.
 */
class FakePriceSource(var rate: Double? = null) : PriceSource {
    var calls = 0
        private set

    override suspend fun usdPerBtc(): Double? {
        calls++
        return rate
    }
}
