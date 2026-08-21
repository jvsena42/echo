package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.anki.BulkNote
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.TriageDecision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The structured entry into the parser (#96 finding 5).
 *
 * It shares `parseBulk`'s dedupe, caps and drop-incomplete policy; what these pin down is the three
 * places where a source that knows its own fields has to behave differently.
 */
class ImportRepositoryBulkNotesTest {

    private val repo = ImportRepositoryImpl()

    @Test
    fun notesBecomeRowsWithoutBeingSplitByASeparator() = runTest {
        val draft = repo.parseBulkNotes(
            listOf(
                BulkNote(front = "perro", back = "dog"),
                BulkNote(front = "gato", back = "cat"),
            ),
        ).getOrThrow()

        assertEquals(2, draft.rows.size)
        assertTrue(draft.structured)
        // A colon inside the text is text, not a delimiter: nothing split this.
        assertEquals(listOf("perro", "dog"), draft.rows[0].fields)
    }

    @Test
    fun aSideThatIsOnlyAPictureIsKeptRatherThanDiscardedAsEmpty() {
        // The whole point of importing images: judging emptiness on text alone drops every
        // image-only Anki answer before publish ever sees it.
        runTest {
            val draft = repo.parseBulkNotes(
                listOf(BulkNote(front = "Which bone?", back = "", backImage = image("femur.jpg"))),
            ).getOrThrow()

            assertEquals(1, repo.keptRows().size)
            assertEquals(emptyMap(), repo.decisions())
            assertNull(draft.rows.first().fields.getOrNull(1)?.takeIf { it.isNotBlank() })
        }
    }

    @Test
    fun aSideWithNeitherTextNorPictureIsStillDiscarded() = runTest {
        repo.parseBulkNotes(listOf(BulkNote(front = "orphan", back = ""))).getOrThrow()
        assertEquals(TriageDecision.Discard, repo.decisions()[0])
        assertTrue(repo.keptRows().isEmpty())
    }

    @Test
    fun rowPicturesSurviveTheRenumberingDedupeDoes() = runTest {
        // Attaching images from the caller cannot work: dedupe drops rows, so the index a picture
        // was read at is not the index publish asks with.
        repo.parseBulkNotes(
            listOf(
                BulkNote(front = "dup", back = "same"),
                BulkNote(front = "dup", back = "same"),
                BulkNote(front = "Which bone?", back = "femur", backImage = image("femur.jpg")),
            ),
        ).getOrThrow()

        val kept = repo.keptRows()
        assertEquals(2, kept.size)
        assertEquals("femur.jpg".encodeToByteArray().toList(), repo.rowImage(1, isFront = false)?.bytes?.toList())
    }

    @Test
    fun twoNotesWithTheSameWordsAndDifferentPicturesAreTwoCards() = runTest {
        val draft = repo.parseBulkNotes(
            listOf(
                BulkNote("Which bone?", "", backImage = image("femur.jpg"), imageKey = "femur.jpg"),
                BulkNote("Which bone?", "", backImage = image("tibia.jpg"), imageKey = "tibia.jpg"),
            ),
        ).getOrThrow()

        assertEquals(2, draft.rows.size)
        assertEquals(0, draft.duplicatesCollapsed)
    }

    @Test
    fun genuineDuplicatesStillCollapse() = runTest {
        val draft = repo.parseBulkNotes(
            listOf(BulkNote("perro", "dog"), BulkNote("perro", "dog")),
        ).getOrThrow()

        assertEquals(1, draft.rows.size)
        assertEquals(1, draft.duplicatesCollapsed)
    }

    @Test
    fun theSourcesTitleDescriptionAndTagsTravelOnTheDraft() = runTest {
        val draft = repo.parseBulkNotes(
            notes = listOf(BulkNote("perro", "dog")),
            suggestedTitle = "Spanish",
            suggestedDescription = "A starter deck",
            suggestedTags = listOf("spanish", "vocab"),
        ).getOrThrow()

        assertEquals("Spanish", draft.suggestedTitle)
        assertEquals("A starter deck", draft.suggestedDescription)
        assertEquals(listOf("spanish", "vocab"), draft.suggestedTags)
    }

    @Test
    fun aPasteCarriesNoneOfThoseByConstruction() = runTest {
        val draft = repo.parseBulk("perro\tdog").getOrThrow()
        assertNull(draft.suggestedDescription)
        assertTrue(draft.suggestedTags.isEmpty())
        assertTrue(!draft.structured)
    }

    @Test
    fun aParseOverTheCapReportsWhatItLeftBehind() = runTest {
        val notes = (1..ImportRepositoryImpl.MAX_BULK_CARDS + 5).map { BulkNote("front $it", "back $it") }
        val draft = repo.parseBulkNotes(notes).getOrThrow()

        assertEquals(ImportRepositoryImpl.MAX_BULK_CARDS, draft.rows.size)
        assertEquals(5, draft.truncated)
    }

    @Test
    fun anEmptyReadFailsRatherThanProducingAnEmptyDraft() = runTest {
        assertTrue(repo.parseBulkNotes(emptyList()).isFailure)
    }

    @Test
    fun aFreshParseForgetsTheLastOnesPictures() = runTest {
        repo.parseBulkNotes(listOf(BulkNote("a", "b", backImage = image("x.jpg")))).getOrThrow()
        repo.parseBulkNotes(listOf(BulkNote("c", "d"))).getOrThrow()
        assertNull(repo.rowImage(0, isFront = false))
    }

    private fun image(name: String) =
        DraftCardImage(bytes = name.encodeToByteArray(), mime = "image/jpeg")
}
