package com.github.jvsena42.eco.data.repository.impl

import com.github.jvsena42.eco.data.repository.ImportRepository
import com.github.jvsena42.eco.domain.model.ColumnMapping
import com.github.jvsena42.eco.domain.model.ColumnRole
import com.github.jvsena42.eco.domain.model.ImportDraft
import com.github.jvsena42.eco.domain.model.ParseFlag
import com.github.jvsena42.eco.domain.model.ParsedRow
import com.github.jvsena42.eco.domain.model.Separator

class ImportRepositoryImpl : ImportRepository {

    private var draft: ImportDraft? = null

    override fun currentDraft(): ImportDraft? = draft

    override suspend fun parse(rawText: String): Result<ImportDraft> = runCatching {
        val text = rawText.replace("\r\n", "\n").replace("\r", "\n").trim()
        require(text.isNotEmpty()) { "Nothing to import." }
        require(text.length <= MAX_CHARS) { "Text is too long (max $MAX_CHARS characters)." }

        val separator = detectSeparator(text)
        val rawRows = splitRows(text, separator)
        require(rawRows.isNotEmpty()) { "Could not parse any cards." }

        val columnCount = rawRows.maxOf { it.size }
        val mapping = when {
            columnCount >= 3 -> ColumnMapping.DEFAULT_THREE_COL
            columnCount == 2 -> ColumnMapping.DEFAULT_TWO_COL
            else -> ColumnMapping(listOf(ColumnRole.Front))
        }

        val flags = mutableListOf<ParseFlag>()
        if (columnCount == 1) flags.add(ParseFlag.SingleColumnFallback)
        if (rawRows.size > MAX_CARDS) flags.add(ParseFlag.OverMaxSize)

        val rows = rawRows.take(MAX_CARDS).mapIndexed { index, fields ->
            if (fields.size != columnCount) flags.add(ParseFlag.MismatchedRowLength(index))
            ParsedRow(
                index = index,
                fields = fields,
                isValid = fields.any { it.isNotBlank() },
            )
        }

        // Collapse exact duplicates
        val seen = mutableSetOf<List<String>>()
        var duplicatesCollapsed = 0
        val deduped = rows.filter { row ->
            if (seen.add(row.fields)) true
            else { duplicatesCollapsed++; false }
        }

        ImportDraft(
            rawText = rawText,
            separator = separator,
            columnMapping = mapping,
            rows = deduped,
            duplicatesCollapsed = duplicatesCollapsed,
            flags = flags,
        ).also { draft = it }
    }

    override fun clear() {
        draft = null
    }

    // --- Separator detection (spec §6 rule order) ---

    private fun detectSeparator(text: String): Separator {
        val lines = text.split("\n").filter { it.isNotBlank() }
        if (lines.size < 2) return Separator.SingleColumn

        // 1. Markdown table
        if (lines.size >= 2 && lines[1].matches(Regex("^[\\s|:-]+$"))) {
            return Separator.MarkdownTable
        }

        // 2. Blank-line-separated pairs
        val chunks = text.split(Regex("\n\\s*\n")).filter { it.isNotBlank() }
        if (chunks.size >= 2 && chunks.all { it.lines().filter { l -> l.isNotBlank() }.size == 2 }) {
            return Separator.BlankLine
        }

        // 3-8. Delimiter detection by consistency
        val candidates = listOf(
            '\t' to Separator.Tab,
            ';' to Separator.Semicolon,
            '|' to Separator.Pipe,
            '—' to Separator.EmDash,
            '–' to Separator.EmDash,
            ':' to Separator.Colon,
            ',' to Separator.Comma,
        )

        for ((delim, sep) in candidates) {
            val delimStr = if (sep == Separator.EmDash) " $delim " else if (sep == Separator.Colon) "$delim " else delim.toString()
            val counts = lines.map { line -> line.split(delimStr).size - 1 }
            val consistentCount = counts.firstOrNull { it > 0 } ?: continue
            val consistent = counts.count { it == consistentCount }.toFloat() / counts.size
            if (consistent >= 0.8f && consistentCount > 0) return sep
        }

        return Separator.SingleColumn
    }

    private fun splitRows(text: String, separator: Separator): List<List<String>> {
        return when (separator) {
            is Separator.MarkdownTable -> parseMarkdownTable(text)
            is Separator.BlankLine -> parseBlankLinePairs(text)
            is Separator.SingleColumn -> text.split("\n")
                .filter { it.isNotBlank() }
                .map { listOf(it.trim()) }
            else -> {
                val delim = separatorToDelimString(separator)
                text.split("\n")
                    .filter { it.isNotBlank() }
                    .map { line -> line.split(delim).map { it.trim() } }
            }
        }
    }

    private fun separatorToDelimString(sep: Separator): String = when (sep) {
        Separator.Tab -> "\t"
        Separator.Semicolon -> ";"
        Separator.Pipe -> "|"
        Separator.EmDash -> " — "
        Separator.Colon -> ": "
        Separator.Comma -> ","
        is Separator.Custom -> sep.char.toString()
        else -> "\t"
    }

    private fun parseMarkdownTable(text: String): List<List<String>> {
        val lines = text.split("\n").filter { it.isNotBlank() }
        return lines
            .filter { !it.matches(Regex("^[\\s|:-]+$")) }
            .map { line ->
                line.trim().removeSurrounding("|").split("|").map { it.trim() }
            }
            .drop(1) // drop header row
    }

    private fun parseBlankLinePairs(text: String): List<List<String>> {
        return text.split(Regex("\n\\s*\n"))
            .filter { it.isNotBlank() }
            .map { chunk ->
                chunk.lines().filter { it.isNotBlank() }.map { it.trim() }
            }
    }

    companion object {
        private const val MAX_CHARS = 10_000
        private const val MAX_CARDS = 500
    }
}
