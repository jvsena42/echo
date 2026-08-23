package com.github.jvsena42.loopky.domain.model

import com.github.jvsena42.loopky.domain.model.AnswerStrictness.Lenient
import com.github.jvsena42.loopky.domain.model.AnswerStrictness.Strict
import com.github.jvsena42.loopky.domain.model.TypedAnswerOutcome.Correct
import com.github.jvsena42.loopky.domain.model.TypedAnswerOutcome.NearMiss
import com.github.jvsena42.loopky.domain.model.TypedAnswerOutcome.Wrong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnswerMatcherTest {

    @Test
    fun bothStrictnessesIgnoreCasePunctuationAndWhitespace() {
        for (strictness in AnswerStrictness.entries) {
            assertTrue(
                AnswerMatcher.matches("  EL, Zorro!  ", "el zorro", strictness),
                "case/punctuation/whitespace should not matter at $strictness",
            )
        }
    }

    @Test
    fun onlyLenientFoldsDiacritics() {
        assertTrue(AnswerMatcher.matches("el zorro", "él zörró", Lenient))
        assertFalse(AnswerMatcher.matches("el zorro", "él zörró", Strict))
    }

    @Test
    fun strictAcceptsTheAccentsSpelledOut() {
        assertTrue(AnswerMatcher.matches("Buenos Días!", "buenos días", Strict))
    }

    @Test
    fun emptyExpectedNeverMatches() {
        for (strictness in AnswerStrictness.entries) {
            assertFalse(AnswerMatcher.matches("", "", strictness))
            assertFalse(AnswerMatcher.matches("anything", "   ", strictness))
            // Punctuation alone normalizes to nothing, so it is an empty target too.
            assertFalse(AnswerMatcher.matches("!", "!", strictness))
        }
    }

    @Test
    fun judgeScoresAnExactAnswerCorrect() {
        assertEquals(Correct, AnswerMatcher.judge("buenos días", "Buenos días"))
    }

    @Test
    fun judgeScoresAnAccentSlipANearMiss() {
        assertEquals(NearMiss, AnswerMatcher.judge("buenos dias", "buenos días"))
        assertEquals(NearMiss, AnswerMatcher.judge("el nino", "el niño"))
    }

    @Test
    fun judgeScoresAnythingElseWrong() {
        assertEquals(Wrong, AnswerMatcher.judge("el zoro", "el zorro"))
        assertEquals(Wrong, AnswerMatcher.judge("", "el zorro"))
        assertEquals(Wrong, AnswerMatcher.judge("el zorro", ""))
    }

    @Test
    fun normalizeCollapsesRunsOfWhitespace() {
        assertEquals("el zorro corre", AnswerMatcher.normalize("  el\t zorro\n\ncorre ", Strict))
    }
}
