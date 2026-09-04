package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.checkedImageUrl
import com.github.jvsena42.loopky.cli.commands.imageUrlAdvice
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ImageUrlAdviceTest {

    private val gull = "https://upload.wikimedia.org/wikipedia/commons/thumb/9/9a/Gull.jpg"

    @Test
    fun `a disallowed wikimedia thumbnail width is called out`() {
        val advice = imageUrlAdvice("$gull/320px-Gull.jpg")
        assertTrue(advice != null)
        assertContains(advice, "320px")
        assertContains(advice, "blank")
    }

    @Test
    fun `the widths wikimedia actually serves are left alone`() {
        for (width in listOf(120, 250, 330, 500, 960, 1280)) {
            assertNull(imageUrlAdvice("$gull/${width}px-Gull.jpg"), "$width should be accepted")
        }
    }

    /** The original file has no width to get wrong, which is why the advice recommends it. */
    @Test
    fun `a non-thumbnail wikimedia url has nothing to say about it`() {
        assertNull(imageUrlAdvice("https://upload.wikimedia.org/wikipedia/commons/9/9a/Gull.jpg"))
    }

    @Test
    fun `other hosts are not second-guessed`() {
        assertNull(imageUrlAdvice("https://example.test/thumb/320px-cat.jpg"))
        assertNull(imageUrlAdvice("https://example.test/cat.jpg"))
    }

    /**
     * The width is the *last* segment's own prefix, not the first `NNNpx-` anywhere in the URL.
     * A Wikimedia thumbnail repeats the file name, so an earlier segment can carry one too — and
     * reading that one would warn about a URL whose actual thumbnail width is fine.
     *
     * The converse is a knowing false positive: a file genuinely named `100px-art.jpg` sitting
     * under /thumb/ is indistinguishable from a thumbnail and gets the note. That costs a line of
     * stderr on a URL that works, which is the right way round for something advisory.
     */
    @Test
    fun `the width comes from the last segment, not an earlier one`() {
        assertNull(imageUrlAdvice("$gull/100px-art/250px-Gull.jpg"))
    }

    @Test
    fun `http is refused with a message naming the scheme`() {
        val error = runCatching { "http://example.test/cat.jpg".checkedImageUrl("--front-image") }
            .exceptionOrNull() as? CliError ?: fail("expected a CliError")
        assertEquals(ExitCode.BadInput, error.exitCode)
        assertContains(error.message.orEmpty(), "https://")
        assertContains(error.message.orEmpty(), "--front-image")
    }

    @Test
    fun `a good url passes through and says nothing`() {
        val notes = mutableListOf<String>()
        val url = "https://example.test/cat.jpg".checkedImageUrl("--front-image", notes::add)
        assertEquals("https://example.test/cat.jpg", url)
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `a bad wikimedia width passes through but is noted`() {
        val notes = mutableListOf<String>()
        "$gull/800px-Gull.jpg".checkedImageUrl("--back-image", notes::add)
        assertEquals(1, notes.size)
        assertContains(notes.single(), "--back-image")
    }
}
