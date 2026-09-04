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
 * Notes worth putting on stderr about [url], or null when there is nothing to say.
 *
 * Two rules, and both come from real decks rather than from what a host's documentation says.
 *
 * **Thumbnail widths.** Wikimedia serves thumbnails at a fixed set of widths only and answers
 * `400, Use thumbnail sizes listed on https://w.wiki/GHai` for every other one. An agent writes
 * `320px-` or `800px-` as readily as `250px-` — they all look equally plausible, and four out of
 * five are a blank card on both clients.
 *
 * **File types neither client decodes.** A Wikipedia lead image is not necessarily a picture:
 * `Dente` and `Tostapane` resolve to `.stl` and `.tiff`, `Rotonda` to `.webm`, and every flag and
 * colour swatch to `.svg`. All are valid `upload.wikimedia.org` addresses that 200, and all are
 * the same blank card — Android decodes with Coil, which ships no SVG decoder here, and iOS with
 * `UIImage`, which decodes neither.
 *
 * That second rule **inverts the first for an SVG**, which is why they are one function rather
 * than two. "Drop the `/thumb/` segment for the original, which is always served" is right for a
 * raster file and exactly backwards for a vector one: the original is `image/svg+xml` and the
 * thumbnail is the only rendered raster there is.
 */
internal fun imageUrlAdvice(url: String): String? {
    val notes = listOfNotNull(undecodableFormatAdvice(url), thumbnailWidthAdvice(url))
    return notes.joinToString("\n").takeIf { it.isNotEmpty() }
}

/**
 * The thumbnail is the answer for a vector or a non-web raster, not the problem — the reverse of
 * every other line of advice here.
 *
 * Only the `.svg` rewrite is spelled out as a URL. Wikimedia does render `.tif` and `.webm` under
 * `/thumb/` too, but with prefixes of their own (`lossy-page1-`, a frame marker), and an address
 * invented here that 404s is worse than a sentence pointing at the file page.
 */
private fun undecodableFormatAdvice(url: String): String? {
    val source = url.sourceFileName() ?: return null
    val extension = source.substringAfterLast('.', "").lowercase()
    if (extension !in UNDECODABLE_EXTENSIONS) return null
    // Already a thumbnail: the width rule below has whatever is left to say about it.
    if (url.contains(THUMB_SEGMENT) && url.wikimediaThumbWidth() != null) return null

    val rewrite = url.asWikimediaSvgThumbnail()
    return "$url\n" +
        "  .$extension is not something either app can decode — Android's loader ships no SVG " +
        "decoder and iOS decodes neither vectors nor these, so this card's picture will be " +
        "blank on both.\n" +
        if (rewrite != null) {
            "  Wikimedia renders a raster thumbnail of it, and for a vector that is the only " +
                "raster there is — the /thumb/ advice below is inverted here. Use:\n  $rewrite"
        } else {
            "  Use an address that serves a JPEG, PNG or WebP."
        }
}

/**
 * The same URL as a `500px` PNG thumbnail, when it is a Wikimedia SVG original.
 *
 * The shape is fixed: `/wikipedia/<project>/<a>/<ab>/Name.svg` becomes
 * `/wikipedia/<project>/thumb/<a>/<ab>/Name.svg/500px-Name.svg.png`. Null for anything else,
 * including a URL that already has a `/thumb/` segment.
 */
private fun String.asWikimediaSvgThumbnail(): String? {
    if (!contains(WIKIMEDIA_UPLOAD_HOST, ignoreCase = true)) return null
    if (contains(THUMB_SEGMENT)) return null
    val name = substringAfterLast('/')
    if (!name.endsWith(SVG_EXTENSION, ignoreCase = true)) return null
    val directory = substringBeforeLast('/')
    // The two hash directories the file sits in; `thumb/` goes in front of them, not of the file.
    val hashes = directory.split('/').takeLast(WIKIMEDIA_HASH_SEGMENTS)
    if (hashes.size != WIKIMEDIA_HASH_SEGMENTS) return null
    val project = directory.dropLast(hashes.joinToString("/").length + 1)
    return "$project/thumb/${hashes.joinToString("/")}/$name/" +
        "${WIKIMEDIA_DEFAULT_THUMB_WIDTH}px-$name.png"
}

/**
 * The file the address is *of*: the last segment for an original, and for a thumbnail the segment
 * before the `NNNpx-` one, which is the source file `/thumb/` was asked to render.
 *
 * The distinction is the whole of the SVG case. `.../thumb/0/03/Flag.svg/500px-Flag.svg.png` ends
 * in `.png` and is a picture; what decides whether dropping `/thumb/` would help is the `.svg` in
 * the middle.
 */
private fun String.sourceFileName(): String? {
    val segments = substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
    if (segments.size < 2) return null
    val last = segments.last()
    return if (contains(THUMB_SEGMENT) && PX_MARKER in last) segments[segments.size - 2] else last
}

/**
 * A note about [url]'s thumbnail width, or null.
 *
 * The original file is always served, which is why that is what the advice names for a raster: it
 * is the answer that cannot go stale. For a vector it is the wrong answer, so the line about
 * dropping `/thumb/` is withheld and only the width list is offered.
 */
private fun thumbnailWidthAdvice(url: String): String? {
    if (!url.contains(WIKIMEDIA_UPLOAD_HOST, ignoreCase = true)) return null
    if (!url.contains(THUMB_SEGMENT)) return null
    val width = url.wikimediaThumbWidth() ?: return null
    if (width in WIKIMEDIA_THUMB_WIDTHS) return null
    val rendersAVector = url.sourceFileName()?.endsWith(SVG_EXTENSION, ignoreCase = true) == true
    return "$url\n" +
        "  Wikimedia serves thumbnails only at ${WIKIMEDIA_THUMB_WIDTHS.joinToString(", ")} px " +
        "and answers 400 for ${width}px, so this card's picture will be blank on both apps.\n" +
        if (rendersAVector) {
            "  Use one of those widths. Do not drop the /thumb/ segment here — the original is an " +
                "SVG, which neither app decodes."
        } else {
            "  Use one of those widths, or drop the /thumb/ segment and the NNNpx- prefix to get " +
                "the full-size original, which is always served."
        }
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

/**
 * What the two clients cannot turn into a picture, by extension.
 *
 * Every one of these was a real Wikipedia lead image in one 855-card deck (#229): flags and colour
 * swatches are `.svg`, and `Dente`, `Piede`, `Tostapane`, `Rotonda` and `Tastiera` resolve to
 * `.stl`, `.tiff`, `.webm` and `.ogv`. Extensions only — a host serving a PNG from a URL ending
 * `.php` is not knowable from the string, and `--check-images` is the tool for that.
 */
private val UNDECODABLE_EXTENSIONS = setOf("svg", "tif", "tiff", "webm", "ogv", "ogg", "stl", "pdf", "djvu", "xcf")

/** What a rewritten SVG thumbnail asks for. In [WIKIMEDIA_THUMB_WIDTHS], and a card-sized one. */
private const val WIKIMEDIA_DEFAULT_THUMB_WIDTH = 500

/** `commons/9/9a/` — the two hash directories every upload sits under. */
private const val WIKIMEDIA_HASH_SEGMENTS = 2

private const val WIKIMEDIA_UPLOAD_HOST = "upload.wikimedia.org"
private const val THUMB_SEGMENT = "/thumb/"
private const val PX_MARKER = "px-"
private const val SVG_EXTENSION = ".svg"
private const val IMAGE_URL_EXCERPT = 60
