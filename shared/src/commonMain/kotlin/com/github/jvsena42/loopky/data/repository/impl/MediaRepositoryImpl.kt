package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.deleteWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.putBytesWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.requireSession
import com.github.jvsena42.loopky.data.pubky.sha256Hex
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.PinnedBlob
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Blob storage for card media. Blobs live at
 * `/pub/loopky/decks/{deckId}/media/{sha256}.{ext}` keyed by content hash — identical bytes within
 * a deck dedupe automatically.
 *
 * Blobs are written raw via the binary FFI surface (`putBytesWithSession`) so other Pubky clients
 * can read them directly; reads come back Base64-encoded from the FFI transport and are decoded
 * here. Note the inflation that implies: a 5 MB audio file transits as ~6.7 MB of `String` before
 * decoding, which is why large media wants a streaming path (see `docs/Architecture.md §8.4`).
 *
 * Media is fetched **lazily, when a card is shown** — never bulk-prefetched on deck open. An Anki
 * deck with audio runs to hundreds of MB, so prefetching every referenced blob is untenable. A
 * small in-memory LRU keeps the current session's cards from re-downloading on every recomposition.
 */
@OptIn(ExperimentalEncodingApi::class)
class MediaRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val revalidator: SessionRevalidator,
) : MediaRepository {

    /** sha256 → bytes, most-recently-used last. Bounded by [CACHE_ENTRIES]. */
    private val cache = LinkedHashMap<String, ByteArray>()
    private val cacheLock = Mutex()

    /**
     * Replayed rather than fire-and-forget. `extraBufferCapacity` only buffers for subscribers
     * that already exist, and the collector — `DeckRepositoryImpl` — is a lazy Koin `single`, so
     * a blob fetched before anything resolves it would emit into nothing. Replaying a stale entry
     * is harmless: `rehostBlob` is idempotent and short-circuits on what it has already done.
     */
    private val _pinnedFetches = MutableSharedFlow<PinnedBlob>(
        replay = PINNED_REPLAY,
        extraBufferCapacity = PINNED_REPLAY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val pinnedFetches: SharedFlow<PinnedBlob> = _pinnedFetches.asSharedFlow()

    override suspend fun putImage(
        deckId: String,
        bytes: ByteArray,
        mime: String,
    ): Result<MediaRef.Image> = runSuspendCatching {
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
    ): Result<MediaRef.Audio> = runSuspendCatching {
        val (sha, ext) = putBlob(deckId, bytes, mime)
        MediaRef.Audio(
            path = PubkyPaths.relativeMedia(sha, ext),
            mime = mime,
            sha256 = sha,
            durationMs = null,
        )
    }

    override suspend fun get(
        authorPubky: String,
        deckId: String,
        ref: MediaRef,
    ): Result<ByteArray> = runSuspendCatching {
        // An absolute ref points at another author's blob — a clone that has not re-hosted this
        // one yet. Resolving against the session author (as this used to) made every foreign
        // deck's media unreadable, since it looked for the blob under *your* pubky.
        val url = ref.uri ?: PubkyPaths.media(authorPubky, deckId, ref.sha256, ref.ext())
        val bytes = fetch(ref.sha256, url)
        signalIfRehostable(authorPubky, deckId, ref)
        bytes
    }

    /**
     * Flag a blob worth copying under the deck's own path, now that its bytes are in hand (#65).
     *
     * `tryEmit` rather than `emit`: the card is already on screen waiting for these bytes, and the
     * copy must never be on that path.
     *
     * Skipped are refs that need no origin — matching what `absolutizedTo` declines to pin: one
     * with no [MediaRef.uri], a remote web image (re-hosting Unsplash bytes would breach their
     * licence), and an empty [MediaRef.sha256], which addresses no blob.
     *
     * The last condition is the load-bearing one. A **followed** deck's blobs must not be copied
     * under your own pubky at a `deckId` you do not own and cannot edit — that would write junk
     * into your namespace for a deck that is not yours.
     */
    private fun signalIfRehostable(authorPubky: String, deckId: String, ref: MediaRef) {
        if (ref.uri == null || ref.sha256.isEmpty()) return
        if ((ref as? MediaRef.Image)?.isRemote == true) return
        if (authorPubky != session.current()?.identity?.pubky) return
        _pinnedFetches.tryEmit(PinnedBlob(deckId, ref.sha256))
    }

    override suspend fun rehost(deckId: String, ref: MediaRef): Result<MediaRef> = runSuspendCatching {
        val origin = ref.uri ?: return@runSuspendCatching ref
        val bytes = fetch(ref.sha256, origin)
        val (sha, ext) = putBlob(deckId, bytes, ref.mime)
        Log.d(TAG, "rehost: copied $origin under deck $deckId")
        // Content-addressed, so the digest is unchanged and every card referencing it stays valid.
        when (ref) {
            is MediaRef.Image -> ref.copy(path = PubkyPaths.relativeMedia(sha, ext), uri = null)
            is MediaRef.Audio -> ref.copy(path = PubkyPaths.relativeMedia(sha, ext), uri = null)
        }
    }

    override suspend fun delete(deckId: String, ref: MediaRef): Result<Unit> = runSuspendCatching {
        val author = session.requireSession().identity.pubky
        val url = PubkyPaths.media(author, deckId, ref.sha256, ref.ext())
        pubky.deleteWithSessionRetry(url, session, revalidator).getOrThrow()
        cacheLock.withLock { cache.remove(ref.sha256) }
        Unit
    }

    /** Blob bytes for [sha], from the LRU if present, otherwise read from [url] and cached. */
    private suspend fun fetch(sha: String, url: String): ByteArray {
        cached(sha)?.let { return it }
        val payload = pubky.getBytes(url).getOrThrow()
        val bytes = Base64.decode(payload)
        putInCache(sha, bytes)
        return bytes
    }

    private suspend fun cached(sha: String): ByteArray? = cacheLock.withLock {
        cache.remove(sha)?.also { cache[sha] = it } // re-insert to mark as most recently used
    }

    private suspend fun putInCache(sha: String, bytes: ByteArray) = cacheLock.withLock {
        cache.remove(sha)
        cache[sha] = bytes
        while (cache.size > CACHE_ENTRIES) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
    }

    private suspend fun putBlob(
        deckId: String,
        bytes: ByteArray,
        mime: String,
    ): Pair<String, String> {
        val author = session.requireSession().identity.pubky
        val sha = sha256Hex(bytes)
        val ext = mimeToExt(mime)
        val url = PubkyPaths.media(author, deckId, sha, ext)
        pubky.putBytesWithSessionRetry(url, bytes, session, revalidator).getOrThrow()
        putInCache(sha, bytes)
        return sha to ext
    }

    private fun MediaRef.ext(): String =
        path.substringAfterLast('.', missingDelimiterValue = "")

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

    companion object {
        private const val TAG = "Loopky/MediaRepo"

        /**
         * Blobs held in memory. Deliberately small: this exists so a card's image survives
         * recomposition and a back-swipe, not to hold a deck's media. Anki-sized decks have far
         * more media than fits in memory, which is what makes the lazy fetch non-negotiable.
         */
        private const val CACHE_ENTRIES = 32

        /** Pinned-blob signals held for a collector that has not been constructed yet. */
        private const val PINNED_REPLAY = 16
    }
}
