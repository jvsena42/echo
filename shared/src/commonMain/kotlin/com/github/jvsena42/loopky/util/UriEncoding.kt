package com.github.jvsena42.loopky.util

private const val HEX_RADIX = 16
private const val BYTE_MASK = 0xFF
private const val LOW_NIBBLE_MASK = 0x0F
private const val NIBBLE_BITS = 4

/** Characters RFC 3986 leaves unreserved — everything else is percent-encoded. */
private const val UNRESERVED = "-_.~"

/**
 * Percent-encode [value] for use as a single URI component (a query-parameter value or one path
 * segment). Every byte outside the RFC 3986 unreserved set is escaped, so `pubky://` subject URIs
 * and emoji tag labels survive being interpolated into a Nexus query string.
 *
 * Encodes the string's UTF-8 bytes rather than its chars: iterating chars splits a surrogate pair
 * (any emoji) into two halves that each encode to the replacement character.
 */
internal fun encodeUriComponent(value: String): String = buildString {
    for (b in value.encodeToByteArray()) {
        val byte = b.toInt() and BYTE_MASK
        val ch = byte.toChar()
        if (ch.isUnreserved()) {
            append(ch)
        } else {
            append('%')
            append((byte shr NIBBLE_BITS).toString(HEX_RADIX).uppercase())
            append((byte and LOW_NIBBLE_MASK).toString(HEX_RADIX).uppercase())
        }
    }
}

private fun Char.isUnreserved(): Boolean =
    isLetterOrDigit() && code < ASCII_LIMIT || this in UNRESERVED

/** Non-ASCII letters and digits are `isLetterOrDigit()` too, and must still be escaped. */
private const val ASCII_LIMIT = 0x80
