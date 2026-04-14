package com.github.jvsena42.eco.data.pubky

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256Hex(bytes: ByteArray): String {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    val input = if (bytes.isEmpty()) ByteArray(1) else bytes
    input.usePinned { inPin ->
        digest.usePinned { outPin ->
            CC_SHA256(
                inPin.addressOf(0),
                bytes.size.convert(),
                outPin.addressOf(0).reinterpret<UByteVar>(),
            )
        }
    }
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xff
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0f])
    }
    return sb.toString()
}

private val HEX = "0123456789abcdef".toCharArray()
