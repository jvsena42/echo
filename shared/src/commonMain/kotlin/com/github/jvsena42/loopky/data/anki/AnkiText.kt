package com.github.jvsena42.loopky.data.anki

/**
 * Turning one Anki note field into what a Loopky card side can actually show.
 *
 * Anki stores a field as a fragment of HTML with its own markup dialects layered on top —
 * `[sound:x.mp3]` for audio, `[latex]…[/latex]` for maths, `{{c1::…}}` for cloze deletions, and
 * `<img>` pointing into the archive's media blobs. The previous reader ran one
 * `Regex("<[^>]+>")` over the lot, which flattened structure that carries meaning: `H<sub>2</sub>O`
 * became `H2O` in a chemistry deck, a table became `EnzymeKmHexokinase0.1`, and every `<div>`-per-
 * line answer became one run-on line. Sound tags survived it untouched, so `[sound:dog.mp3]` was
 * printed on the card, while images went the other way and vanished with no trace at all (#96).
 */
internal data class AnkiField(
    /** The field as readable text. May contain newlines — a card side is not one line. */
    val text: String,
    /**
     * The `src` of this field's image, set only when the field is **nothing but** that image.
     *
     * Restricted to the sole-image case on purpose: that is the shape where dropping the picture
     * loses the whole card, and where putting it on the side is unambiguous. A field mixing prose
     * and figures needs a layout decision this importer has no way to make.
     */
    val imageSrc: String? = null,
) {
    val isEmpty: Boolean get() = text.isBlank() && imageSrc == null
}

/** Parse one raw `flds` value into what a card side can show. */
internal fun parseAnkiField(raw: String): AnkiField {
    val withoutHidden = raw.replace(HIDDEN_ELEMENT, " ")
    // Before anything strips tags: a field that is only a picture is a picture, not an empty card.
    val soleImage = withoutHidden.soleImageSrc()
    return AnkiField(text = withoutHidden.toPlainText(), imageSrc = soleImage)
}

/**
 * The `src` of the only `<img>` in this field, or null if there is anything else in it.
 *
 * "Anything else" is judged after the image is removed: separators an editor leaves behind — a
 * `<br>`, a wrapping `<div>`, `&nbsp;` — do not make a picture into a mixed field.
 */
private fun String.soleImageSrc(): String? {
    val images = IMG_TAG.findAll(this).toList()
    val image = images.singleOrNull() ?: return null
    val src = IMG_SRC.find(image.value)?.groupValues?.get(1)?.decodeEntities()?.trim()
    if (src.isNullOrBlank()) return null
    val remainder = removeRange(image.range).replace(SOUND_TAG, " ").toPlainText()
    return src.takeIf { remainder.isBlank() }
}

/** The whole strip-to-readable-text pipeline. Order matters; see the comments on each step. */
private fun String.toPlainText(): String = this
    // Audio is not imported, so the tag is noise rather than content. Left in, it printed
    // "Perro [sound:dog.mp3]" on the card face.
    .replace(SOUND_TAG, " ")
    // Nothing here can typeset LaTeX. The source is at least readable; the delimiters are not.
    .replace(LATEX_WRAPPER) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
    .mapScriptTags()
    // Block boundaries carry the structure. A newline is only possible now that a note is no
    // longer flattened into one tab-separated line.
    .replace(BLOCK_BOUNDARY, "\n")
    .replace(CELL_BOUNDARY, " · ")
    .replace(ANY_TAG, "")
    .decodeEntities()
    .collapseWhitespace()

/**
 * `<sub>`/`<sup>` to Unicode where the characters exist, and to `_`/`^` where they don't.
 *
 * A chemistry deck is the common case and the one that breaks worst: stripping the tags outright
 * turns `CH<sub>2</sub>COOH` into `CH2COOH`, which is a different, wrong formula.
 */
private fun String.mapScriptTags(): String =
    replace(SUB_TAG) { it.groupValues[1].toScript(SUBSCRIPTS, "_") }
        .replace(SUP_TAG) { it.groupValues[1].toScript(SUPERSCRIPTS, "^") }

private fun String.toScript(table: Map<Char, Char>, fallbackPrefix: String): String {
    // Blank, not just empty: an editor leaves `<sub>&nbsp;</sub>` behind, and the fallback prefix
    // would render that as a bare "_" hanging off the end of a formula.
    val inner = replace(ANY_TAG, "").decodeEntities().trim()
    if (inner.isEmpty()) return ""
    return if (inner.all { table.containsKey(it) }) {
        inner.map { table.getValue(it) }.joinToString("")
    } else {
        "$fallbackPrefix$inner"
    }
}

/**
 * Named and numeric entities.
 *
 * Numeric ones matter more than they look: Anki's editor writes `&#39;` for an apostrophe, so a
 * decoder that only knows names leaves `it&#39;s` on the card.
 */
private fun String.decodeEntities(): String =
    NUMERIC_ENTITY.replace(this) { match ->
        val (hex, digits) = match.destructured
        val code = if (hex.isNotEmpty()) hex.toIntOrNull(HEX_RADIX) else digits.toIntOrNull()
        if (code != null && code in 1..Char.MAX_VALUE.code) code.toChar().toString() else match.value
    }.let { decoded ->
        NAMED_ENTITIES.entries.fold(decoded) { acc, (name, value) -> acc.replace(name, value) }
    }

/**
 * Spaces collapse **within** a line, never across one.
 *
 * Collapsing every run of whitespace is what made the block boundaries above pointless — the
 * newlines they insert would be eaten by the very next step.
 */
private fun String.collapseWhitespace(): String =
    split('\n')
        .joinToString("\n") { it.replace(HORIZONTAL_SPACE, " ").trim() }
        .replace(BLANK_LINES, "\n\n")
        .trim()

private val HIDDEN_ELEMENT =
    Regex("<(style|script)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
private val IMG_SRC = Regex("src\\s*=\\s*[\"']?([^\"'>\\s]+)", RegexOption.IGNORE_CASE)
private val SOUND_TAG = Regex("\\[sound:[^\\]]*\\]", RegexOption.IGNORE_CASE)
private val LATEX_WRAPPER = Regex(
    "\\[latex\\]([\\s\\S]*?)\\[/latex\\]|\\[\\${'$'}\\${'$'}?\\]([\\s\\S]*?)\\[/\\${'$'}\\${'$'}?\\]",
    RegexOption.IGNORE_CASE,
)
private val SUB_TAG = Regex("<sub[^>]*>(.*?)</sub>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val SUP_TAG = Regex("<sup[^>]*>(.*?)</sup>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val BLOCK_BOUNDARY = Regex(
    "<br\\s*/?>|</?(div|p|tr|li|ul|ol|table|h[1-6])[^>]*>",
    RegexOption.IGNORE_CASE,
)
private val CELL_BOUNDARY = Regex("</(td|th)>", RegexOption.IGNORE_CASE)
private val ANY_TAG = Regex("<[^>]+>")
private val NUMERIC_ENTITY = Regex("&#(?:[xX]([0-9a-fA-F]+)|([0-9]+));")
private val HORIZONTAL_SPACE = Regex("[^\\S\\n]+")
private val BLANK_LINES = Regex("\\n{3,}")

private const val HEX_RADIX = 16

private val NAMED_ENTITIES = mapOf(
    "&nbsp;" to " ",
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&apos;" to "'",
    "&hellip;" to "…",
    "&mdash;" to "—",
    "&ndash;" to "–",
    "&rsquo;" to "\u2019",
    "&lsquo;" to "\u2018",
    "&rdquo;" to "\u201D",
    "&ldquo;" to "\u201C",
    // Last: decoding it first would turn "&amp;lt;" into "<" rather than "&lt;".
    "&amp;" to "&",
)

private val SUBSCRIPTS = mapOf(
    '0' to '\u2080', '1' to '\u2081', '2' to '\u2082', '3' to '\u2083', '4' to '\u2084',
    '5' to '\u2085', '6' to '\u2086', '7' to '\u2087', '8' to '\u2088', '9' to '\u2089',
    '+' to '\u208A', '-' to '\u208B', '=' to '\u208C', '(' to '\u208D', ')' to '\u208E',
    'n' to '\u2099', 'x' to '\u2093',
)

private val SUPERSCRIPTS = mapOf(
    '0' to '\u2070', '1' to '\u00B9', '2' to '\u00B2', '3' to '\u00B3', '4' to '\u2074',
    '5' to '\u2075', '6' to '\u2076', '7' to '\u2077', '8' to '\u2078', '9' to '\u2079',
    '+' to '\u207A', '-' to '\u207B', '=' to '\u207C', '(' to '\u207D', ')' to '\u207E',
    'n' to '\u207F',
)
