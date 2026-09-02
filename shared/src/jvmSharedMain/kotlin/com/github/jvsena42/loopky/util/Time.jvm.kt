package com.github.jvsena42.loopky.util

import java.time.Instant
import java.time.ZoneId

internal actual fun epochMillis(): Long = System.currentTimeMillis()

internal actual fun localDayIndex(millis: Long): Int =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay().toInt()
