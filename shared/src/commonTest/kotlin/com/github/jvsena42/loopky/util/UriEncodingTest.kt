package com.github.jvsena42.loopky.util

import kotlin.test.Test
import kotlin.test.assertEquals

class UriEncodingTest {

    @Test
    fun leavesUnreservedCharactersAlone() {
        assertEquals("abcXYZ019-_.~", encodeUriComponent("abcXYZ019-_.~"))
    }

    @Test
    fun encodesPubkySubjectUri() {
        assertEquals(
            expected = "pubky%3A%2F%2Fabc%2Fpub%2Floopky%2Fdecks%2Fd1%2Fmanifest.json",
            actual = encodeUriComponent("pubky://abc/pub/loopky/decks/d1/manifest.json"),
        )
    }

    @Test
    fun encodesReservedQueryCharacters() {
        assertEquals("a%26b%3Dc%3Fd%23e", encodeUriComponent("a&b=c?d#e"))
    }

    @Test
    fun encodesNonAsciiAsUtf8Bytes() {
        // 'é' is two UTF-8 bytes, and `isLetterOrDigit()` is true for it — it must still escape.
        assertEquals("caf%C3%A9", encodeUriComponent("café"))
        assertEquals("%F0%9F%94%A5", encodeUriComponent("🔥"))
    }
}
