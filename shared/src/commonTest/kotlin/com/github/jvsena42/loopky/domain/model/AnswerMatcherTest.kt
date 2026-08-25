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
    fun isTypableRejectsEverythingNoAnswerCouldMatch() {
        // Each of these has text in it and is not blank, but normalizes to nothing — so
        // `matches` refuses it as an empty target and no typed string can ever get past it.
        for (untypable in listOf("", "   ", "—", "...", "?!", "→", "🇪🇸")) {
            assertFalse(AnswerMatcher.isTypable(untypable), "$untypable was called typable")
            assertFalse(AnswerMatcher.matches(untypable, untypable, Strict))
        }
    }

    @Test
    fun isTypableAcceptsAnythingWithALetterOrDigitInIt() {
        for (typable in listOf("hello", "buenos días", "H2O", "¿qué?", "42")) {
            assertTrue(AnswerMatcher.isTypable(typable), "$typable was called untypable")
        }
    }

    @Test
    fun bothStrictnessesIgnoreAParenthesizedAside() {
        for (strictness in AnswerStrictness.entries) {
            assertTrue(AnswerMatcher.matches("hello", "hello (formal)", strictness), "$strictness")
            assertTrue(AnswerMatcher.matches("hello (formal)", "hello", strictness), "$strictness")
            assertFalse(AnswerMatcher.matches("formal", "hello (formal)", strictness), "$strictness")
        }
    }

    @Test
    fun judgeAcceptsAnAnswerThatOmitsTheAside() {
        assertEquals(Correct, AnswerMatcher.judge("hello", "hello (formal)"))
        assertEquals(Correct, AnswerMatcher.judge("hello (formal)", "hello (formal)"))
        assertEquals(Wrong, AnswerMatcher.judge("formal", "hello (formal)"))
    }

    @Test
    fun judgeStillHoldsAccentsInsideAnAsideCard() {
        // Stripping the aside must not quietly relax the rest of the phrase.
        assertEquals(NearMiss, AnswerMatcher.judge("buenos dias", "buenos días (formal)"))
    }

    @Test
    fun isTypableAsksAboutTheTextThatWillActuallyBeCompared() {
        // The bracket is all this card has to type — but it is still answerable, so the mode
        // must not offer a card `judge` can never accept, nor withhold one it can.
        assertTrue(AnswerMatcher.isTypable("(formal)"))
        assertTrue(AnswerMatcher.isTypable("hello (formal)"))
        assertFalse(AnswerMatcher.isTypable("— (…)"))
    }

    @Test
    fun stripParentheticalsDropsAsides() {
        assertEquals("hello", AnswerMatcher.stripParentheticals("hello (formal)"))
        assertEquals("hello", AnswerMatcher.stripParentheticals("(formal) hello"))
        assertEquals("hello there", AnswerMatcher.stripParentheticals("hello (very formal) there"))
        assertEquals("konnichiwa", AnswerMatcher.stripParentheticals("konnichiwa（丁寧）"))
    }

    @Test
    fun stripParentheticalsKeepsTextWithNoAside() {
        assertEquals("el zorro", AnswerMatcher.stripParentheticals("el zorro"))
        // An unclosed bracket is not an aside — leave it to the punctuation stripping.
        assertEquals("hello (formal", AnswerMatcher.stripParentheticals("hello (formal"))
    }

    @Test
    fun stripParentheticalsLeavesAWhollyParenthesizedCardAnswerable() {
        // Stripping would empty it, and `matches` refuses an empty target — so keep it as written.
        assertEquals("(formal)", AnswerMatcher.stripParentheticals("(formal)"))
    }

    @Test
    fun normalizeCollapsesRunsOfWhitespace() {
        assertEquals("el zorro corre", AnswerMatcher.normalize("  el\t zorro\n\ncorre ", Strict))
    }
}
