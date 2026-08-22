package com.github.jvsena42.loopky.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.github.jvsena42.loopky.R

/**
 * Coarse "in 4h" / "in 2d" phrasing — an exact timestamp would be noise wherever this is used.
 *
 * Shared by Home's caught-up card and the study session's congrats screen, which say the same
 * thing about the same clock and so must not round it two different ways.
 */
@Composable
fun relativeFromNow(millis: Long): String {
    val delta = (millis - System.currentTimeMillis()).coerceAtLeast(0L)
    val minutes = delta / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        days > 0 -> pluralStringResource(R.plurals.duration_days, days.toInt(), days.toInt())
        hours > 0 -> pluralStringResource(R.plurals.duration_hours, hours.toInt(), hours.toInt())
        else -> pluralStringResource(R.plurals.duration_minutes, minutes.toInt(), minutes.toInt())
    }
}
