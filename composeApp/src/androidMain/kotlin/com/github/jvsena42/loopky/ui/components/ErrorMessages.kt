package com.github.jvsena42.loopky.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.FormError
import com.github.jvsena42.loopky.presentation.importflow.BulkImportError

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
fun bulkImportErrorMessage(reason: BulkImportError): String = stringResource(
    when (reason) {
        BulkImportError.Unreadable -> R.string.bulk_error_unreadable_message
        BulkImportError.TooLarge -> R.string.bulk_error_too_large_message
        BulkImportError.NotText -> R.string.bulk_error_not_text_message
        BulkImportError.UnsupportedApkg -> R.string.bulk_error_unsupported_apkg_message
        BulkImportError.NoCardsFound -> R.string.bulk_error_no_cards_message
        BulkImportError.Unknown -> R.string.bulk_error_unknown_message
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
