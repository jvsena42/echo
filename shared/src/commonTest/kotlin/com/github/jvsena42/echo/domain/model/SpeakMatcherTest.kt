package com.github.jvsena42.echo.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeakMatcherTest {

    @Test
    fun exactMatch() {
        assertTrue(SpeakMatcher.match("el zorro", "el zorro").correct)
    }

    @Test
    fun caseInsensitive() {
        assertTrue(SpeakMatcher.match("EL Zorro", "el zorro").correct)
    }

    @Test
    fun ignoresPunctuationAndExtraWhitespace() {
        assertTrue(SpeakMatcher.match("  El, Zorro!  ", "el zorro").correct)
    }

    @Test
    fun ignoresDiacritics() {
        assertTrue(SpeakMatcher.match("el zorro", "él zörró").correct)
        assertTrue(SpeakMatcher.match("buenos dias", "buenos días").correct)
    }

    @Test
    fun mismatchReportsHeardAndExpected() {
        val result = SpeakMatcher.match("el zoro", "el zorro")
        assertFalse(result.correct)
        assertEquals("el zoro", result.heard)
        assertEquals("el zorro", result.expected)
    }

    @Test
    fun emptyExpectedNeverMatches() {
        assertFalse(SpeakMatcher.match("", "").correct)
        assertFalse(SpeakMatcher.match("anything", "").correct)
    }
}
