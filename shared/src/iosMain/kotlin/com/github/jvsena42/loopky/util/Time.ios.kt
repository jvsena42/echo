package com.github.jvsena42.loopky.util

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitEra
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

private const val MILLIS_PER_SECOND = 1000

internal actual fun epochMillis(): Long =
    (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()

/**
 * Day ordinality within the era, from the current calendar — the Foundation equivalent of
 * `toEpochDay()`. The absolute value is irrelevant; only equality between two of them is used.
 */
internal actual fun localDayIndex(millis: Long): Int {
    val date = NSDate.dateWithTimeIntervalSince1970(millis.toDouble() / MILLIS_PER_SECOND)
    return NSCalendar.currentCalendar.ordinalityOfUnit(
        unit = NSCalendarUnitDay,
        inUnit = NSCalendarUnitEra,
        forDate = date,
    ).toInt()
}
