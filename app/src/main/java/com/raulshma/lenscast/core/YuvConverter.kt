package com.raulshma.lenscast.core

import java.nio.ByteBuffer

/**
 * Pure YUV_420_888 → NV21 conversion, extracted from CameraService so it can
 * be unit-tested on the JVM (the same profile as H264NalParser). The caller
 * supplies the plane buffers and strides; this module owns the pixel logic.
 */
object YuvConverter {

    fun yuvToNv21(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        yRowStride: Int,
        yPixelStride: Int,
        uRowStride: Int,
        uPixelStride: Int,
        vRowStride: Int,
        vPixelStride: Int,
        width: Int,
        height: Int,
        cropLeft: Int,
        cropTop: Int,
    ): ByteArray? {
        if (width <= 0 || height <= 0) return null

        val ySize = width * height
        val uvSize = ySize / 2
        val nv21 = ByteArray(ySize + uvSize)

        // Copy Y plane — use bulk get when pixelStride==1 for ~4x speedup
        val y = yBuffer.duplicate()
        if (yPixelStride == 1) {
            var yOut = 0
            for (row in 0 until height) {
                y.position((row + cropTop) * yRowStride + cropLeft)
                y.get(nv21, yOut, width)
                yOut += width
            }
        } else {
            var yOut = 0
            for (row in 0 until height) {
                var srcIndex = (row + cropTop) * yRowStride + cropLeft * yPixelStride
                for (col in 0 until width) {
                    nv21[yOut++] = y.get(srcIndex)
                    srcIndex += yPixelStride
                }
            }
        }

        // Copy UV planes interleaved as VU (NV21)
        val u = uBuffer.duplicate()
        val v = vBuffer.duplicate()
        val uvWidth = width / 2
        val uvHeight = height / 2
        val uvCropTop = cropTop / 2
        val uvCropLeft = cropLeft / 2
        var uvPos = ySize

        // Fast path: interleaved NV12/NV21 with pixelStride==2 and contiguous VU layout
        if (vPixelStride == 2 && uPixelStride == 2 && vRowStride == uRowStride
            && v.capacity() > 0
        ) {
            val vStart = vBuffer.position()
            val uStart = uBuffer.position()
            // NV21: VU interleaved, V plane starts 1 byte before U
            if (uStart - vStart == 1) {
                // The raw buffer IS NV21 interleaved — bulk copy per row
                for (row in 0 until uvHeight) {
                    val rowOffset = (row + uvCropTop) * vRowStride + uvCropLeft * vPixelStride
                    v.position(rowOffset)
                    v.get(nv21, uvPos, uvWidth * 2)
                    uvPos += uvWidth * 2
                }
            } else {
                // Per-pixel interleave
                for (row in 0 until uvHeight) {
                    var uIndex = (row + uvCropTop) * uRowStride + uvCropLeft * uPixelStride
                    var vIndex = (row + uvCropTop) * vRowStride + uvCropLeft * vPixelStride
                    for (col in 0 until uvWidth) {
                        nv21[uvPos++] = v.get(vIndex)
                        nv21[uvPos++] = u.get(uIndex)
                        uIndex += uPixelStride
                        vIndex += vPixelStride
                    }
                }
            }
        } else {
            // Generic slow path (planar or odd strides)
            for (row in 0 until uvHeight) {
                var uIndex = (row + uvCropTop) * uRowStride + uvCropLeft * uPixelStride
                var vIndex = (row + uvCropTop) * vRowStride + uvCropLeft * vPixelStride
                for (col in 0 until uvWidth) {
                    nv21[uvPos++] = v.get(vIndex)
                    nv21[uvPos++] = u.get(uIndex)
                    uIndex += uPixelStride
                    vIndex += vPixelStride
                }
            }
        }

        return nv21
    }
    /**
     * NV21 rotation for the encode paths (90/180/270). Moved here from the
     * RTSP transport so all pixel logic lives in one testable module.
     */
    fun rotateNv21(src: ByteArray, width: Int, height: Int, rotation: Int): ByteArray {
        return when (rotation) {
            180 -> rotateNv21_180(src, width, height)
            90 -> rotateNv21_90(src, width, height)
            270 -> rotateNv21_270(src, width, height)
            else -> src
        }
    }

    private fun rotateNv21_90(src: ByteArray, width: Int, height: Int): ByteArray {
        val dstW = height
        val dstH = width
        val ySize = width * height
        val dst = ByteArray(dstW * dstH * 3 / 2)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val dstX = dstW - 1 - y
                val dstY = x
                dst[dstY * dstW + dstX] = src[y * width + x]
            }
        }

        val dstUvStart = ySize
        for (y in 0 until height / 2) {
            for (x in 0 until width / 2) {
                val srcVIdx = ySize + y * width + x * 2
                val srcUIdx = ySize + y * width + x * 2 + 1
                val dstX = dstW / 2 - 1 - y
                val dstY = x
                val dstIdx = dstUvStart + dstY * dstW + dstX * 2
                dst[dstIdx] = src[srcVIdx]
                dst[dstIdx + 1] = src[srcUIdx]
            }
        }

        return dst
    }

    private fun rotateNv21_270(src: ByteArray, width: Int, height: Int): ByteArray {
        val dstW = height
        val dstH = width
        val ySize = width * height
        val dst = ByteArray(dstW * dstH * 3 / 2)

        for (y in 0 until height) {
            for (x in 0 until width) {
                dst[(width - 1 - x) * dstW + y] = src[y * width + x]
            }
        }

        for (y in 0 until height / 2) {
            for (x in 0 until width / 2) {
                val srcVIdx = ySize + y * width + x * 2
                val srcUIdx = ySize + y * width + x * 2 + 1
                val dstX = y
                val dstY = width / 2 - 1 - x
                val dstIdx = ySize + dstY * dstW + dstX * 2
                dst[dstIdx] = src[srcVIdx]
                dst[dstIdx + 1] = src[srcUIdx]
            }
        }

        return dst
    }

    private fun rotateNv21_180(src: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val dst = ByteArray(ySize * 3 / 2)

        for (i in 0 until ySize) {
            dst[i] = src[ySize - 1 - i]
        }

        val uvSize = ySize / 2
        for (i in 0 until uvSize step 2) {
            dst[ySize + i] = src[ySize + uvSize - 2 - i]
            dst[ySize + i + 1] = src[ySize + uvSize - 1 - i]
        }

        return dst
    }

    /**
     * NV21 aspect-fill rescale for the encode paths: scales [src] so it
     * covers the [outWidth]x[outHeight] target entirely (the larger per-axis
     * scale factor wins) and center-crops the overflow on the other axis, so
     * an encoder configured at a fixed size receives exactly that size no
     * matter what analysis resolution the camera produces. Nearest-neighbor
     * sampling; both dimensions must be even (NV21 chroma is 2x2 subsampled —
     * every encode-path size is). Returns [src] unchanged when the sizes
     * already match or a dimension is degenerate.
     */
    fun scaleNv21(src: ByteArray, width: Int, height: Int, outWidth: Int, outHeight: Int): ByteArray {
        if (width == outWidth && height == outHeight) return src
        if (width <= 0 || height <= 0 || outWidth <= 0 || outHeight <= 0 ||
            width % 2 != 0 || height % 2 != 0 || outWidth % 2 != 0 || outHeight % 2 != 0
        ) {
            return src
        }

        val out = ByteArray(outWidth * outHeight * 3 / 2)

        // The visible source window: scale-to-cover, centered. The binding
        // axis is the one with the larger target/source ratio; the other axis
        // keeps a narrower window that center-crops away.
        val bindsX = outWidth.toLong() * height >= outHeight.toLong() * width
        val winW = if (bindsX) width else outWidth * height / outHeight
        val winH = if (bindsX) outHeight * width / outWidth else height
        val winLeft = (width - winW) / 2
        val winTop = (height - winH) / 2

        for (outY in 0 until outHeight) {
            val srcRow = (winTop + outY * winH / outHeight) * width + winLeft
            val dstRow = outY * outWidth
            for (outX in 0 until outWidth) {
                out[dstRow + outX] = src[srcRow + outX * winW / outWidth]
            }
        }

        // Chroma covers the luma window on its own halved lattice — derived
        // FROM the luma window, never recomputed independently, so the color
        // planes stay registered with the luma crop.
        val cwOut = outWidth / 2
        val chOut = outHeight / 2
        val cLeft = winLeft / 2
        val cTop = winTop / 2
        val cW = (winLeft + winW + 1) / 2 - cLeft
        val cH = (winTop + winH + 1) / 2 - cTop

        val srcUvStart = width * height
        val dstUvStart = outWidth * outHeight
        for (cy in 0 until chOut) {
            val srcCY = cTop + cy * cH / chOut
            val dstRow = dstUvStart + cy * outWidth
            for (cx in 0 until cwOut) {
                val srcIdx = srcUvStart + srcCY * width + (cLeft + cx * cW / cwOut) * 2
                val dstIdx = dstRow + cx * 2
                out[dstIdx] = src[srcIdx]
                out[dstIdx + 1] = src[srcIdx + 1]
            }
        }

        return out
    }

    /**
     * NV21 → NV12: reorders the interleaved UV plane from VU to UV on a copy.
     * Moved here from H264Encoder — the encode-path pixel swaps live with the
     * rest of the YUV knowledge.
     */
    fun nv21ToNv12(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val nv12 = nv21.copyOf()

        val uvStart = ySize
        for (i in 0 until width * height / 2 step 2) {
            val v = nv21[uvStart + i]
            val u = nv21[uvStart + i + 1]
            nv12[uvStart + i] = u
            nv12[uvStart + i + 1] = v
        }

        return nv12
    }

    /** NV21 → I420: deinterleaves the VU pairs into separate U and V planes. */
    fun nv21ToI420(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val uvPlaneSize = ySize / 4
        val i420 = ByteArray(ySize + uvPlaneSize * 2)

        // Y plane
        System.arraycopy(nv21, 0, i420, 0, ySize)

        // U and V planar
        var src = ySize
        var uDst = ySize
        var vDst = ySize + uvPlaneSize
        while (src + 1 < nv21.size) {
            val v = nv21[src]
            val u = nv21[src + 1]
            i420[uDst++] = u
            i420[vDst++] = v
            src += 2
        }

        return i420
    }
}
