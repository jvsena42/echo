package com.github.jvsena42.eco.domain.model

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
