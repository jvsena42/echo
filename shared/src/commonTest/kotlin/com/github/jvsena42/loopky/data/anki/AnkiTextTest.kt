package com.github.jvsena42.loopky.data.anki

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The strip-to-readable-text pipeline (#96 findings 6 and 8).
 *
 * Every case here is a real deck shape from the issue rather than an invented one — a chemistry
 * formula, an Anki table, a `<div>`-per-line answer, an audio tag on a language card.
 */
class AnkiTextTest {

    @Test
    fun subscriptsBecomeUnicodeSoTheChemistryStaysRight() {
        // `CH2COOH` is a different, wrong molecule.
        assertEquals("R = CH₂COOH", parseAnkiField("R = CH<sub>2</sub>COOH").text)
        assertEquals("H₂O", parseAnkiField("H<sub>2</sub>O").text)
        assertEquals("x³", parseAnkiField("x<sup>3</sup>").text)
    }

    @Test
    fun anUnmappableScriptFallsBackToAMarkerRatherThanVanishing() {
        assertEquals("x_i", parseAnkiField("x<sub>i</sub>").text)
    }

    @Test
    fun anEmptyScriptTagLeavesNothingBehind() {
        // An editor leaves `<sub>&nbsp;</sub>` around, and the fallback marker would hang a bare
        // "_" off the end of a formula.
        assertEquals("H₂O", parseAnkiField("H<sub>2</sub>O<sub>&nbsp;</sub>").text)
    }

    @Test
    fun blockBoundariesBecomeLinesInsteadOfRunningTogether() {
        assertEquals(
            "first\nsecond",
            parseAnkiField("<div>first</div><div>second</div>").text,
        )
        assertEquals("one\ntwo", parseAnkiField("one<br>two").text)
    }

    @Test
    fun tableRowsAndCellsKeepTheirBoundaries() {
        val html = "<table><tr><td>Enzyme</td><td>Km</td></tr><tr><td>Hexokinase</td><td>0.1</td></tr></table>"
        // Was "EnzymeKmHexokinase0.1".
        assertEquals("Enzyme · Km\nHexokinase · 0.1", parseAnkiField(html).text)
    }

    @Test
    fun soundTagsAreDroppedRatherThanPrintedOnTheCard() {
        // Not HTML, so the old tag stripper left it on the card face verbatim.
        assertEquals("Perro", parseAnkiField("Perro [sound:dog.mp3]").text)
    }

    @Test
    fun latexIsUnwrappedToItsSource() {
        assertEquals("Solve \\int x^2 dx", parseAnkiField("Solve [latex]\\int x^2 dx[/latex]").text)
        assertEquals("x^3/3 + C", parseAnkiField("[${'$'}]x^3/3[/${'$'}] + C").text)
    }

    @Test
    fun numericEntitiesDecodeBecauseAnkiWritesThem() {
        assertEquals("it's", parseAnkiField("it&#39;s").text)
        assertEquals("it's", parseAnkiField("it&#x27;s").text)
        assertEquals("a & b", parseAnkiField("a &amp; b").text)
    }

    @Test
    fun anEscapedEntityStaysEscaped() {
        // Decoding "&amp;" first would turn "&amp;lt;" into "<" instead of "&lt;".
        assertEquals("&lt;", parseAnkiField("&amp;lt;").text)
    }

    @Test
    fun styleAndScriptBodiesAreNotCardText() {
        assertEquals("Answer", parseAnkiField("<style>.card { color: red }</style>Answer").text)
    }

    @Test
    fun spacesCollapseWithinALineButNeverAcrossOne() {
        assertEquals("a b\nc d", parseAnkiField("a   b<br>c \t d").text)
    }

    @Test
    fun aFieldThatIsNothingButAPictureReportsThePicture() {
        val field = parseAnkiField("""<img src="dog.jpg">""")
        assertEquals("dog.jpg", field.imageSrc)
        assertEquals("", field.text)
    }

    @Test
    fun wrappersAndSoundTagsDoNotMakeAPictureIntoAMixedField() {
        // What an editor leaves around an image; none of it is content.
        assertEquals(
            "cat.png",
            parseAnkiField("""<div><img src="cat.png"><br>&nbsp;</div> [sound:meow.mp3]""").imageSrc,
        )
    }

    @Test
    fun aPictureWithProseBesideItIsNotAPictureField() {
        // Where to put the image is a layout decision this importer cannot make, so the text wins.
        val field = parseAnkiField("""The heart: <img src="heart.jpg">""")
        assertNull(field.imageSrc)
        assertEquals("The heart:", field.text)
    }

    @Test
    fun twoPicturesAreNotASinglePictureEither() {
        assertNull(parseAnkiField("""<img src="a.jpg"><img src="b.jpg">""").imageSrc)
    }

    @Test
    fun anImageWithNoSourceIsNotAPicture() {
        assertNull(parseAnkiField("<img>").imageSrc)
    }
}
