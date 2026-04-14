package com.github.jvsena42.eco.data.repository.impl

import com.github.jvsena42.eco.data.pubky.PubkyClient
import com.github.jvsena42.eco.data.pubky.PubkyPaths
import com.github.jvsena42.eco.data.pubky.SessionProvider
import com.github.jvsena42.eco.data.pubky.requireSession
import com.github.jvsena42.eco.data.pubky.sha256Hex
import com.github.jvsena42.eco.data.repository.MediaRepository
import com.github.jvsena42.eco.domain.model.MediaRef
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Blob storage for card media. Blobs are stored at
 * `/pub/echo/decks/{deckId}/media/{sha256}.{ext}` keyed by content hash — identical bytes
 * within a deck dedupe automatically.
 *
 * The Pubky FFI `put` surface takes a `String`, so binary content is Base64-encoded on the
 * wire. Callers receive a typed [MediaRef] that carries the relative path the card record
 * should embed.
 */
@OptIn(ExperimentalEncodingApi::class)
class MediaRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
) : MediaRepository {

    override suspend fun putImage(
        deckId: String,
        bytes: ByteArray,
        mime: String,
    ): Result<MediaRef.Image> = runCatching {
        val (sha, ext) = putBlob(deckId, bytes, mime)
        MediaRef.Image(
            path = PubkyPaths.relativeMedia(sha, ext),
            mime = mime,
            sha256 = sha,
            width = null,
            height = null,
        )
    }

    override suspend fun putAudio(
        deckId: String,
        bytes: ByteArray,
        mime: String,
    ): Result<MediaRef.Audio> = runCatching {
        val (sha, ext) = putBlob(deckId, bytes, mime)
        MediaRef.Audio(
            path = PubkyPaths.relativeMedia(sha, ext),
            mime = mime,
            sha256 = sha,
            durationMs = null,
        )
    }

    override suspend fun get(deckId: String, ref: MediaRef): Result<ByteArray> = runCatching {
        val author = session.requireSession().identity.pubky
        val ext = ref.path.substringAfterLast('.', missingDelimiterValue = "")
        val url = PubkyPaths.media(author, deckId, ref.sha256, ext)
        val payload = pubky.get(url).getOrThrow()
        Base64.decode(payload)
    }

    override suspend fun delete(deckId: String, ref: MediaRef): Result<Unit> = runCatching {
        val s = session.requireSession()
        val ext = ref.path.substringAfterLast('.', missingDelimiterValue = "")
        val url = PubkyPaths.media(s.identity.pubky, deckId, ref.sha256, ext)
        pubky.deleteWithSession(url, s.sessionSecret).getOrThrow()
        Unit
    }

    private suspend fun putBlob(
        deckId: String,
        bytes: ByteArray,
        mime: String,
    ): Pair<String, String> {
        val s = session.requireSession()
        val sha = sha256Hex(bytes)
        val ext = mimeToExt(mime)
        val url = PubkyPaths.media(s.identity.pubky, deckId, sha, ext)
        val body = Base64.encode(bytes)
        pubky.putWithSession(url, body, s.sessionSecret).getOrThrow()
        return sha to ext
    }

    private fun mimeToExt(mime: String): String = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/ogg" -> "ogg"
        "audio/wav", "audio/x-wav" -> "wav"
        else -> mime.substringAfter('/', "bin")
    }
}
