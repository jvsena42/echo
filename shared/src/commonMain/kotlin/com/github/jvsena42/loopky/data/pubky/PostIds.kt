package com.github.jvsena42.loopky.data.pubky

/**
 * pubky-app-specs `TimestampId`: the 8 big-endian bytes of a microsecond Unix timestamp, encoded
 * with the Crockford Base32 alphabet — always 13 characters.
 *
 * Hand-rolled rather than taken from the FFI, which exposes `create_tag_id` and nothing
 * post-shaped. That is safe here in a way it would not be for a tag id: a tag id is half a blake3
 * hash and has to match the indexer's own derivation byte for byte, whereas a post id only has to
 * decode back to a plausible timestamp. Nexus validates exactly that (`TimestampId::validate_id`):
 * 13 chars, 8 bytes after decoding, and a time between 2024-10-01 and two hours from now.
 */
internal object PostIds {
    /** Crockford Base32 — no `I`, `L`, `O` or `U`, so an id cannot be misread aloud. */
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private const val ID_BYTES = 8
    private const val BITS_PER_CHAR = 5
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFFL
    private const val CHAR_MASK = 0x1F

    /** Length of the encoding of [ID_BYTES] bytes: ceil(64 / 5). */
    const val ID_LENGTH = 13

    /**
     * Mint an id for a post created at [epochMicros].
     *
     * The final character carries one real bit and four bits of zero padding, which is what the
     * reference `base32` crate emits too — decoding still yields exactly eight bytes.
     */
    fun create(epochMicros: Long): String {
        var bitBuffer = 0L
        var bitCount = 0
        return buildString(ID_LENGTH) {
            for (i in ID_BYTES - 1 downTo 0) {
                val byte = (epochMicros ushr (i * BYTE_BITS)) and BYTE_MASK
                bitBuffer = (bitBuffer shl BYTE_BITS) or byte
                bitCount += BYTE_BITS
                while (bitCount >= BITS_PER_CHAR) {
                    bitCount -= BITS_PER_CHAR
                    append(CROCKFORD[((bitBuffer ushr bitCount).toInt()) and CHAR_MASK])
                }
            }
            if (bitCount > 0) {
                append(CROCKFORD[((bitBuffer shl (BITS_PER_CHAR - bitCount)).toInt()) and CHAR_MASK])
            }
        }
    }
}
