package com.github.jvsena42.loopky.data.pubky

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Vectors cross-checked against the reference Crockford Base32 encoding of eight big-endian bytes
 * — the same thing `base32::encode(Alphabet::Crockford, &micros.to_be_bytes())` produces in
 * pubky-app-specs. `00321FCW75ZFY` is the example id from the `PubkyAppPost` doc comment; it has
 * to decode back to a real 2024 timestamp or the whole scheme is wrong.
 */
class PostIdsTest {

    @Test
    fun `encodes a known timestamp`() {
        assertEquals("00326QR0MQG00", PostIds.create(1_727_740_800_000_000L))
        assertEquals("0033S7HHWWW00", PostIds.create(1_755_500_000_000_000L))
    }

    @Test
    fun `is always thirteen characters`() {
        val samples = listOf(0L, 1L, 1_727_740_800_000_000L, Long.MAX_VALUE)
        for (micros in samples) {
            assertEquals(PostIds.ID_LENGTH, PostIds.create(micros).length, "micros=$micros")
        }
    }

    @Test
    fun `round-trips through a decode`() {
        val micros = 1_755_500_123_456_000L
        assertEquals(micros, decode(PostIds.create(micros)))
    }

    @Test
    fun `matches the spec's documented example`() {
        // The id in the PubkyAppPost doc comment, which decodes to 2024-08-28T12:36:42Z. It is
        // only here to pin the *encoding*: it predates the 2024-10-01 floor validate_id enforces,
        // so it is not an id we could mint today.
        assertEquals(1_724_848_602_185_471L, decode("00321FCW75ZFY"))
    }

    @Test
    fun `uses only Crockford symbols`() {
        val id = PostIds.create(1_755_500_000_000_000L)
        assertTrue(id.none { it in "ILOU" }, "Crockford excludes I, L, O and U: $id")
    }

    /** Straight MSB-first Crockford decode of the leading 64 bits — the inverse of the encoder. */
    private fun decode(id: String): Long {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        return id
            .map { alphabet.indexOf(it) }
            .joinToString("") { it.toString(radix = 2).padStart(BITS_PER_CHAR, '0') }
            .take(TIMESTAMP_BITS)
            .fold(0L) { acc, bit -> (acc shl 1) or (if (bit == '1') 1L else 0L) }
    }

    private companion object {
        const val BITS_PER_CHAR = 5
        const val TIMESTAMP_BITS = 64
    }
}
