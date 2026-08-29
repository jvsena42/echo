package com.github.jvsena42.loopky.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * `ByteArray` ↔ `NSData` conversions, in one place so the copy semantics are stated once.
 *
 * Both directions copy. `NSData.create(bytes:length:)` copies the buffer it is given, so the
 * `memScoped` allocation is safe to release; and `memcpy` out of `NSData.bytes` copies before the
 * `NSData` can be released. Anything sharing the pointer instead would be a lifetime bug that only
 * shows up under memory pressure.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = this@toNSData.size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}
