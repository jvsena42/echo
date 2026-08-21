package com.github.jvsena42.loopky.data.anki

/**
 * Anki cloze deletions, expanded into cards.
 *
 * `{{c1::Madrid}}` marks a hole in a sentence. Anki generates one card per distinct index, showing
 * that hole blanked and the others revealed. Loopky's importer used to pass the markup through
 * untouched, so the card read `The capital of Spain is {{c1::Madrid}}` — the answer printed on the
 * question side, on one of the two note types every Anki user has (#96).
 *
 * Mapped onto Loopky's two-sided card: **front** is the sentence with this index blanked, **back**
 * is what was in the hole. Anki's own back shows the whole sentence with the answer highlighted;
 * that needs a rich card this app does not have, and the deletion alone is the part being recalled.
 */
internal data class ClozeCard(val front: String, val back: String)

/** The distinct cloze indices in [text], in the order Anki would number the cards. */
internal fun clozeIndices(text: String): List<Int> =
    CLOZE.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().sorted().toList()

/**
 * One card per cloze index in [text].
 *
 * [extra] is Anki's second field on a cloze note ("Extra"/"Back Extra"), appended to every card's
 * back the way Anki appends it. Returns empty when there is no cloze markup — the caller keeps its
 * ordinary front/back pair in that case rather than this deciding what a plain note looks like.
 */
internal fun expandCloze(text: String, extra: String = ""): List<ClozeCard> =
    clozeIndices(text).map { index ->
        ClozeCard(
            front = text.renderCloze(blankIndex = index),
            back = listOfNotNull(
                text.clozeAnswer(index).takeIf { it.isNotBlank() },
                extra.takeIf { it.isNotBlank() },
            ).joinToString("\n\n"),
        )
    }

/**
 * [text] with [blankIndex] replaced by a blank and every other index revealed.
 *
 * Revealing the others is Anki's behaviour and it matters: a sentence with three holes would
 * otherwise show two of them as blanks with nothing saying which one is being asked about.
 */
private fun String.renderCloze(blankIndex: Int): String =
    CLOZE.replace(this) { match ->
        val (answer, hint) = match.groupValues[2].splitHint()
        if (match.groupValues[1].toIntOrNull() == blankIndex) {
            if (hint.isNotBlank()) "[$hint]" else BLANK
        } else {
            answer
        }
    }.trim()

private fun String.clozeAnswer(index: Int): String =
    CLOZE.findAll(this)
        .filter { it.groupValues[1].toIntOrNull() == index }
        .map { it.groupValues[2].splitHint().first }
        .joinToString(" / ")

/** `{{c1::answer::hint}}` — everything after the first `::` inside the deletion is the hint. */
private fun String.splitHint(): Pair<String, String> {
    val separator = indexOf(HINT_SEPARATOR)
    return if (separator < 0) {
        trim() to ""
    } else {
        take(separator).trim() to drop(separator + HINT_SEPARATOR.length).trim()
    }
}

private const val HINT_SEPARATOR = "::"

/** What a blanked deletion looks like on the card front. */
private const val BLANK = "____"

// Every brace and bracket is escaped: Android's regex engine rejects a bare closing "}}",
// which is a class-initialiser crash rather than a bad match, and so takes the whole import
// down with "couldn't read it".
private val CLOZE = Regex("\\{\\{c(\\d+)::(.*?)\\}\\}", RegexOption.DOT_MATCHES_ALL)
