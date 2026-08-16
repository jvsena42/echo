package com.github.jvsena42.loopky.domain.model

sealed class MediaRef {
    abstract val path: String
    abstract val mime: String
    abstract val sha256: String

    /**
     * Absolute `pubky://{author}/pub/loopky/decks/{deckId}/media/{sha}.{ext}` when the blob lives
     * under *another* deck — what makes cloning a media-heavy deck instant instead of re-uploading
     * hundreds of MB (#33 blocker 2). Null means the blob is under this deck's own path.
     *
     * Content addressing by sha256 is what makes this safe to resolve lazily: re-hosting the blob
     * later under the clone's own path yields the same digest, so the swap is invisible.
     */
    abstract val uri: String?

    data class Image(
        override val path: String,
        override val mime: String,
        override val sha256: String,
        val width: Int?,
        val height: Int?,
        override val uri: String? = null,
        /**
         * When set, this image is a remote web image referenced by URL (e.g. an Unsplash
         * photo) rather than a blob stored on the homeserver. For remote images [path] and
         * [sha256] are empty and no blob is uploaded.
         */
        val url: String? = null,
    ) : MediaRef() {
        val isRemote: Boolean get() = url != null
    }

    data class Audio(
        override val path: String,
        override val mime: String,
        override val sha256: String,
        val durationMs: Long?,
        override val uri: String? = null,
    ) : MediaRef()
}
