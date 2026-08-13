package com.github.jvsena42.echo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.jvsena42.echo.R
import com.github.jvsena42.echo.domain.model.ErrorReason

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
        ErrorReason.Unknown -> R.string.error_generic_message
    },
)
