package com.github.jvsena42.loopky.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.math.roundToInt

/**
 * [MediaProcessor] backed by `UIImage`, mirroring [AndroidMediaProcessor]: downscale so the longest
 * edge fits `maxDimension`, then re-encode as JPEG. Runs off the main thread.
 *
 * Two differences from the Android implementation, both forced by the platform. There is no
 * `inSampleSize` equivalent that is worth the ceremony here, so a very large image is decoded at
 * full size before being scaled — acceptable because callers hand over one blob at a time
 * (`ApkgReader.readNotes` takes `compressImage` as a parameter for exactly that reason). And the
 * renderer's scale is pinned to 1, because `UIGraphicsImageRenderer` otherwise draws at the
 * device's scale factor and a "1024px" image comes out 3072px on a 3x screen.
 */
@OptIn(ExperimentalForeignApi::class)
class IosMediaProcessor : MediaProcessor {

    override suspend fun compressImage(
        bytes: ByteArray,
        maxDimension: Int,
        quality: Int,
    ): ProcessedImage = withContext(Dispatchers.Default) {
        val source = UIImage(data = bytes.toNSData()) ?: error("Could not decode image")
        val scaled = source.downscaled(maxDimension)

        val jpeg = UIImageJPEGRepresentation(scaled, quality.coerceIn(1, 100) / 100.0)
            ?: error("Could not encode image as JPEG")

        ProcessedImage(
            bytes = jpeg.toByteArray(),
            mime = "image/jpeg",
            width = scaled.size.useContents { width }.roundToInt(),
            height = scaled.size.useContents { height }.roundToInt(),
        )
    }

    private fun UIImage.downscaled(maxDimension: Int): UIImage {
        val width = size.useContents { width }
        val height = size.useContents { height }
        val longest = maxOf(width, height)
        if (longest <= maxDimension) return this

        val ratio = maxDimension / longest
        val target = CGSizeMake(
            (width * ratio).coerceAtLeast(1.0),
            (height * ratio).coerceAtLeast(1.0),
        )
        // scale = 1: the renderer defaults to the screen's scale factor, which would multiply the
        // pixel dimensions we just computed by 2 or 3.
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply { scale = 1.0 }
        val renderer = UIGraphicsImageRenderer(size = target, format = format)
        return renderer.imageWithActions { drawInRect(CGRectMake(0.0, 0.0, target.useContents { width }, target.useContents { height })) }
    }
}
