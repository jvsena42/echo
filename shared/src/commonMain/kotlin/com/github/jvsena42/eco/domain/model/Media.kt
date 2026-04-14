package com.github.jvsena42.eco.domain.model

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
    ) : MediaRef()

    data class Audio(
        override val path: String,
        override val mime: String,
        override val sha256: String,
        val durationMs: Long?,
    ) : MediaRef()
}
