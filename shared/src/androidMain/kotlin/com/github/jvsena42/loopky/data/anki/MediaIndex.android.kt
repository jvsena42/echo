package com.github.jvsena42.loopky.data.anki

import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.util.Log
import org.json.JSONObject
import java.util.zip.ZipFile

/**
 * The archive's pictures, resolved by the filename an `<img src=…>` names.
 *
 * An `.apkg` does not store media under its own name — blobs are zip entries numbered `0`, `1`, `2`
 * and a `media` entry holds the JSON map from those numbers back to filenames. So a card asking for
 * `dog.jpg` needs that map read backwards.
 *
 * Blobs are pulled one at a time, on demand, and compressed before the next is touched: a deck can
 * carry hundreds of megabytes of media and only a handful of cards are ever image-only.
 */
internal class MediaIndex(private val zip: ZipFile) {

    private val entriesByName: Map<String, String> by lazy { readManifest() }
    private val cache = mutableMapOf<String, DraftCardImage>()

    /** Distinct blobs pulled out of the archive and compressed. */
    var imported = 0
        private set

    /** Pictures left behind at [MAX_IMPORT_IMAGES], so the summary can say so rather than not. */
    var skipped = 0
        private set

    /**
     * The pictures for this note's two mapped sides, or null if one is named but not in the file.
     *
     * A named-but-absent blob fails the note rather than silently dropping to a text-less side:
     * the field held nothing but that picture, so there is no card left to make.
     */
    suspend fun resolve(
        fields: List<AnkiField>,
        mapping: ApkgFieldMapping,
        compressImage: suspend (ByteArray, String) -> DraftCardImage,
    ): Map<Int, DraftCardImage>? {
        val wanted = listOf(mapping.frontOrd, mapping.backOrd)
            .mapNotNull { ord -> fields.getOrNull(ord)?.imageSrc?.let { ord to it } }
        if (wanted.isEmpty()) return emptyMap()

        val resolved = mutableMapOf<Int, DraftCardImage>()
        wanted.forEach { (ord, src) ->
            // Past the cap the picture is left behind, not the card: a side that was only a
            // picture then has nothing left and is counted as dropped, which is the honest report.
            if (src !in cache && imported >= MAX_IMPORT_IMAGES) {
                skipped++
                return@forEach
            }
            val image = load(src, compressImage) ?: return null
            resolved[ord] = image
        }
        return resolved
    }

    private suspend fun load(
        src: String,
        compressImage: suspend (ByteArray, String) -> DraftCardImage,
    ): DraftCardImage? {
        cache[src]?.let { return it }
        val entry = entriesByName[src]?.let(zip::getEntry) ?: return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        if (bytes.isEmpty()) return null
        val image = compressImage(bytes, src.mimeFromExtension())
        imported++
        cache[src] = image
        return image
    }

    /**
     * `{"0": "dog.jpg", "1": "cat.png"}` — read backwards, since a card names the file and needs
     * the entry.
     */
    private fun readManifest(): Map<String, String> = runCatching {
        val entry = zip.getEntry(MEDIA_ENTRY) ?: return emptyMap()
        val json = JSONObject(zip.getInputStream(entry).use { it.readBytes() }.decodeToString())
        buildMap {
            json.keys().forEach { key -> put(json.getString(key), key) }
        }
    }.onFailure {
        // A media manifest this reader cannot parse costs the deck its pictures, not its cards.
        Log.d(TAG, "apkg: unreadable media manifest — ${it.message}")
    }.getOrDefault(emptyMap())

    private companion object {
        const val TAG = "Loopky/ApkgReader"
        const val MEDIA_ENTRY = "media"

        /**
         * Ceiling on pictures imported from one deck.
         *
         * Every image is a blob uploaded against a 1 GB homeserver quota with no way to check it
         * beforehand (Architecture.md §8.5), so an unbounded import could spend someone's whole
         * allowance on one deck.
         */
        const val MAX_IMPORT_IMAGES = 500
    }
}

private fun String.mimeFromExtension(): String = when (substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    else -> "image/jpeg"
}
