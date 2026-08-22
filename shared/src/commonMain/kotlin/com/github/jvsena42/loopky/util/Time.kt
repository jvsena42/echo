package com.github.jvsena42.loopky.util

/** Returns the current time in milliseconds since the Unix epoch. */
internal expect fun epochMillis(): Long

/**
 * Which local day [millis] falls on, as a day number that only ever has to be compared for
 * equality — days since the Unix epoch in the device's own time zone.
 *
 * Local, not UTC: "new cards today" is a claim about the user's day, and a UTC boundary would roll
 * their counter over mid-evening or mid-morning depending on where they are. Scheduling is
 * deliberately unaffected — SRS intervals stay plain `now + n × 24h`, since a card due "in 3 days"
 * means 72 hours, not "at midnight three sleeps from now".
 */
internal expect fun localDayIndex(millis: Long): Int
