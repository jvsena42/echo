package com.github.jvsena42.loopky.data.homegate

import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.nexus.HttpMethod
import com.github.jvsena42.loopky.data.nexus.HttpRequest
import com.github.jvsena42.loopky.data.nexus.HttpResponse
import com.github.jvsena42.loopky.data.repository.impl.loopkyJson
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Client for **Homegate**, the service that gates homeserver signups.
 *
 * Loopky cannot create an account on a `token_required` homeserver without a signup token, and
 * Homegate is where one comes from: prove you are a person by SMS, or by paying a small Lightning
 * invoice, or by presenting a hand-issued invite code. Each route ends in the same place — a
 * [SignupGrant] naming both the token and the homeserver it is good for.
 *
 * Errors are classified by *what the user can do about them* rather than by status code, the same
 * shape `UnsplashClient` uses. Nothing here puts a phone number or a code into an exception
 * message: those reach logcat.
 */
class HomegateClient(
    private val http: HttpFetcher,
    /**
     * No default on purpose. Staging and production Homegate hand out tokens for *different*
     * homeservers, so a missing wire-up has to fail to compile rather than quietly ship a release
     * pointed at staging (#42) — and here the cost is worse than a wrong read: a token minted
     * against the wrong server is rejected and, being single-use, gone.
     */
    private val baseUrl: String,
) {

    suspend fun smsAvailability(): MethodAvailability = availability("$baseUrl/sms_verification/info") { null }

    suspend fun lightningAvailability(): MethodAvailability =
        availability("$baseUrl/ln_verification/info") { it.longField("amountSat") }

    private suspend inline fun availability(
        url: String,
        priceOf: (JsonObject) -> Long?,
    ): MethodAvailability {
        val response = http.send(HttpRequest(url)).getOrElse {
            Log.w(TAG, "availability: could not reach Homegate — offering the method anyway")
            return MethodAvailability.Unknown
        }
        return when {
            response.statusCode == HTTP_FORBIDDEN -> MethodAvailability.Unavailable
            !response.isSuccess -> MethodAvailability.Unknown
            else -> {
                val price = runCatching { priceOf(response.body.asJsonObject()) }.getOrNull()
                MethodAvailability.Available(price)
            }
        }
    }

    /**
     * Ask Homegate to text a code. Any well-formed number is accepted — Homegate deliberately does
     * not reveal whether a number exists, so a success here says nothing about delivery.
     */
    suspend fun sendSmsCode(phoneNumber: String): Result<Unit> = runSuspendCatching {
        val response = post(
            url = "$baseUrl/sms_verification/send_code",
            body = buildJson("phoneNumber" to phoneNumber),
        )
        if (!response.isSuccess) throw HomegateException(response.toSmsSendError())
    }

    suspend fun validateSmsCode(phoneNumber: String, code: String): Result<SignupGrant> = runSuspendCatching {
        val response = post(
            url = "$baseUrl/sms_verification/validate_code",
            body = buildJson("phoneNumber" to phoneNumber, "code" to code),
        )
        if (!response.isSuccess) throw HomegateException(response.toGenericError())

        val json = response.body.asJsonObject()
        // Homegate returns `valid` as the JSON string "true" as often as a boolean; pubky-app's
        // own client carries the same `valid === 'true' || valid === true`. Anything else is false.
        val valid = json["valid"]?.asLooseBoolean() ?: false
        if (!valid) throw HomegateException(HomegateError.CodeIncorrect)
        json.toGrant()
    }

    suspend fun createLnInvoice(): Result<LnInvoice> = runSuspendCatching {
        val response = post(url = "$baseUrl/ln_verification", body = "{}")
        if (!response.isSuccess) throw HomegateException(response.toGenericError())

        val json = response.body.asJsonObject()
        LnInvoice(
            id = json.stringField("id") ?: error("Homegate returned no verification id"),
            bolt11 = json.stringField("bolt11Invoice") ?: error("Homegate returned no invoice"),
            amountSat = json.longField("amountSat") ?: 0L,
            expiresAtMillis = json.longField("expiresAt") ?: 0L,
        )
    }

    /**
     * Block until [invoice] is paid.
     *
     * A **loop of long-polls**, not one long request. Three reasons, in order of force: 408 from
     * this endpoint *means* "ask again", so the loop is part of the protocol; a single 60s socket
     * on mobile is routinely killed by doze or a sleeping radio, producing a timeout
     * indistinguishable from "unpaid" for a payment the user already made; and cancellation only
     * lands at an iteration boundary, so backing out of the screen does not leave a thread parked
     * for the rest of the window.
     *
     * @param nowMillis clock reader — passed in because `commonMain` has no ambient clock and
     *   tests need the loop to terminate on virtual time.
     */
    suspend fun awaitLnPayment(invoice: LnInvoice, nowMillis: () -> Long): Result<SignupGrant> = runSuspendCatching {
        val url = "$baseUrl/ln_verification/${invoice.id}/await"
        while (true) {
            if (invoice.expiresAtMillis in 1 until nowMillis()) {
                throw HomegateException(HomegateError.InvoiceExpired)
            }
            when (val outcome = pollOnce(url)) {
                is PollOutcome.Paid -> return@runSuspendCatching outcome.grant
                is PollOutcome.Failed -> throw HomegateException(outcome.error)
                is PollOutcome.AskAgain -> delay(outcome.afterMillis)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** One long-poll attempt, classified into what the loop should do next. */
    private suspend fun pollOnce(url: String): PollOutcome {
        val response = http.send(HttpRequest(url, timeoutMs = LN_AWAIT_TIMEOUT_MS)).getOrElse {
            // Transport failure, not a verdict: ask again rather than reporting an unpaid invoice
            // the user may already have paid. Doze and sleeping radios kill long sockets routinely.
            Log.w(TAG, "awaitLnPayment: transport failure, retrying")
            return PollOutcome.AskAgain(TRANSPORT_BACKOFF_MS)
        }
        return when {
            // The server waited its whole window and heard nothing. No delay — it already spent
            // the time on our behalf.
            response.statusCode == HTTP_REQUEST_TIMEOUT -> PollOutcome.AskAgain(0)

            response.statusCode == HTTP_NOT_FOUND -> PollOutcome.Failed(HomegateError.VerificationLost)

            response.statusCode == HTTP_TOO_MANY_REQUESTS ->
                PollOutcome.AskAgain(response.retryAfterSeconds()?.times(MILLIS_PER_SECOND) ?: TRANSPORT_BACKOFF_MS)

            !response.isSuccess -> PollOutcome.Failed(HomegateError.Unavailable)

            else -> {
                val json = response.body.asJsonObject()
                // Paid-but-not-yet-flagged is a real intermediate state; keep asking.
                if (json["isPaid"]?.asLooseBoolean() == true) {
                    PollOutcome.Paid(json.toGrant())
                } else {
                    PollOutcome.AskAgain(0)
                }
            }
        }
    }

    private suspend fun post(url: String, body: String): HttpResponse =
        http.send(
            HttpRequest(
                url = url,
                method = HttpMethod.POST,
                headers = mapOf("Content-Type" to "application/json"),
                body = body,
            ),
        ).getOrElse { throw HomegateException(HomegateError.Unavailable) }

    /**
     * Weekly and yearly limits are distinguishable only by substring-matching the error body —
     * Homegate ships no error code, and pubky-app's client carries the same TODO about it. A miss
     * degrades to [HomegateError.RateLimitedTemporary], which is the recoverable verdict, never a
     * terminal one.
     */
    private fun HttpResponse.toSmsSendError(): HomegateError = when (statusCode) {
        HTTP_FORBIDDEN -> HomegateError.PhoneBlocked
        HTTP_TOO_MANY_REQUESTS -> {
            val reason = body.lowercase()
            when {
                "weekly" in reason -> HomegateError.RateLimitedWeekly
                "yearly" in reason || "annual" in reason -> HomegateError.RateLimitedYearly
                else -> HomegateError.RateLimitedTemporary(retryAfterSeconds())
            }
        }
        else -> HomegateError.Unavailable
    }

    private fun HttpResponse.toGenericError(): HomegateError = when (statusCode) {
        HTTP_FORBIDDEN -> HomegateError.Geoblocked
        HTTP_NOT_FOUND -> HomegateError.VerificationLost
        else -> HomegateError.Unavailable
    }

    private fun HttpResponse.retryAfterSeconds(): Int? = header("Retry-After")?.trim()?.toIntOrNull()

    private companion object {
        const val TAG = "Loopky/Homegate"

        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429

        const val MILLIS_PER_SECOND = 1000L

        /** Above the server's own 60s window, so our timeout can never pre-empt its 408. */
        const val LN_AWAIT_TIMEOUT_MS = 70_000L
        const val TRANSPORT_BACKOFF_MS = 3_000L
    }
}

/** What one poll of the await endpoint told us to do next. */
private sealed interface PollOutcome {
    data class Paid(val grant: SignupGrant) : PollOutcome
    data class AskAgain(val afterMillis: Long) : PollOutcome
    data class Failed(val error: HomegateError) : PollOutcome
}

private fun JsonObject.toGrant(): SignupGrant {
    val token = stringField("signupCode") ?: throw HomegateException(HomegateError.Unavailable)
    val homeserver = stringField("homeserverPubky") ?: throw HomegateException(HomegateError.Unavailable)
    return SignupGrant(token = token, homeserverPubky = homeserver)
}

private fun String.asJsonObject(): JsonObject = loopkyJson.parseToJsonElement(this).jsonObject

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.longField(name: String): Long? =
    (this[name] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

/** Homegate sends `valid`/`isPaid` as the JSON string "true" as often as a boolean. */
private fun JsonElement.asLooseBoolean(): Boolean =
    (this as? JsonPrimitive)?.content.equals("true", ignoreCase = true)

/** Minimal request-body builder — one flat object of string fields is all Homegate takes. */
private fun buildJson(vararg fields: Pair<String, String>): String =
    JsonObject(fields.associate { (key, value) -> key to JsonPrimitive(value) }).toString()
