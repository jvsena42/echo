package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.domain.model.isRenderableImageUrl

/**
 * What the CLI can tell an agent about a picture it will never fetch.
 *
 * Every other client puts a human in front of the image before it is stored — the sheet's Done
 * button waits on a preview that actually loaded. `loopky` has no such moment: it takes a URL,
 * writes it into a card, and reports success, so a deck of 200 cards can be built entirely out of
 * addresses that answer 403. That is not hypothetical; it is how this file came to exist.
 *
 * Fetching them is not the answer. It would make `card add` a crawler, turn a one-second write
 * into a hundred round trips against hosts that rate-limit, and still say nothing about the ones
 * that answer 200 today and 403 tomorrow. So the split here is by what a string can be *known* to
 * be wrong about, without asking anyone:
 *
 * - **[requireRenderableImageUrl] refuses** what can never render on any device Loopky ships for.
 * - **[imageUrlAdvice] warns** about a host rule that is real, checkable and currently being
 *   broken — on stderr, never fatal, because a host's rules are theirs to change and a stale
 *   check must not be able to fail somebody's import.
 */

/**
 * The one call for an image URL arriving from outside: refuses what can never render, and notes
 * what is merely likely to be wrong.
 *
 * Both halves at one call site because they are one question — "is this picture going to appear" —
 * asked at the only moment anyone can still act on the answer. [onNote] defaults to stderr, which
 * is where every other advisory in this client goes and is what keeps `--json`'s stdout a clean
 * machine channel; it is a parameter so the behaviour is testable without capturing a stream.
 */
internal fun String.checkedImageUrl(where: String, onNote: (String) -> Unit = System.err::println): String {
    requireRenderableImageUrl(where)
    imageUrlAdvice(this)?.let { onNote("loopky: $where — $it") }
    return this
}

/**
 * [url], or a [CliError] naming why it could never render.
 *
 * The scheme is the whole of it. Android blocks cleartext at targetSdk 28+, iOS ATS does the same,
 * so an `http://` ref is a card whose picture cannot appear on either client — and refusing it
 * here is the last point where the person who typed it is still around to fix it.
 */
internal fun String.requireRenderableImageUrl(where: String): String {
    if (isRenderableImageUrl()) return this
    val why = when {
        startsWith("http://", ignoreCase = true) ->
            "it is an http:// address. Android and iOS both refuse cleartext, so the picture " +
                "would never render on either — use https://."

        any { it.isWhitespace() } -> "it contains a space, which no address can."
        else -> "it must be an https:// URL."
    }
    throw CliError(ExitCode.BadInput, "$where holds \"${take(IMAGE_URL_EXCERPT)}\" — $why")
}

/**
 * A note worth putting on stderr about [url], or null when there is nothing to say.
 *
 * One rule so far, because one rule accounts for most of a real broken deck: Wikimedia serves
 * thumbnails at **a fixed set of widths only** and answers `400, Use thumbnail sizes listed on
 * https://w.wiki/GHai` for every other one. An agent writing image URLs produces `320px-` or
 * `800px-` as readily as `250px-` — they all look equally plausible, and four out of five are a
 * blank card on both clients.
 *
 * The original file (no `/thumb/` segment) is always served, which is why that is what the advice
 * names: it is the answer that cannot go stale.
 */
internal fun imageUrlAdvice(url: String): String? {
    if (!url.contains(WIKIMEDIA_UPLOAD_HOST, ignoreCase = true)) return null
    if (!url.contains(THUMB_SEGMENT)) return null
    val width = url.wikimediaThumbWidth() ?: return null
    if (width in WIKIMEDIA_THUMB_WIDTHS) return null
    return "$url\n" +
        "  Wikimedia serves thumbnails only at ${WIKIMEDIA_THUMB_WIDTHS.joinToString(", ")} px " +
        "and answers 400 for ${width}px, so this card's picture will be blank on both apps.\n" +
        "  Use one of those widths, or drop the /thumb/ segment and the NNNpx- prefix to get the " +
        "full-size original, which is always served."
}

/**
 * The `NNN` of the trailing `.../NNNpx-Name.jpg` segment, or null when the URL is not that shape.
 *
 * Read from the **last** path segment rather than by searching the whole URL: `px-` occurs in
 * plenty of file names, and a thumbnail's width is only ever the prefix of its own segment.
 */
private fun String.wikimediaThumbWidth(): Int? = substringAfterLast('/')
    .substringBefore(PX_MARKER, missingDelimiterValue = "")
    .takeIf { it.isNotEmpty() }
    ?.toIntOrNull()

/**
 * The widths `upload.wikimedia.org` served on 2026-09-04, measured across 35 candidates.
 *
 * A snapshot, and treated as one — this drives a warning and never a refusal, so the cost of the
 * list going stale is a note nobody needed rather than an import that will not run.
 */
private val WIKIMEDIA_THUMB_WIDTHS = listOf(120, 250, 330, 500, 960, 1280)

private const val WIKIMEDIA_UPLOAD_HOST = "upload.wikimedia.org"
private const val THUMB_SEGMENT = "/thumb/"
private const val PX_MARKER = "px-"
private const val IMAGE_URL_EXCERPT = 60
