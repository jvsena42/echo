package com.github.jvsena42.loopky.domain.model

data class ImportDraft(
    val rawText: String,
    val separator: Separator,
    val columnMapping: ColumnMapping,
    val rows: List<ParsedRow>,
    val duplicatesCollapsed: Int,
    val flags: List<ParseFlag>,
)

data class ParsedRow(
    val index: Int,
    val fields: List<String>,
    val isValid: Boolean,
)

/** A keep/discard decision made during triage (spec §5.5). Rows default to [Keep]. */
enum class TriageDecision { Keep, Discard }

/**
 * An image attached to a draft card side during triage, resolved at publish time:
 * a web [url] is stored as a remote ref, while gallery [bytes] are uploaded as a blob.
 */
data class DraftCardImage(
    val url: String? = null,
    val bytes: ByteArray? = null,
    val mime: String? = null,
)

/** Index of the field mapped to the card front (falls back to 0). */
fun ImportDraft.frontIndex(): Int =
    columnMapping.assignments.indexOfFirst { it == ColumnRole.Front }.takeIf { it >= 0 } ?: 0

/** Index of the field mapped to the card back (falls back to 1). */
fun ImportDraft.backIndex(): Int =
    columnMapping.assignments.indexOfFirst { it == ColumnRole.Back }.takeIf { it >= 0 } ?: 1

/** The (front, back) text pair for [row] using this draft's column mapping. */
fun ImportDraft.frontBackOf(row: ParsedRow): Pair<String, String> =
    row.fields.getOrElse(frontIndex()) { "" } to row.fields.getOrElse(backIndex()) { "" }

sealed class Separator {
    data object Auto : Separator()
    data object Tab : Separator()
    data object Semicolon : Separator()
    data object Pipe : Separator()
    data object EmDash : Separator()
    data object Colon : Separator()
    data object Comma : Separator()
    data object BlankLine : Separator()
    data object MarkdownTable : Separator()
    data object SingleColumn : Separator()
    data class Custom(val char: Char) : Separator()
}

data class ColumnMapping(
    val assignments: List<ColumnRole>,
) {
    companion object {
        val DEFAULT_TWO_COL = ColumnMapping(listOf(ColumnRole.Front, ColumnRole.Back))
        val DEFAULT_THREE_COL =
            ColumnMapping(listOf(ColumnRole.Front, ColumnRole.Back, ColumnRole.Tags))
    }
}

enum class ColumnRole { Front, Back, Tags, Ignore }

sealed class ParseFlag {
    data class MismatchedRowLength(val rowIndex: Int) : ParseFlag()
    data class InvalidUtf8(val rowIndex: Int) : ParseFlag()
    data class LongCard(val rowIndex: Int) : ParseFlag()
    data object SingleColumnFallback : ParseFlag()
    data object OverMaxSize : ParseFlag()
}
