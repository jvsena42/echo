package com.github.jvsena42.echo.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * [MediaProcessor] backed by Android [Bitmap]. Decodes with subsampling to keep memory bounded,
 * downscales so the longest edge fits [maxDimension], then re-encodes as JPEG. Runs off the main
 * thread.
 */
class AndroidMediaProcessor : MediaProcessor {

    override suspend fun compressImage(
        bytes: ByteArray,
        maxDimension: Int,
        quality: Int,
    ): ProcessedImage = withContext(Dispatchers.Default) {
        // First pass: read bounds only so we can subsample large images during decode.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: error("Could not decode image")

        val scaled = downscale(decoded, maxDimension)
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), output)
        val result = ProcessedImage(
            bytes = output.toByteArray(),
            mime = "image/jpeg",
            width = scaled.width,
            height = scaled.height,
        )
        if (scaled !== decoded) decoded.recycle()
        scaled.recycle()
        result
    }

    private fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / sample > maxDimension * 2) sample *= 2
        return sample
    }

    private fun downscale(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest
        val w = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
