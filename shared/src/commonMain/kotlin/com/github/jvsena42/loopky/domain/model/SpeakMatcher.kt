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
 * Pronunciation practice's view of [AnswerMatcher] — the same normalization, fixed to
 * [AnswerStrictness.Lenient].
 *
 * Speech is graded leniently because a recognizer emits whatever text its model settles on; the
 * accents in that transcript were never the speaker's to get right. Typing is graded strictly for
 * the mirror-image reason.
 */
object SpeakMatcher {

    fun match(spoken: String, expected: String): SpeakResult = SpeakResult(
        correct = AnswerMatcher.matches(spoken, expected, AnswerStrictness.Lenient),
        heard = spoken.trim(),
        expected = expected.trim(),
    )

    fun normalize(text: String): String = AnswerMatcher.normalize(text, AnswerStrictness.Lenient)
}
