package com.github.jvsena42.loopky.domain.model

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * An image the reader brought themselves: a link typed or pasted into the image sheet's field,
 * rather than a photo picked out of the Unsplash grid.
 *
 * The sheet's one text field is both the search box and the link box, decided by what is in it —
 * [parse] is that decision. A second field would have been a second thing to explain, and the
 * paste that motivates this ("copy image address" on a search result) lands in whichever field is
 * in front of the user, so both had to accept it anyway.
 *
 * Two shapes come back because a copied image arrives as either of two things. "Copy image
 * address" gives a URL, which is stored as a remote ref exactly like an Unsplash pick; a thumbnail
 * on a results page is often a `data:` URI instead, which is not fetchable later and so has to be
 * decoded here and uploaded as a blob.
 */
sealed interface ImageLink {

    /** An `http(s)` image URL, saved as-is — no blob, same as an Unsplash pick. */
    data class Remote(val url: String) : ImageLink

    /**
     * The decoded bytes of a `data:` URI. These go through the same compression the gallery picker
     * applies and are uploaded as a blob: the URI itself is the image, so there is nothing to
     * point a stored ref at.
     */
    class Inline(val bytes: ByteArray, val mime: String) : ImageLink {
        override fun equals(other: Any?): Boolean =
            other is Inline && mime == other.mime && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + mime.hashCode()

        override fun toString(): String = "Inline(mime=$mime, bytes=${bytes.size})"
    }

    companion object {
        /**
         * The link in [text], or `null` when it is an ordinary search term.
         *
         * A scheme is required. Accepting a bare `dogs.jpg` would turn a plausible search into a
         * link that can never load, and the field has to keep working as a search box first.
         */
        fun parse(text: String): ImageLink? {
            val trimmed = text.trim()
            return when {
                trimmed.startsWith(DATA_PREFIX, ignoreCase = true) -> parseDataUri(trimmed)
                trimmed.isHttpUrl() -> Remote(unwrapImgres(trimmed))
                else -> null
            }
        }

        private fun String.isHttpUrl(): Boolean {
            val scheme = HTTP_SCHEMES.firstOrNull { startsWith(it, ignoreCase = true) } ?: return false
            return length > scheme.length && none { it.isWhitespace() }
        }

        /**
         * The image behind a Google Images viewer link.
         *
         * Copying the address of a search result hands you
         * `https://www.google.com/imgres?imgurl=<the actual image>&imgrefurl=…` — a page, which
         * loads as HTML and never as an image. Unwrapping it is the difference between the paste
         * working and the paste looking broken for no visible reason. Only `imgurl` is unwrapped:
         * it is Google's own parameter name, where a generic `url=` would misread an image proxy's
         * own address.
         */
        private fun unwrapImgres(url: String): String {
            val inner = url.queryParam(IMGURL_PARAM)?.percentDecoded() ?: return url
            return if (inner.isHttpUrl()) inner else url
        }

        private fun String.queryParam(name: String): String? =
            substringAfter('?', "")
                .split('&')
                .firstOrNull { it.startsWith("$name=") }
                ?.substringAfter('=')
                ?.takeIf { it.isNotEmpty() }

        /**
         * `%XX` escapes decoded as UTF-8 bytes, with one exception: an escape standing for a space
         * or a control character is left as written. `%20` decoded is a URL with a space in it,
         * which is not a URL at all — the escape *is* the character's spelling in an address, and
         * only the ones Google added around the address itself (`%3A`, `%2F`) need undoing.
         *
         * `+` is left alone too: in an image URL it is a literal, not a space.
         */
        private fun String.percentDecoded(): String {
            if ('%' !in this) return this
            val out = mutableListOf<Byte>()
            var i = 0
            while (i < length) {
                val code = escapeAt(i)
                when {
                    code == null -> {
                        this[i].toString().encodeToByteArray().forEach(out::add)
                        i++
                    }

                    // Compared as an unsigned code, not a Byte: `%C3` is negative as a Byte, and
                    // half of every accented URL would survive decoding percent-encoded.
                    code <= SPACE_CODE -> {
                        substring(i, i + ESCAPE_LENGTH).encodeToByteArray().forEach(out::add)
                        i += ESCAPE_LENGTH
                    }

                    else -> {
                        out.add(code.toByte())
                        i += ESCAPE_LENGTH
                    }
                }
            }
            return out.toByteArray().decodeToString()
        }

        /** The byte value of the `%XX` escape starting at [index], or null if there isn't one. */
        private fun String.escapeAt(index: Int): Int? {
            if (this[index] != '%' || index + 2 >= length) return null
            val digits = substring(index + 1, index + ESCAPE_LENGTH)
            if (!digits.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
            return digits.toIntOrNull(HEX)
        }

        /**
         * `data:image/png;base64,…` → its bytes. Anything else is refused rather than guessed at:
         * a non-image media type is not an image, and a percent-encoded payload (how inline SVG
         * usually travels) is a vector document the media pipeline cannot compress.
         */
        @OptIn(ExperimentalEncodingApi::class)
        private fun parseDataUri(uri: String): Inline? {
            val comma = uri.indexOf(',')
            if (comma < 0) return null
            val header = uri.substring(DATA_PREFIX.length, comma)
            if (!header.contains(BASE64_MARKER, ignoreCase = true)) return null
            val mime = header.substringBefore(';').trim().lowercase()
            if (!mime.startsWith(IMAGE_MIME_PREFIX)) return null
            // Mime, not Default: a pasted data URI can carry line breaks, and rejecting the whole
            // image over a newline would read as "that image is broken".
            val bytes = runCatching { Base64.Mime.decode(uri, comma + 1) }.getOrNull()
            return bytes?.takeIf { it.isNotEmpty() }?.let { Inline(it, mime) }
        }

        private const val DATA_PREFIX = "data:"
        private const val BASE64_MARKER = ";base64"
        private const val IMAGE_MIME_PREFIX = "image/"
        private const val IMGURL_PARAM = "imgurl"
        private const val HEX = 16

        /** Space, and by extension everything below it — the escapes that must survive decoding. */
        private const val SPACE_CODE = 0x20
        private const val ESCAPE_LENGTH = 3
        private val HTTP_SCHEMES = listOf("https://", "http://")
    }
}
