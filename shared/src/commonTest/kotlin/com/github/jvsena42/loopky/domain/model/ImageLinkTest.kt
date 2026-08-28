package com.github.jvsena42.loopky.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageLinkTest {

    @Test
    fun anOrdinarySearchTermIsNotALink() {
        assertNull(ImageLink.parse("golden retriever"))
    }

    @Test
    fun blankTextIsNotALink() {
        assertNull(ImageLink.parse("   "))
    }

    @Test
    fun aBareFilenameIsASearchTermNotALink() {
        // No scheme, so it stays a query: a link built out of this could never load.
        assertNull(ImageLink.parse("dogs.jpg"))
    }

    @Test
    fun anHttpsUrlIsARemoteLink() {
        assertEquals(
            ImageLink.Remote("https://example.com/cat.jpg"),
            ImageLink.parse("https://example.com/cat.jpg"),
        )
    }

    @Test
    fun surroundingWhitespaceFromAPasteIsTrimmed() {
        assertEquals(
            ImageLink.Remote("https://example.com/cat.jpg"),
            ImageLink.parse("  https://example.com/cat.jpg\n"),
        )
    }

    @Test
    fun aUrlWithNoExtensionIsStillALink() {
        // Unsplash and most CDNs serve images off extensionless paths.
        assertEquals(
            ImageLink.Remote("https://images.example.com/photo-1234?w=800"),
            ImageLink.parse("https://images.example.com/photo-1234?w=800"),
        )
    }

    @Test
    fun textWithASpaceIsNeverAUrl() {
        assertNull(ImageLink.parse("https://example.com/cat.jpg and more"))
    }

    @Test
    fun aGoogleImagesViewerLinkUnwrapsToTheImageItself() {
        val pasted = "https://www.google.com/imgres?imgurl=https%3A%2F%2Fexample.com%2Fa%20cat.jpg" +
            "&imgrefurl=https%3A%2F%2Fexample.com%2Fpage&tbnid=abc"

        // The space stays spelled `%20`: decoding it would produce a string that is not a URL.
        assertEquals(ImageLink.Remote("https://example.com/a%20cat.jpg"), ImageLink.parse(pasted))
    }

    @Test
    fun anImgresLinkWithNothingUsableIsLeftAsPasted() {
        val pasted = "https://www.google.com/imgres?imgurl=&tbnid=abc"

        assertEquals(ImageLink.Remote(pasted), ImageLink.parse(pasted))
    }

    @Test
    fun anOrdinaryUrlKeepsItsOwnQueryParams() {
        // `q=` is a resize/quality param on plenty of image CDNs — nothing to unwrap.
        val url = "https://cdn.example.com/cat.jpg?q=80&url=thumb"

        assertEquals(ImageLink.Remote(url), ImageLink.parse(url))
    }

    @Test
    fun aBase64DataUriDecodesToItsBytes() {
        val link = ImageLink.parse("data:image/png;base64,aGVsbG8=")

        assertEquals(ImageLink.Inline("hello".encodeToByteArray(), "image/png"), link)
    }

    @Test
    fun aDataUriBrokenAcrossLinesStillDecodes() {
        val link = ImageLink.parse("data:image/jpeg;base64,aGVs\nbG8=")

        assertEquals(ImageLink.Inline("hello".encodeToByteArray(), "image/jpeg"), link)
    }

    @Test
    fun aNonImageDataUriIsRefused() {
        assertNull(ImageLink.parse("data:text/plain;base64,aGVsbG8="))
    }

    @Test
    fun aPercentEncodedDataUriIsRefusedRatherThanGuessedAt() {
        assertNull(ImageLink.parse("data:image/svg+xml,%3Csvg%2F%3E"))
    }

    @Test
    fun anEmptyDataPayloadIsNotAnImage() {
        assertNull(ImageLink.parse("data:image/png;base64,"))
    }

    @Test
    fun inlineBytesCompareByContent() {
        val one = ImageLink.Inline(byteArrayOf(1, 2, 3), "image/png")
        val other = ImageLink.Inline(byteArrayOf(1, 2, 3), "image/png")

        assertEquals(one, other)
        assertEquals(one.hashCode(), other.hashCode())
        assertTrue(one != ImageLink.Inline(byteArrayOf(1, 2), "image/png"))
    }
}
