package com.github.jvsena42.echo.domain.model

/**
 * Field-level validation failures. Like [ErrorReason] these are carried instead of message
 * text so `commonMain` holds no user-facing strings and each platform localises its own.
 */
enum class FormError {
    TitleRequired,
    TitleTooLong,
    DescriptionTooLong,
}
