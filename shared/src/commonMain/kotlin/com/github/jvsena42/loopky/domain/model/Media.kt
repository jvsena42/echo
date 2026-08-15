package com.github.jvsena42.loopky.domain.model

sealed class MediaRef {
    abstract val path: String
    abstract val mime: String
    abstract val sha256: String

    data class Image(
        override val path: String,
        override val mime: String,
        override val sha256: String,
        val width: Int?,
        val height: Int?,
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
    ) : MediaRef()
}
