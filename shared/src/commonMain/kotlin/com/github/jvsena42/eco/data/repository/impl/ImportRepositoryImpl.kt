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
    //
    // After checking structural formats (markdown table, blank-line pairs),
    // we try each single-character / multi-character delimiter with three
    // false-positive guards:
    //
    //  A) Single-char delimiters (`;`, `|`, `,`): skip a line if the char
    //     is immediately followed by a space (grammatical punctuation) or
    //     sandwiched between digits (thousands separator).
    //  B) Multi-char delimiters (`" — "`): reject if most lines contain
    //     two or more occurrences (parenthetical inserts).
    //  C) Balance check: reject if the median front side exceeds 50% of
    //     the line length (both halves are full sentences → prose).
    //
    // Tab is exempt from all guards — it almost never appears as prose
    // punctuation.

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

        // 3-8. Delimiter detection
        val candidates = listOf(
            "\t" to Separator.Tab,
            ";" to Separator.Semicolon,
            "|" to Separator.Pipe,
            " \u2014 " to Separator.EmDash,   // em-dash with spaces
            " \u2013 " to Separator.EmDash,   // en-dash with spaces
            ": " to Separator.Colon,
            "," to Separator.Comma,
        )

        for ((delimStr, sep) in candidates) {
            // Tab is unambiguous — simple presence check
            if (sep == Separator.Tab) {
                val count = lines.count { it.contains(delimStr) }
                if (count.toFloat() / lines.size >= 0.8f) return sep
                continue
            }

            // (A) For single-char delimiters, only count lines where the
            //     delimiter acts as a field separator, not punctuation.
            val qualifying = if (delimStr.length == 1) {
                lines.filter { line -> isSeparatorUse(line, delimStr[0]) }
            } else {
                lines.filter { it.contains(delimStr) }
            }

            if (qualifying.size.toFloat() / lines.size < 0.8f) continue

            // (B) Multi-char: reject if most lines have 2+ occurrences
            //     (parenthetical inserts, e.g. "The dog — big — barked")
            if (delimStr.length > 1) {
                val multiHit = qualifying.count { line ->
                    val first = line.indexOf(delimStr)
                    first >= 0 && line.indexOf(delimStr, first + delimStr.length) >= 0
                }
                if (multiHit.toFloat() / qualifying.size > 0.5f) continue
            }

            // (C) Balance: reject if the median front side is too long.
            //     Flashcard fronts are short terms (typically ≤ 30 chars).
            //     If the median front exceeds both an absolute length AND a
            //     ratio threshold, the delimiter is splitting prose.
            val frontLengths = qualifying.map { line ->
                val idx = line.indexOf(delimStr)
                if (idx <= 0) 0 else idx
            }
            val sortedLens = frontLengths.sorted()
            val medianLen = sortedLens[sortedLens.size / 2]
            if (medianLen > MAX_FRONT_CHARS) {
                // Absolute length exceeded — check ratio as confirmation
                val medianRatio = qualifying.map { line ->
                    val idx = line.indexOf(delimStr)
                    if (idx <= 0) 0f else idx.toFloat() / line.length
                }.sorted().let { it[it.size / 2] }
                if (medianRatio > MAX_FRONT_RATIO) continue
            }

            return sep
        }

        return Separator.SingleColumn
    }

    /** Returns true when [delim] at its first position in [line] looks like
     *  a field separator rather than natural punctuation.  Rejects:
     *  - delimiter immediately followed by a space (`"Yes, I agree"`)
     *  - delimiter sandwiched between digits (`"1,000"`) */
    private fun isSeparatorUse(line: String, delim: Char): Boolean {
        val idx = line.indexOf(delim)
        if (idx < 0) return false
        val after = idx + 1
        // Followed by space → grammatical punctuation
        if (after < line.length && line[after] == ' ') return false
        // Between digits → thousands / decimal separator
        if (idx > 0 && line[idx - 1].isDigit() && after < line.length && line[after].isDigit()) return false
        return true
    }

    // Split each line on the *first* occurrence of the delimiter only.
    // This ensures that delimiters appearing inside the back-side content
    // are preserved rather than creating spurious extra columns.

    private fun splitRows(text: String, separator: Separator): List<List<String>> {
        return when (separator) {
            is Separator.MarkdownTable -> parseMarkdownTable(text)
            is Separator.BlankLine -> parseBlankLinePairs(text)
            is Separator.SingleColumn -> text.split("\n")
                .filter { it.isNotBlank() }
                .map { listOf(it.trim()) }
            else -> {
                val delim = separatorToDelimString(separator, text)
                text.split("\n")
                    .filter { it.isNotBlank() }
                    .map { line -> splitFirst(line, delim) }
            }
        }
    }

    /** Split [line] on the first occurrence of [delim], returning [front, back].
     *  If the delimiter is not found, returns the whole line as a single-element list. */
    private fun splitFirst(line: String, delim: String): List<String> {
        val idx = line.indexOf(delim)
        if (idx < 0) return listOf(line.trim())
        val front = line.substring(0, idx).trim()
        val back = line.substring(idx + delim.length).trim()
        return listOf(front, back)
    }

    /** Return the delimiter string to split on.  For em-dash we pick whichever
     *  variant (em `—` or en `–`) actually appears in the text. */
    private fun separatorToDelimString(sep: Separator, text: String = ""): String = when (sep) {
        Separator.Tab -> "\t"
        Separator.Semicolon -> ";"
        Separator.Pipe -> "|"
        Separator.EmDash -> {
            // Prefer the variant present in the text
            when {
                text.contains(" \u2014 ") -> " \u2014 "  // em-dash
                text.contains(" \u2013 ") -> " \u2013 "  // en-dash
                else -> " \u2014 "
            }
        }
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

        /** Flashcard fronts rarely exceed 30 characters. */
        private const val MAX_FRONT_CHARS = 30

        /** If the front is both > MAX_FRONT_CHARS and > 50% of the line,
         *  the delimiter is splitting prose, not flashcard pairs. */
        private const val MAX_FRONT_RATIO = 0.50f
    }
}
