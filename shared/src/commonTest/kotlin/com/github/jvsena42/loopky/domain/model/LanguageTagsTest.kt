package com.github.jvsena42.loopky.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageTagsTest {

    @Test
    fun theRegionPicksAVoiceNeverALabel() {
        // es-ES and es-MX are both just Spanish: labelling them apart would halve a search for
        // Spanish decks, and a deck with both sides in Spanish carries one label, not two.
        assertEquals(listOf(Tag("spanish")), LanguageTags.forPair("es-ES", "es-MX"))
        assertEquals(listOf(Tag("english"), Tag("spanish")), LanguageTags.forPair("en-US", "es-ES"))
    }

    @Test
    fun aDeckWithNoDeclaredPairCarriesNoLanguageLabel() {
        assertEquals(emptyList(), LanguageTags.forPair(null, null))
        assertEquals(listOf(Tag("english")), LanguageTags.forPair("en", null))
        assertEquals(emptyList(), LanguageTags.forPair("  ", null))
    }

    @Test
    fun anUnknownSubtagLabelsUnderItselfRatherThanGoingUntagged() {
        // Findable under something is better than findable under nothing.
        assertEquals(listOf(Tag("xh")), LanguageTags.forPair("xh-ZA", null))
    }

    @Test
    fun retagSwapsTheOldPairsLabelsAndKeepsEverythingElseInPlace() {
        assertEquals(
            listOf("verbs", "english", "french"),
            LanguageTags.retag(listOf("verbs", "spanish", "english"), "en-US", "es-ES", "en-US", "fr-FR"),
        )
    }

    @Test
    fun retagLeavesALabelThatBothPairsShare() {
        // "english" is in both, so it must not be dropped and re-appended — the author's tag order
        // is theirs, and reordering on every pick would look like the list rewriting itself.
        assertEquals(
            listOf("english", "verbs", "french"),
            LanguageTags.retag(listOf("english", "spanish", "verbs"), "en-US", "es-ES", "en-US", "fr-FR"),
        )
    }

    @Test
    fun retagAddsTheFirstPickToADeckThatHadNoLanguage() {
        assertEquals(
            listOf("verbs", "spanish"),
            LanguageTags.retag(listOf("verbs"), null, null, "es-ES", null),
        )
    }
}
