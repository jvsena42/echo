package com.github.jvsena42.loopky.domain.model

/**
 * Outcome of comparing a spoken utterance against the expected card text.
 *
 * @property correct whether the normalized utterance matched the expected text
 * @property heard the raw transcript the recognizer returned (shown on the "wrong" sheet)
 * @property expected the raw expected text (shown on the "wrong" sheet)
 */
data class SpeakResult(
    val correct: Boolean,
    val heard: String,
    val expected: String,
)

/**
 * Pure, testable comparison of a spoken answer to a card's expected text.
 *
 * Both sides are normalized (lowercased, diacritics stripped, punctuation removed, whitespace
 * collapsed) before comparison so "El Zorro!" matches "el zorro". Kept framework-free so it can
 * be unit-tested in commonTest and reused across platforms.
 */
object SpeakMatcher {

    fun match(spoken: String, expected: String): SpeakResult = SpeakResult(
        correct = normalize(spoken) == normalize(expected) && normalize(expected).isNotEmpty(),
        heard = spoken.trim(),
        expected = expected.trim(),
    )

    /** Lowercase, strip diacritics, drop non-alphanumeric chars, collapse whitespace. */
    fun normalize(text: String): String = buildString {
        for (ch in text.lowercase()) {
            val base = stripDiacritic(ch)
            when {
                base.isLetterOrDigit() -> append(base)
                base.isWhitespace() -> append(' ')
                // drop everything else (punctuation, symbols)
            }
        }
    }.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")

    /** Map the common Latin accented letters used by supported languages to their base form. */
    private fun stripDiacritic(ch: Char): Char = when (ch) {
        'á', 'à', 'â', 'ä', 'ã', 'å' -> 'a'
        'é', 'è', 'ê', 'ë' -> 'e'
        'í', 'ì', 'î', 'ï' -> 'i'
        'ó', 'ò', 'ô', 'ö', 'õ' -> 'o'
        'ú', 'ù', 'û', 'ü' -> 'u'
        'ñ' -> 'n'
        'ç' -> 'c'
        else -> ch
    }
}
