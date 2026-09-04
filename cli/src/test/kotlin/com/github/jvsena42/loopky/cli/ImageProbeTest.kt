package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.ImageCheck
import com.github.jvsena42.loopky.cli.commands.checkImageUrls
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `--check-images`, driven against a fake host.
 *
 * What it has to get right is what it *does not* report. The static checks already refuse the
 * knowably-broken; this one exists for the three cases a string cannot show — a `.stl` behind an
 * ordinary-looking address, a renamed file, a host refusing an unfamiliar client — so a run that
 * buried those in 900 lines of "this one is fine" would be no better than the hand-written check
 * it replaces.
 */
class ImageProbeTest {

    private fun ok(url: String) = ImageCheck(url, status = 200, contentType = "image/jpeg", ok = true)

    @Test
    fun `a picture that is a picture is reported nowhere`() = runBlocking {
        val notes = mutableListOf<String>()

        val problems = checkImageUrls(listOf("https://x.test/a.jpg"), notes::add) { ok(it) }

        assertEquals(emptyList(), problems)
        assertTrue(notes.none { "a.jpg" in it }, "a working URL earns no line of its own")
    }

    @Test
    fun `something that is not an image is reported with its content type`() = runBlocking {
        val notes = mutableListOf<String>()

        val problems = checkImageUrls(listOf("https://x.test/Rotonda.webm"), notes::add) {
            ImageCheck(it, status = 200, contentType = "video/webm", reason = "this is not an image")
        }

        assertEquals(1, problems.size)
        assertEquals("video/webm", problems.single().contentType)
        assertTrue(notes.any { "Rotonda.webm" in it && "video/webm" in it })
    }

    @Test
    fun `a host that does not answer is reported without a status`() = runBlocking {
        val notes = mutableListOf<String>()

        val problems = checkImageUrls(listOf("https://gone.test/a.jpg"), notes::add) {
            ImageCheck(it, reason = "could not be reached: connect timed out")
        }

        assertEquals(null, problems.single().status)
        assertTrue(notes.any { "could not be reached" in it })
    }

    /** A picture on forty cards is one question. The whole point is that this is cheap enough to run. */
    @Test
    fun `each distinct url is asked about once`() = runBlocking {
        val asked = mutableListOf<String>()

        checkImageUrls(List(40) { "https://x.test/same.jpg" } + "https://x.test/other.jpg", {}) {
            asked += it
            ok(it)
        }

        assertEquals(listOf("https://x.test/same.jpg", "https://x.test/other.jpg"), asked)
    }

    /** The write goes ahead either way, and the note has to say so rather than read like a refusal. */
    @Test
    fun `a run with problems says the write is happening anyway`() = runBlocking {
        val notes = mutableListOf<String>()

        checkImageUrls(listOf("https://x.test/a.jpg"), notes::add) {
            ImageCheck(it, status = 404, reason = "the host refused it")
        }

        assertContains(notes.last(), "writing anyway")
    }

    @Test
    fun `nothing to check is silent`() = runBlocking {
        val notes = mutableListOf<String>()

        val problems = checkImageUrls(listOf("", "  "), notes::add) { error("nothing should be probed") }

        assertEquals(emptyList(), problems)
        assertEquals(emptyList(), notes)
    }
}
