package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.domain.model.BACK_FIELD
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.FRONT_FIELD
import com.github.jvsena42.loopky.domain.model.ImportDraft
import com.github.jvsena42.loopky.domain.model.ParsedRow
import com.github.jvsena42.loopky.domain.model.Separator
import com.github.jvsena42.loopky.domain.model.TriageDecision

@Suppress("TooManyFunctions")
class ImportRepositoryImpl : ImportRepository {

    private var draft: ImportDraft? = null
    private val triageDecisions = mutableMapOf<Int, TriageDecision>()
    private val rowEdits = mutableMapOf<Int, Pair<String, String>>()
    private val rowFrontImages = mutableMapOf<Int, DraftCardImage>()
    private val rowBackImages = mutableMapOf<Int, DraftCardImage>()

    override fun currentDraft(): ImportDraft? = draft

    override fun decisions(): Map<Int, TriageDecision> = triageDecisions.toMap()

    override fun setDecision(rowIndex: Int, decision: TriageDecision) {
        triageDecisions[rowIndex] = decision
    }

    override fun updateRow(rowIndex: Int, front: String, back: String) {
        rowEdits[rowIndex] = front.trim() to back.trim()
    }

    override fun setRowImage(rowIndex: Int, isFront: Boolean, image: DraftCardImage?) {
        val target = if (isFront) rowFrontImages else rowBackImages
        if (image == null) target.remove(rowIndex) else target[rowIndex] = image
    }

    override fun rowImage(rowIndex: Int, isFront: Boolean): DraftCardImage? =
        if (isFront) rowFrontImages[rowIndex] else rowBackImages[rowIndex]

    override fun keptRows(): List<ParsedRow> {
        val d = draft ?: return emptyList()
        return d.rows
            .filter { triageDecisions[it.index] != TriageDecision.Discard }
            .map { row -> applyEdit(row) }
    }

    /** Returns [row] with any triage edit applied to its front/back fields. */
    private fun applyEdit(row: ParsedRow): ParsedRow {
        val edit = rowEdits[row.index] ?: return row
        val (front, back) = edit
        val fields = row.fields.toMutableList()
        while (fields.size <= BACK_FIELD) fields.add("")
        fields[FRONT_FIELD] = front
        fields[BACK_FIELD] = back
        return row.copy(fields = fields, isValid = front.isNotBlank() || back.isNotBlank())
    }

    override suspend fun parseBulk(rawText: String, separator: Separator?): Result<ImportDraft> =
        parseInternal(rawText, separator, maxChars = MAX_BULK_CHARS, maxCards = MAX_BULK_CARDS)

    override suspend fun parse(rawText: String, separator: Separator?): Result<ImportDraft> =
        parseInternal(rawText, separator, maxChars = MAX_CHARS, maxCards = MAX_CARDS)

    private fun parseInternal(
        rawText: String,
        separator: Separator?,
        maxChars: Int,
        maxCards: Int,
    ): Result<ImportDraft> = runCatching {
        // A fresh parse invalidates any prior triage decisions/edits.
        triageDecisions.clear()
        rowEdits.clear()
        rowFrontImages.clear()
        rowBackImages.clear()
        val text = rawText.replace("\r\n", "\n").replace("\r", "\n").trim()
        require(text.isNotEmpty()) { "Nothing to import." }
        require(text.length <= maxChars) { "Text is too long (max $maxChars characters)." }

        val resolved = separator?.takeIf { it != Separator.Auto } ?: detectSeparator(text)
        // A card has exactly two sides, so anything past the second column is dropped (spec §8).
        val rawRows = splitRows(text, resolved).map { it.take(MAX_FIELDS) }
        require(rawRows.isNotEmpty()) { "Could not parse any cards." }

        // Truncation used to be a silent `.take`, so a paste over the cap quietly lost its tail
        // with nothing in the UI to say so. The count is now reported on the draft.
        val truncated = (rawRows.size - maxCards).coerceAtLeast(0)
        val rows = rawRows.take(maxCards).mapIndexed { index, fields ->
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
            if (seen.add(row.fields)) {
                true
            } else { duplicatesCollapsed++; false }
        }

        ImportDraft(
            rawText = rawText,
            separator = resolved,
            rows = deduped,
            duplicatesCollapsed = duplicatesCollapsed,
            truncated = truncated,
        ).also { draft = it }
    }

    override fun clear() {
        draft = null
        triageDecisions.clear()
        rowEdits.clear()
        rowFrontImages.clear()
        rowBackImages.clear()
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

    // Prescriptive separator-detection rules (spec §6); the branching mirrors the spec's
    // rule order and is clearer kept whole than split across helpers.
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LoopWithTooManyJumpStatements")
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
            " \u2014 " to Separator.EmDash, // em-dash with spaces
            " \u2013 " to Separator.EmDash, // en-dash with spaces
            " - " to Separator.EmDash, // hyphen-minus with spaces
            ": " to Separator.Colon,
            "," to Separator.Comma,
        )

        for ((delimStr, sep) in candidates) {
            // Tab is unambiguous — simple presence check
            if (sep == Separator.Tab) {
                val count = lines.count { it.contains(delimStr) }
                if (count.toFloat() / lines.size >= MIN_DELIMITER_COVERAGE) return sep
                continue
            }

            // (A) For single-char delimiters, only count lines where the
            //     delimiter acts as a field separator, not punctuation.
            val qualifying = if (delimStr.length == 1) {
                lines.filter { line -> isSeparatorUse(line, delimStr[0]) }
            } else {
                lines.filter { it.contains(delimStr) }
            }

            if (qualifying.size.toFloat() / lines.size < MIN_DELIMITER_COVERAGE) continue

            // (B) Multi-char: reject if most lines have 2+ occurrences
            //     (parenthetical inserts, e.g. "The dog — big — barked")
            if (delimStr.length > 1) {
                val multiHit = qualifying.count { line ->
                    val first = line.indexOf(delimStr)
                    first >= 0 && line.indexOf(delimStr, first + delimStr.length) >= 0
                }
                if (multiHit.toFloat() / qualifying.size > MAX_MULTI_HIT_RATIO) continue
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
    @Suppress("ComplexCondition")
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
    // are preserved rather than creating spurious extra columns
    // (`"date: December 25: Christmas"` keeps its second colon, spec §9).
    //
    // Tab is the exception: it is a genuine column delimiter — spreadsheet and
    // Anki exports use it, and it effectively never appears inside card text —
    // so tab lines split on *every* tab. Columns past the back are dropped in
    // `parse()`, which is how a `front⇥back⇥tags` export loses its tag column
    // instead of gluing it onto the back.

    private fun splitRows(text: String, separator: Separator): List<List<String>> {
        return when (separator) {
            is Separator.MarkdownTable -> parseMarkdownTable(text)
            is Separator.BlankLine -> parseBlankLinePairs(text)
            is Separator.SingleColumn -> text.split("\n")
                .filter { it.isNotBlank() }
                .map { listOf(it.trim()) }
            is Separator.Tab -> text.split("\n")
                .filter { it.isNotBlank() }
                .map { line -> line.split("\t").map { it.trim() } }
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
                text.contains(" \u2014 ") -> " \u2014 " // em-dash
                text.contains(" \u2013 ") -> " \u2013 " // en-dash
                text.contains(" - ") -> " - " // hyphen-minus
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
        /**
         * Cap on a *pasted* string. Deliberately not raised for bulk import: a 20k-card Anki
         * export is ~2 MB of text, which has no business going through a text field the user is
         * meant to read. File import calls [parseBulk] and bypasses this entirely.
         */
        private const val MAX_CHARS = 10_000

        /**
         * Cap on cards from a paste. The constraint is swipe-triage, not storage — nobody swipes
         * through 20,000 cards — so this stays modest while [MAX_BULK_CARDS] governs file import.
         * Resolves specs §13 Q4.
         */
        private const val MAX_CARDS = 2_000

        /** Cap on a bulk file import. High enough for any real Anki deck; a backstop, not a policy. */
        internal const val MAX_BULK_CARDS = 100_000

        /** Characters accepted from a file. ~10 MB of text — far past any plausible Anki export. */
        private const val MAX_BULK_CHARS = 10_000_000

        /** A card has a front and a back; extra columns are dropped (spec §8). */
        private const val MAX_FIELDS = 2

        /** Flashcard fronts rarely exceed 30 characters. */
        private const val MAX_FRONT_CHARS = 30

        /** If the front is both > MAX_FRONT_CHARS and > 50% of the line,
         *  the delimiter is splitting prose, not flashcard pairs. */
        private const val MAX_FRONT_RATIO = 0.50f

        /** A delimiter must appear on at least this fraction of lines to qualify. */
        private const val MIN_DELIMITER_COVERAGE = 0.8f

        /** Reject a multi-char delimiter if more than this fraction of lines hit it twice. */
        private const val MAX_MULTI_HIT_RATIO = 0.5f
    }
}
