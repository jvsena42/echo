package com.github.jvsena42.loopky.data.anki

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cloze deletions expanded into cards (#96 finding 7). */
class AnkiClozeTest {

    @Test
    fun aDeletionBecomesAHoleOnTheFrontAndTheAnswerOnTheBack() {
        val cards = expandCloze("The capital of Spain is {{c1::Madrid}}")
        assertEquals(1, cards.size)
        assertEquals("The capital of Spain is ____", cards[0].front)
        assertEquals("Madrid", cards[0].back)
    }

    @Test
    fun eachIndexBecomesItsOwnCardWithTheOthersRevealed() {
        // Anki's own behaviour, and it matters: blanking every hole at once leaves nothing saying
        // which one is being asked about.
        val cards = expandCloze("The capital of Spain is {{c1::Madrid}} and it has {{c2::3.3M}} people")
        assertEquals(2, cards.size)
        assertEquals("The capital of Spain is ____ and it has 3.3M people", cards[0].front)
        assertEquals("Madrid", cards[0].back)
        assertEquals("The capital of Spain is Madrid and it has ____ people", cards[1].front)
        assertEquals("3.3M", cards[1].back)
    }

    @Test
    fun oneIndexUsedTwiceIsStillOneCard() {
        val cards = expandCloze("{{c1::Adenine}} pairs with thymine, {{c1::guanine}} with cytosine")
        assertEquals(1, cards.size)
        assertEquals("____ pairs with thymine, ____ with cytosine", cards[0].front)
        assertEquals("Adenine / guanine", cards[0].back)
    }

    @Test
    fun aHintIsShownInPlaceOfTheBlank() {
        val cards = expandCloze("The capital is {{c1::Madrid::city}}")
        assertEquals("The capital is [city]", cards[0].front)
        assertEquals("Madrid", cards[0].back)
    }

    @Test
    fun theExtraFieldRidesAlongOnTheBack() {
        val cards = expandCloze("{{c1::Madrid}} is the capital", extra = "Population 3.3M")
        assertEquals("Madrid\n\nPopulation 3.3M", cards[0].back)
    }

    @Test
    fun indicesComeOutInAnkisOrderNotTheOrderTheyAppear() {
        val cards = expandCloze("{{c2::second}} then {{c1::first}}")
        assertEquals(listOf("first", "second"), cards.map { it.back })
    }

    @Test
    fun anOrdinaryNoteIsNotCloze() {
        // Empty is the caller's cue to keep its ordinary front/back pair.
        assertTrue(expandCloze("Perro").isEmpty())
        assertTrue(expandCloze("Use {{Front}} in a template").isEmpty())
    }

    @Test
    fun aClozeNoteExpandsInsteadOfReadingItsExtraFieldAsTheAnswer() {
        val cards = ankiNoteToCards(
            fields = listOf(AnkiField("Water is {{c1::H₂O}}"), AnkiField("A note")),
            mapping = ApkgFieldMapping(0, 1),
        )
        assertEquals(1, cards.size)
        assertEquals("Water is ____", cards[0].front)
        assertEquals("H₂O\n\nA note", cards[0].back)
    }
}
