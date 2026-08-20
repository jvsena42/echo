package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.homegate.HomegateClient
import com.github.jvsena42.loopky.data.homegate.HomegateError
import com.github.jvsena42.loopky.data.homegate.HomegateException
import com.github.jvsena42.loopky.data.homegate.LnInvoice
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.homegate.SignupGrant
import com.github.jvsena42.loopky.data.repository.SignupAvailability
import com.github.jvsena42.loopky.data.repository.SignupRepository
import com.github.jvsena42.loopky.data.storage.PendingSignup
import com.github.jvsena42.loopky.data.storage.SignupTokenStore
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * [SignupRepository] over [HomegateClient] and [SignupTokenStore].
 *
 * Every path that obtains a token writes it to the store *before* returning, so there is no state
 * in which the user has spent an SMS attempt or paid an invoice and the token exists only in
 * memory. See [SignupTokenStore] for why that matters.
 */
class SignupRepositoryImpl(
    private val homegate: HomegateClient,
    private val tokenStore: SignupTokenStore,
    private val environment: PubkyEnvironment,
    private val nowMillis: () -> Long,
) : SignupRepository {

    override val pending: Flow<PendingSignup?> = tokenStore.pending

    override suspend fun availability(): SignupAvailability = coroutineScope {
        // Two independent probes for two independent cards; serialising them would show the user
        // a spinner for the sum of both round trips.
        val sms = async { homegate.smsAvailability() }
        val lightning = async { homegate.lightningAvailability() }
        SignupAvailability(sms = sms.await(), lightning = lightning.await())
    }

    override suspend fun sendSmsCode(phoneNumber: String): Result<Unit> =
        homegate.sendSmsCode(phoneNumber)

    override suspend fun redeemSmsCode(phoneNumber: String, code: String): Result<PendingSignup> =
        runSuspendCatching {
            val grant = homegate.validateSmsCode(phoneNumber, code).getOrThrow()
            grant.persist(PendingSignup.Source.Sms)
        }

    override suspend fun createInvoice(): Result<LnInvoice> = homegate.createLnInvoice()

    override suspend fun awaitInvoice(invoice: LnInvoice): Result<PendingSignup> =
        runSuspendCatching {
            val grant = homegate.awaitLnPayment(invoice, nowMillis).getOrThrow()
            grant.persist(PendingSignup.Source.Lightning)
        }

    override suspend fun redeemInviteCode(code: String): Result<PendingSignup> = runSuspendCatching {
        val normalised = code.normaliseInviteCode()
        // Checked locally so a typo costs no round trip — and, more usefully, so "that is not a
        // code" reads differently from "that code was already used".
        if (!normalised.isInviteCodeShaped()) throw HomegateException(HomegateError.CodeIncorrect)

        // No Homegate call on this path, so nothing tells us which homeserver the code is for;
        // the configured environment's default is the only available answer. Whether the code is
        // actually valid is settled when Ring redeems it — there is no pre-check reachable from
        // here, because the homeserver is addressed by a pkarr name that the HTTP stack cannot
        // resolve.
        SignupGrant(token = normalised, homeserverPubky = environment.defaultHomeserver)
            .persist(PendingSignup.Source.Invite)
    }

    override suspend fun clearPending() = tokenStore.clear()

    private suspend fun SignupGrant.persist(source: PendingSignup.Source): PendingSignup {
        val pending = PendingSignup.from(this, source)
        tokenStore.save(pending)
        return pending
    }
}

/** Upper-cased and hyphen-normalised, so a pasted code with stray spacing still matches. */
internal fun String.normaliseInviteCode(): String {
    val alphanumeric = filter { it.isLetterOrDigit() }.uppercase()
    return alphanumeric.chunked(INVITE_CODE_GROUP).joinToString("-")
}

/**
 * `XXXX-XXXX-XXXX` — three groups of four, Crockford base32, as the homeserver mints them
 * (`pubky-homeserver .../signup_code.rs`).
 */
internal fun String.isInviteCodeShaped(): Boolean =
    length == INVITE_CODE_LENGTH &&
        split("-").let { groups ->
            groups.size == INVITE_CODE_GROUPS &&
                groups.all { group -> group.length == INVITE_CODE_GROUP && group.all(Char::isLetterOrDigit) }
        }

private const val INVITE_CODE_GROUP = 4
private const val INVITE_CODE_GROUPS = 3
private const val INVITE_CODE_LENGTH = INVITE_CODE_GROUP * INVITE_CODE_GROUPS + (INVITE_CODE_GROUPS - 1)
