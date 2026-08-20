package com.github.jvsena42.loopky.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.FormError
import com.github.jvsena42.loopky.presentation.importflow.BulkImportError
import com.github.jvsena42.loopky.presentation.signup.SignupError
import com.github.jvsena42.loopky.ui.importflow.MAX_IMPORT_FILE_BYTES

/**
 * Maps a shared [ErrorReason] to user-facing copy. The ViewModels deliberately carry no
 * message text — the FFI's diagnostic string was previously rendered verbatim.
 */
@Composable
fun errorTitle(reason: ErrorReason): String = stringResource(
    when (reason) {
        ErrorReason.Offline -> R.string.error_offline_title
        ErrorReason.SessionExpired -> R.string.error_session_expired_title
        ErrorReason.NotFound -> R.string.error_not_found_title
        ErrorReason.NoHomeserverAccount -> R.string.error_no_account_title
        ErrorReason.NotSignedIn -> R.string.error_not_signed_in_title
        ErrorReason.RingNotInstalled -> R.string.error_ring_not_installed_title
        ErrorReason.AuthFailed -> R.string.error_auth_failed_title
        ErrorReason.AuthRelayUnreachable -> R.string.error_auth_relay_title
        ErrorReason.Unknown -> R.string.error_generic_title
    },
)

@Composable
fun errorMessage(reason: ErrorReason): String = stringResource(
    when (reason) {
        ErrorReason.Offline -> R.string.error_offline_message
        ErrorReason.SessionExpired -> R.string.error_session_expired_message
        ErrorReason.NotFound -> R.string.error_not_found_message
        ErrorReason.NoHomeserverAccount -> R.string.error_no_account_message
        ErrorReason.NotSignedIn -> R.string.error_not_signed_in_message
        ErrorReason.RingNotInstalled -> R.string.error_ring_not_installed_message
        ErrorReason.AuthFailed -> R.string.error_auth_failed_message
        ErrorReason.AuthRelayUnreachable -> R.string.error_auth_relay_message
        ErrorReason.Unknown -> R.string.error_generic_message
    },
)

/**
 * Copy for a file import that failed.
 *
 * Separate from [ErrorReason], which is network/session/auth throughout — these are local-file
 * failures, and folding them in would make eight unrelated screens' exhaustive `when`s handle
 * "you picked a photo". Kept distinct from each other because a single message string is how the
 * screen came to answer "wrong file type" with a parser complaint.
 */
@Composable
fun bulkImportErrorTitle(reason: BulkImportError): String = stringResource(
    when (reason) {
        BulkImportError.Unreadable -> R.string.bulk_error_unreadable_title
        BulkImportError.TooLarge -> R.string.bulk_error_too_large_title
        BulkImportError.NotText -> R.string.bulk_error_not_text_title
        BulkImportError.UnsupportedApkg -> R.string.bulk_error_unsupported_apkg_title
        BulkImportError.NoCardsFound -> R.string.bulk_error_no_cards_title
        BulkImportError.Unknown -> R.string.bulk_error_unknown_title
    },
)

@Composable
fun bulkImportErrorMessage(reason: BulkImportError): String = when (reason) {
    // Formatted from the reader's own ceiling so the number in the copy cannot drift from it.
    BulkImportError.TooLarge -> stringResource(
        R.string.bulk_error_too_large_message,
        MAX_IMPORT_FILE_BYTES / BYTES_PER_MB,
    )
    BulkImportError.Unreadable -> stringResource(R.string.bulk_error_unreadable_message)
    BulkImportError.NotText -> stringResource(R.string.bulk_error_not_text_message)
    BulkImportError.UnsupportedApkg -> stringResource(R.string.bulk_error_unsupported_apkg_message)
    BulkImportError.NoCardsFound -> stringResource(R.string.bulk_error_no_cards_message)
    BulkImportError.Unknown -> stringResource(R.string.bulk_error_unknown_message)
}

private const val BYTES_PER_MB = 1024L * 1024

/**
 * Copy for a step of the signup flow.
 *
 * Separate from [ErrorReason] for the same reason [bulkImportErrorTitle] is: these only ever occur
 * on the five screens that exist for this flow, and folding them in would make the eight unrelated
 * screens with an exhaustive `when` over [ErrorReason] answer for "your invite code was already
 * used".
 */
@Composable
fun signupErrorTitle(error: SignupError): String = stringResource(
    when (error) {
        SignupError.Geoblocked -> R.string.signup_error_geoblocked_title
        SignupError.PhoneBlocked -> R.string.signup_error_phone_blocked_title
        SignupError.RateLimited -> R.string.signup_error_rate_limited_title
        SignupError.RateLimitedWeekly -> R.string.signup_error_rate_limited_weekly_title
        SignupError.RateLimitedYearly -> R.string.signup_error_rate_limited_yearly_title
        SignupError.CodeIncorrect -> R.string.signup_error_code_incorrect_title
        SignupError.InvoiceExpired -> R.string.signup_error_invoice_expired_title
        SignupError.VerificationLost -> R.string.signup_error_verification_lost_title
        SignupError.RingFailed -> R.string.signup_error_ring_failed_title
        SignupError.RingNotInstalled -> R.string.signup_error_ring_missing_title
        SignupError.Unavailable -> R.string.signup_error_unavailable_title
    },
)

@Composable
fun signupErrorMessage(error: SignupError): String = stringResource(
    when (error) {
        SignupError.Geoblocked -> R.string.signup_error_geoblocked_message
        SignupError.PhoneBlocked -> R.string.signup_error_phone_blocked_message
        SignupError.RateLimited -> R.string.signup_error_rate_limited_message
        SignupError.RateLimitedWeekly -> R.string.signup_error_rate_limited_weekly_message
        SignupError.RateLimitedYearly -> R.string.signup_error_rate_limited_yearly_message
        SignupError.CodeIncorrect -> R.string.signup_error_code_incorrect_message
        SignupError.InvoiceExpired -> R.string.signup_error_invoice_expired_message
        SignupError.VerificationLost -> R.string.signup_error_verification_lost_message
        SignupError.RingFailed -> R.string.signup_error_ring_failed_message
        SignupError.RingNotInstalled -> R.string.signup_error_ring_missing_message
        SignupError.Unavailable -> R.string.signup_error_unavailable_message
    },
)

/** Field-level validation copy for [FormError]. */
@Composable
fun formErrorMessage(error: FormError): String = stringResource(
    when (error) {
        FormError.TitleRequired -> R.string.form_title_required
        FormError.TitleTooLong -> R.string.form_title_too_long
        FormError.DescriptionTooLong -> R.string.form_description_too_long
    },
)
