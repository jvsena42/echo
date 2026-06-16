package com.github.jvsena42.echo.platform

/** Raw bytes + mime of an image picked from the device gallery. */
data class PickedImage(
    val bytes: ByteArray,
    val mime: String,
)

/** A compressed, downscaled image ready to upload to the homeserver. */
data class ProcessedImage(
    val bytes: ByteArray,
    val mime: String,
    val width: Int,
    val height: Int,
)

/**
 * Downscales and re-encodes picked images before they are uploaded as media blobs. Pure (no
 * Activity/lifecycle), so it can be a Koin singleton — the actual pick happens in the Compose
 * layer via the system photo picker. Implemented per platform (Android Bitmap / iOS UIImage).
 */
interface MediaProcessor {
    /** Decode [bytes], downscale to fit [maxDimension] px, re-encode as JPEG at [quality]. */
    suspend fun compressImage(
        bytes: ByteArray,
        maxDimension: Int = DEFAULT_MAX_DIMENSION,
        quality: Int = DEFAULT_QUALITY,
    ): ProcessedImage

    companion object {
        const val DEFAULT_MAX_DIMENSION = 1024
        const val DEFAULT_QUALITY = 80
    }
}
