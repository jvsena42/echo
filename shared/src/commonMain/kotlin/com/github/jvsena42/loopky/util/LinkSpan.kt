package com.github.jvsena42.loopky.util

/**
 * A stretch of [text] that should be rendered as a tappable link.
 *
 * [start]/[end] index the *original* string (end-exclusive) so a caller can style it in place;
 * [url] is the openable form, which differs from the matched text for bare `www.` hosts.
 */
data class LinkSpan(val start: Int, val end: Int, val url: String)

/**
 * Finds the http(s) and bare `www.` links inside free text such as a deck description.
 *
 * Deliberately narrow: users write prose, not markup, so a scheme-less `example.com` stays plain
 * text rather than turning every sentence-ending abbreviation into a link.
 */
fun findLinks(text: String): List<LinkSpan> =
    LINK_REGEX.findAll(text).mapNotNull { match -> match.toLinkSpan(text) }.toList()

private fun MatchResult.toLinkSpan(text: String): LinkSpan? {
    val start = range.first
    val end = trimmedEnd(text, start, range.last + 1)
    if (end <= start) return null
    val raw = text.substring(start, end)
    if (!raw.hasHost()) return null
    val url = if (raw.startsWith(WWW_PREFIX, ignoreCase = true)) "https://$raw" else raw
    return LinkSpan(start = start, end = end, url = url)
}

/**
 * Walks back over punctuation that reads as sentence punctuation rather than part of the URL —
 * "see https://loopky.app." should not link the full stop. A closing bracket is kept when the match
 * opens one itself, so `…/wiki/Foo_(bar)` survives while `(see https://loopky.app)` does not.
 */
private fun trimmedEnd(text: String, start: Int, matchEnd: Int): Int {
    var end = matchEnd
    while (end > start && text.isSentencePunctuationAt(start, end)) {
        end--
    }
    return end
}

/** Whether the character ending the `[start, end)` candidate belongs to the prose, not the URL. */
private fun String.isSentencePunctuationAt(start: Int, end: Int): Boolean {
    val last = this[end - 1]
    if (last !in TRAILING_PUNCTUATION) return false
    val opener = BRACKET_PAIRS[last] ?: return true
    return !hasBalancedBrackets(start, end, opener, last)
}

private fun String.hasBalancedBrackets(start: Int, end: Int, opener: Char, closer: Char): Boolean {
    var depth = 0
    for (i in start until end) {
        when (this[i]) {
            opener -> depth++
            closer -> depth--
        }
    }
    return depth >= 0
}

/** Rejects a scheme with nothing behind it (`https://`) and a host with no dot (`www.`). */
private fun String.hasHost(): Boolean {
    val afterScheme = substringAfter("://", missingDelimiterValue = this)
    val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    return host.length > 1 && host.contains('.') && !host.endsWith('.')
}

private const val WWW_PREFIX = "www."
private val LINK_REGEX = Regex("""(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
private val BRACKET_PAIRS = mapOf(')' to '(', ']' to '[', '}' to '{')
private val TRAILING_PUNCTUATION = charArrayOf(
    '.', ',', ';', ':', '!', '?', '"', '\'', '’', '”', ')', ']', '}', '»', '*', '~',
).toSet()
