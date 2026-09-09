package com.raulshma.lenscast.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * The one JPEG downscale ladder, shared by every consumer that re-encodes a
 * JPEG smaller: the detection-event snapshot encoder and the gallery photo
 * thumbnail. Decode at the largest power-of-two sample that keeps the source's
 * longest side at or under the target, encode, and — while the encoding still
 * misses the caller's size gate — halve the size and retry. The gate decides
 * the final size, so a busy frame ships a smaller image instead of no image.
 * Null when the input is missing or undecodable: callers log fine without the
 * bytes, they never fail for them.
 */
internal object JpegDownscaler {

    /** The ladder's floor: at or under a 1/32 decode any reasonable gate passes, so stop. */
    const val MAX_SAMPLE = 32

    /**
     * Pure: the largest power-of-two `inSampleSize` whose next doubling would
     * drop the longest source side below [targetMaxPx] — i.e. the smallest
     * power-of-two subsample that still reaches the target.
     */
    fun sampleSizeFor(srcWidth: Int, srcHeight: Int, targetMaxPx: Int): Int {
        var sample = 1
        val maxDim = maxOf(srcWidth, srcHeight)
        while (maxDim / (sample * 2) >= targetMaxPx) {
            sample *= 2
        }
        return sample
    }

    /**
     * The ladder: decode → encode → gate, halving on a failed gate. [accepts]
     * is the caller's size/quality verdict over the encoded bytes; the default
     * accepts the first encode. Null when [jpeg] is null/empty, undecodable,
     * or every decode up the ladder fails.
     */
    fun downscale(
        jpeg: ByteArray?,
        targetMaxPx: Int,
        quality: Int,
        accepts: (ByteArray) -> Boolean = { true },
    ): ByteArray? {
        if (jpeg == null || jpeg.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetMaxPx)
            var bytes: ByteArray? = null
            ladder@ while (sample <= MAX_SAMPLE) {
                val decoded = BitmapFactory.decodeByteArray(
                    jpeg,
                    0,
                    jpeg.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: break
                val output = ByteArrayOutputStream()
                decoded.compress(Bitmap.CompressFormat.JPEG, quality, output)
                decoded.recycle()
                bytes = output.toByteArray()
                if (accepts(bytes)) break@ladder
                sample *= 2
            }
            bytes
        } catch (_: Exception) {
            null
        }
    }
}
