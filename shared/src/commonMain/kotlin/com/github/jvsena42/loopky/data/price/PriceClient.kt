package com.github.jvsena42.loopky.data.price

import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A best-effort BTC/USD rate, used only to put a familiar number beside the sats price on the two
 * Lightning signup surfaces.
 *
 * **Best-effort means invisible on failure.** "Pay ₿ 2000" is meaningless to the audience this
 * whole feature is for — someone choosing between "free SMS" and "a small Bitcoin payment" cannot
 * weigh the second without knowing whether it is 30 cents or 30 dollars. But the sats figure *is*
 * the price; the fiat one is a courtesy. No rate means the screen renders exactly as it did
 * before, with no spinner, no error, and no placeholder.
 *
 * **Why this is a client-side ticker and not Homegate.** Homegate is the right long-term source —
 * authoritative at the moment of quoting, no extra round trip, no third party — but it does not
 * carry a fiat field today and adding one is someone else's backlog. `PriceSource` is the seam, so
 * swapping the Koin binding later touches no ViewModel.
 *
 * **Why the exposure is acceptable.** This is only called from the two Lightning surfaces, and only
 * when Homegate has actually quoted a price — so the request comes from users already on the
 * Lightning branch, not from everyone who opens onboarding. One source, no fallback chain: a chain
 * would triple the number of parties who learn that somebody is about to create a Pubky account,
 * to buy a courtesy figure.
 */
interface PriceSource {
    /** USD per BTC, or null when unknown. Never throws. */
    suspend fun usdPerBtc(): Double?
}

class PriceClient(
    private val http: HttpFetcher,
    private val nowMillis: () -> Long,
    private val baseUrl: String = MEMPOOL_PRICES_URL,
) : PriceSource {

    private val lock = Mutex()
    private var cachedRate: Double? = null
    private var cachedAtMillis: Long = 0

    override suspend fun usdPerBtc(): Double? = lock.withLock {
        val now = nowMillis()
        // Past the TTL the cache is *dropped*, not served. The invoice screen is where the user
        // commits money, and a rate from an hour ago is worse there than no rate at all.
        cachedRate?.takeIf { now - cachedAtMillis < TTL_MS }?.let { return@withLock it }

        val rate = runSuspendCatching { fetchRate() }
            .onFailure { Log.w(TAG, "usdPerBtc: unavailable — showing sats only") }
            .getOrNull()

        if (rate != null) {
            cachedRate = rate
            cachedAtMillis = now
        } else {
            cachedRate = null
        }
        rate
    }

    private suspend fun fetchRate(): Double? {
        val body = http.get(baseUrl, headers = emptyMap()).getOrThrow()
        val usd = priceJson.parseToJsonElement(body).jsonObject["USD"]?.jsonPrimitive?.doubleOrNull
        return usd?.takeIf { it > 0 }
    }

    private companion object {
        const val TAG = "Loopky/PriceClient"

        /** Short, because the invoice screen is a commitment point rather than a browse. */
        const val TTL_MS = 5 * 60 * 1000L

        /** No key, no per-request identifier, and a Bitcoin-native operator. */
        const val MEMPOOL_PRICES_URL = "https://mempool.space/api/v1/prices"
    }
}

private val priceJson: Json = Json { ignoreUnknownKeys = true }
