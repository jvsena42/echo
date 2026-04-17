package com.github.jvsena42.eco.data.repository.impl

import com.github.jvsena42.eco.domain.model.ColumnRole
import com.github.jvsena42.eco.domain.model.ParseFlag
import com.github.jvsena42.eco.domain.model.Separator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImportRepositoryImplTest {

    private fun repo() = ImportRepositoryImpl()

    // ── Separator detection ──────────────────────────────────────────────

    @Test
    fun emDashSeparator() = runBlocking {
        val draft = repo().parse("hola — hello\ngracias — thank you").getOrThrow()
        assertIs<Separator.EmDash>(draft.separator)
        assertEquals(2, draft.rows.size)
    }

    @Test
    fun colonSeparator() = runBlocking {
        val draft = repo().parse("mitosis: cell division\nosmosis: water moves across a membrane").getOrThrow()
        assertIs<Separator.Colon>(draft.separator)
        assertEquals(2, draft.rows.size)
    }

    @Test
    fun tabSeparator() = runBlocking {
        val draft = repo().parse("hola\thello\ngracias\tthank you").getOrThrow()
        assertIs<Separator.Tab>(draft.separator)
        assertEquals(2, draft.rows.size)
    }

    @Test
    fun commaSeparator() = runBlocking {
        val draft = repo().parse("hola,hello\ngracias,thank you").getOrThrow()
        assertIs<Separator.Comma>(draft.separator)
        assertEquals(2, draft.rows.size)
    }

    @Test
    fun pipeSeparator() = runBlocking {
        val draft = repo().parse("hola|hello\ngracias|thank you").getOrThrow()
        assertIs<Separator.Pipe>(draft.separator)
        assertEquals(2, draft.rows.size)
    }

    @Test
    fun semicolonSeparator() = runBlocking {
        val draft = repo().parse("hola;hello\ngracias;thank you").getOrThrow()
        assertIs<Separator.Semicolon>(draft.separator)
        assertEquals(2, draft.rows.size)
    }

    @Test
    fun blankLinePairs() = runBlocking {
        val text = "hola\nhello\n\ngracias\nthank you"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.BlankLine>(draft.separator)
        assertEquals(2, draft.rows.size)
        assertEquals(listOf("hola", "hello"), draft.rows[0].fields)
        assertEquals(listOf("gracias", "thank you"), draft.rows[1].fields)
    }

    @Test
    fun markdownTable() = runBlocking {
        val text = "| front | back |\n| --- | --- |\n| hola | hello |\n| gracias | thank you |"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.MarkdownTable>(draft.separator)
        assertEquals(2, draft.rows.size)
        assertEquals("hola", draft.rows[0].fields[0])
        assertEquals("hello", draft.rows[0].fields[1])
    }

    @Test
    fun singleColumn() = runBlocking {
        val draft = repo().parse("hola\nhello").getOrThrow()
        assertIs<Separator.SingleColumn>(draft.separator)
        assertEquals(2, draft.rows.size)
        assertEquals(1, draft.rows[0].fields.size)
    }

    // ── Front/back mapping ───────────────────────────────────────────────

    @Test
    fun emDashFrontBackMapping() = runBlocking {
        val draft = repo().parse("hola — hello\ngracias — thank you\npor favor — please").getOrThrow()
        assertEquals("hola", draft.rows[0].fields[0])
        assertEquals("hello", draft.rows[0].fields[1])
        assertEquals("gracias", draft.rows[1].fields[0])
        assertEquals("thank you", draft.rows[1].fields[1])
        assertEquals("por favor", draft.rows[2].fields[0])
        assertEquals("please", draft.rows[2].fields[1])
    }

    @Test
    fun colonFrontBackMapping() = runBlocking {
        val draft = repo().parse("mitosis: cell division\nosmosis: water moves").getOrThrow()
        assertEquals("mitosis", draft.rows[0].fields[0])
        assertEquals("cell division", draft.rows[0].fields[1])
    }

    @Test
    fun twoColumnMappingIsCorrect() = runBlocking {
        val draft = repo().parse("hola — hello\ngracias — thank you").getOrThrow()
        assertEquals(2, draft.columnMapping.assignments.size)
        assertEquals(ColumnRole.Front, draft.columnMapping.assignments[0])
        assertEquals(ColumnRole.Back, draft.columnMapping.assignments[1])
    }

    // ── Delimiter in content (split limit) ───────────────────────────────

    @Test
    fun colonInBackContent() = runBlocking {
        val text = "time: it's 3:00 PM\ndate: December 25: Christmas"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.Colon>(draft.separator)
        assertEquals(2, draft.rows.size)
        assertEquals("time", draft.rows[0].fields[0])
        assertEquals("it's 3:00 PM", draft.rows[0].fields[1])
        assertEquals("date", draft.rows[1].fields[0])
        assertEquals("December 25: Christmas", draft.rows[1].fields[1])
    }

    @Test
    fun emDashInBackContent() = runBlocking {
        val text = "test — answer — with extra dash\nother — simple"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.EmDash>(draft.separator)
        assertEquals(2, draft.rows.size)
        assertEquals("test", draft.rows[0].fields[0])
        assertEquals("answer — with extra dash", draft.rows[0].fields[1])
    }

    @Test
    fun pipeInBackContent() = runBlocking {
        val text = "true|yes | correct\nfalse|no"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.Pipe>(draft.separator)
        assertEquals("true", draft.rows[0].fields[0])
        assertEquals("yes | correct", draft.rows[0].fields[1])
    }

    @Test
    fun commaInBackContent() = runBlocking {
        val text = "greeting,hello, world\nfarewell,goodbye, everyone"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.Comma>(draft.separator)
        assertEquals("greeting", draft.rows[0].fields[0])
        assertEquals("hello, world", draft.rows[0].fields[1])
    }

    // ── En-dash vs em-dash ───────────────────────────────────────────────

    @Test
    fun enDashSeparator() = runBlocking {
        val text = "hola \u2013 hello\ngracias \u2013 thank you"  // \u2013 = en-dash
        val draft = repo().parse(text).getOrThrow()
        assertEquals(2, draft.rows.size)
        assertEquals("hola", draft.rows[0].fields[0])
        assertEquals("hello", draft.rows[0].fields[1])
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Test
    fun deduplication() = runBlocking {
        val text = "hola — hello\nhola — hello\ngracias — thank you"
        val draft = repo().parse(text).getOrThrow()
        assertEquals(2, draft.rows.size)
        assertEquals(1, draft.duplicatesCollapsed)
    }

    @Test
    fun emptyTextFails() = runBlocking {
        val result = repo().parse("")
        assertTrue(result.isFailure)
    }

    @Test
    fun whitespaceOnlyFails() = runBlocking {
        val result = repo().parse("   \n  \n  ")
        assertTrue(result.isFailure)
    }

    @Test
    fun windowsLineEndings() = runBlocking {
        val text = "hola — hello\r\ngracias — thank you\r\n"
        val draft = repo().parse(text).getOrThrow()
        assertEquals(2, draft.rows.size)
        assertEquals("hola", draft.rows[0].fields[0])
        assertEquals("hello", draft.rows[0].fields[1])
    }

    // ── False-positive separator detection ─────────────────────────────
    //
    // Characters that appear as natural punctuation in prose should NOT
    // be treated as field separators.  A comma in "Yes, I agree" is
    // grammar, not a front/back delimiter.

    @Test
    fun commaInProseIsNotSeparator(): Unit = runBlocking {
        // Commas here are natural punctuation, not field separators
        val text = "Yes, I agree with that\nNo, I disagree completely\nWell, maybe so"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.SingleColumn>(draft.separator)
        Unit
    }

    @Test
    fun semicolonInProseIsNotSeparator(): Unit = runBlocking {
        // Semicolons joining independent clauses
        val text = "She ran quickly; he walked slowly\nThey ate dinner; we slept early"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.SingleColumn>(draft.separator)
        Unit
    }

    @Test
    fun emDashParentheticalIsNotSeparator(): Unit = runBlocking {
        // Em-dashes used as parenthetical inserts (two per line)
        val text = "The dog \u2014 a big one \u2014 barked loudly\nThe cat \u2014 so tiny \u2014 meowed softly"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.SingleColumn>(draft.separator)
        Unit
    }

    @Test
    fun commaInNumbersNotSeparator(): Unit = runBlocking {
        // Commas inside numbers, no real front/back separation
        val text = "Total 1,000 units shipped\nCount 2,500 items remaining\nValue 10,250 dollars"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.SingleColumn>(draft.separator)
        Unit
    }

    @Test
    fun commaWithShortFrontIsRealSeparator() = runBlocking {
        // Short front + long back = genuine flashcard-style pairs
        val text = "hola,hello\ngracias,thank you\nadios,goodbye"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.Comma>(draft.separator)
        assertEquals("hola", draft.rows[0].fields[0])
        assertEquals("hello", draft.rows[0].fields[1])
    }

    @Test
    fun colonWithShortFrontIsRealSeparator(): Unit = runBlocking {
        // Legitimate key: value pairs
        val text = "mitosis: cell division\nosmosis: diffusion of water\nphotosynthesis: converting light to energy"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.Colon>(draft.separator)
        Unit
    }

    @Test
    fun semicolonWithShortFrontIsRealSeparator(): Unit = runBlocking {
        // Short terms separated by semicolons
        val text = "hola;hello\ngracias;thank you\nadios;goodbye"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.Semicolon>(draft.separator)
        Unit
    }

    @Test
    fun pipeInShellCommandsNotSeparator(): Unit = runBlocking {
        // Shell pipes — both sides are commands, not front/back pairs
        val text = "ls -la | grep .txt | head\ncat file.log | sort | uniq -c"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.SingleColumn>(draft.separator)
        Unit
    }

    @Test
    fun higherPriorityDelimiterWinsOverIncidentalComma() = runBlocking {
        // Colon is the real separator; commas are incidental in content
        val text = "population: 1,000,000 people\narea: 2,500 sq km"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.Colon>(draft.separator)
        assertEquals("population", draft.rows[0].fields[0])
        assertEquals("1,000,000 people", draft.rows[0].fields[1])
    }

    @Test
    fun draftIsPersisted() = runBlocking {
        val r = repo()
        assertEquals(null, r.currentDraft())
        r.parse("hola — hello\ngracias — thank you")
        assertTrue(r.currentDraft() != null)
        r.clear()
        assertEquals(null, r.currentDraft())
    }
}
