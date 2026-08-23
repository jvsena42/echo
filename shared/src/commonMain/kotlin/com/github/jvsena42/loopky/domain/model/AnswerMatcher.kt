package com.github.jvsena42.loopky.domain.model

/**
 * How forgiving a comparison of an answer to a card's expected text should be.
 *
 * The distinction is the whole reason this is a parameter rather than one fixed normalization:
 * a recognizer never had accents to get wrong in the first place, whereas typing "el zorro" for
 * "el zorró" is precisely the mistake a typed answer exists to surface.
 */
enum class AnswerStrictness {
    /** Diacritics folded away. Right for speech, where they were never the user's to type. */
    Lenient,

    /** Diacritics significant. Right for typing. */
    Strict,
}

/** How close a typed answer came. [NearMiss] is a strict miss that a [AnswerStrictness.Lenient] pass would have let through. */
enum class TypedAnswerOutcome { Correct, NearMiss, Wrong }

/**
 * Pure, testable comparison of an answer to a card's expected text.
 *
 * Both sides are lowercased, stripped of punctuation and whitespace-collapsed before comparison,
 * so "El Zorro!" matches "el zorro" — none of case, punctuation or spacing is what either study
 * mode is testing. Whether accents survive that is [AnswerStrictness]'s business.
 *
 * Framework-free so it unit-tests in commonTest and is reused across platforms.
 */
object AnswerMatcher {

    fun matches(given: String, expected: String, strictness: AnswerStrictness): Boolean {
        val target = normalize(expected, strictness)
        return target.isNotEmpty() && normalize(given, strictness) == target
    }

    /**
     * Grade a typed answer, reserving [TypedAnswerOutcome.NearMiss] for the accent-only slip.
     *
     * A near miss is reported rather than scored: it reveals the answer and says which way it
     * differed. Nothing here picks an SRS grade — that stays the user's, whatever the outcome.
     */
    fun judge(typed: String, expected: String): TypedAnswerOutcome = when {
        matches(typed, expected, AnswerStrictness.Strict) -> TypedAnswerOutcome.Correct
        matches(typed, expected, AnswerStrictness.Lenient) -> TypedAnswerOutcome.NearMiss
        else -> TypedAnswerOutcome.Wrong
    }

    /** Lowercase, drop non-alphanumeric chars, collapse whitespace; fold diacritics when lenient. */
    fun normalize(text: String, strictness: AnswerStrictness): String = buildString {
        for (ch in text.lowercase()) {
            val base = if (strictness == AnswerStrictness.Lenient) stripDiacritic(ch) else ch
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
