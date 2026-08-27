package com.github.jvsena42.loopky.domain.model

data class ImportDraft(
    val rawText: String,
    val separator: Separator,
    val rows: List<ParsedRow>,
    val duplicatesCollapsed: Int,
    /**
     * Rows dropped for exceeding the parser's cap. Truncation used to be a silent `.take`, so an
     * over-long import lost its tail with nothing in the UI to say so.
     */
    val truncated: Int = 0,
    /**
     * A deck title to prefill the commit screen with: the `.apkg`'s own deck name, else the file
     * it came from. Null for a paste — there is nothing to guess from, and an invented title is
     * worse than an empty field the user knows to fill in.
     */
    val suggestedTitle: String? = null,
    /**
     * The source deck's own description, to prefill the commit screen beside [suggestedTitle].
     * Null for a paste, which has nothing to guess from.
     */
    val suggestedDescription: String? = null,
    /**
     * Tags to offer as chips on the commit screen. A suggestion, not a decision: they arrive
     * editable and removable, because the source that proposed them (Anki's per-note tags) is not
     * describing the deck the way the user would.
     */
    val suggestedTags: List<String> = emptyList(),
    /**
     * The source said this deck is meant to be drilled in both directions — an `.apkg` built on a
     * reversed note type. Prefills the commit screen's opt-in the way [suggestedTags] prefills its
     * chips: a starting point, editable before anything is published.
     */
    val suggestsReverse: Boolean = false,
    /**
     * True when this draft came from a source that knew its own fields, so the text was never
     * split by a separator. The summary offers a field picker instead of a separator override —
     * a separator is not a thing an `.apkg` has.
     */
    val structured: Boolean = false,
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

/**
 * The (front, back) text pair for [row]. The parser always emits the card front as field 0 and the
 * back as field 1 (spec §8); extra columns are dropped at parse time.
 */
fun ImportDraft.frontBackOf(row: ParsedRow): Pair<String, String> =
    row.fields.getOrElse(FRONT_FIELD) { "" } to row.fields.getOrElse(BACK_FIELD) { "" }

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

/** Field index of the card front in a [ParsedRow]. */
const val FRONT_FIELD = 0

/** Field index of the card back in a [ParsedRow]. */
const val BACK_FIELD = 1
