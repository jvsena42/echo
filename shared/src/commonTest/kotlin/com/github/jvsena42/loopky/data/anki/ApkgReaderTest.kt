package com.github.jvsena42.loopky.data.anki

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApkgReaderTest {

    private val sep = ANKI_FIELD_SEPARATOR

    @Test
    fun aTwoFieldNoteBecomesATabSeparatedLine() {
        assertEquals("hola\thello", ankiNoteToLine("hola${sep}hello"))
    }

    @Test
    fun extraFieldsAreDroppedLikeExtraPasteColumns() {
        // A Loopky card has exactly two sides (spec §8); the Note→Card split is #46, not here.
        assertEquals("hola\thello", ankiNoteToLine("hola${sep}hello${sep}spanish${sep}audio.mp3"))
    }

    @Test
    fun aNoteWithNoBackIsSkipped() {
        assertNull(ankiNoteToLine("orphan"))
        assertNull(ankiNoteToLine("orphan$sep"))
        assertNull(ankiNoteToLine(""))
    }

    @Test
    fun tabsAndNewlinesInsideAFieldDoNotBreakTheLine() {
        // Otherwise one note would parse as several malformed rows.
        val line = ankiNoteToLine("a\tb${sep}c\nd")
        assertEquals("a b\tc d", line)
    }

    @Test
    fun ankiHtmlIsStrippedToReadableText() {
        assertEquals("hola\thello there", ankiNoteToLine("<b>hola</b>${sep}hello<br>there"))
        assertEquals("a & b", "a &amp; b".stripAnkiHtml())
        assertEquals("x y", "<div>x</div><div>y</div>".stripAnkiHtml())
        assertEquals("plain", "&nbsp;plain&nbsp;".stripAnkiHtml())
    }

    @Test
    fun aNoteWhoseFieldsAreOnlyMarkupIsSkipped() {
        // Stripping can empty a field, and an empty side must not become a blank card.
        assertNull(ankiNoteToLine("<br>${sep}hello"))
    }
}
