package com.github.jvsena42.loopky.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkSpanTest {

    @Test
    fun `finds an https link and reports its exact range`() {
        val text = "Source: https://loopky.app/decks here."
        val link = findLinks(text).single()

        assertEquals("https://loopky.app/decks", text.substring(link.start, link.end))
        assertEquals("https://loopky.app/decks", link.url)
    }

    @Test
    fun `prefixes a bare www host with https`() {
        val link = findLinks("See www.wikipedia.org for more").single()

        assertEquals("https://www.wikipedia.org", link.url)
    }

    @Test
    fun `drops sentence punctuation that trails a link`() {
        val text = "Built from https://loopky.app."
        val link = findLinks(text).single()

        assertEquals("https://loopky.app", text.substring(link.start, link.end))
    }

    @Test
    fun `keeps a closing bracket the link opened itself`() {
        val text = "https://en.wikipedia.org/wiki/Cat_(disambiguation)"
        val link = findLinks(text).single()

        assertEquals(text, link.url)
    }

    @Test
    fun `drops a closing bracket that wraps the link`() {
        val text = "Spanish basics (https://loopky.app/es)"
        val link = findLinks(text).single()

        assertEquals("https://loopky.app/es", link.url)
    }

    @Test
    fun `finds every link in a multiline description`() {
        val text = """
            Deck notes: https://loopky.app
            Mirror at www.example.org/decks
        """.trimIndent()

        assertEquals(
            listOf("https://loopky.app", "https://www.example.org/decks"),
            findLinks(text).map { it.url },
        )
    }

    @Test
    fun `does not link scheme-less prose`() {
        assertTrue(findLinks("Learn 500 words. No links here, e.g. nothing at all").isEmpty())
    }

    @Test
    fun `does not link a scheme with no host`() {
        assertTrue(findLinks("broken https:// and https://localhost").isEmpty())
    }

    @Test
    fun `does not match the www inside a full url twice`() {
        assertEquals(1, findLinks("https://www.loopky.app").size)
    }

    @Test
    fun `returns nothing for blank text`() {
        assertTrue(findLinks("").isEmpty())
    }
}
