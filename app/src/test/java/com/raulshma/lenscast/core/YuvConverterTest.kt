package com.raulshma.lenscast.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class YuvConverterTest {

    // Synthetic image: Y filled with a row-dependent pattern, U/V with their
    // own byte values, so any mix-up between planes or strides shows up.

    private fun expectedNv21(width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 3 / 2)
        for (row in 0 until height) {
            for (col in 0 until width) {
                out[row * width + col] = yValue(row, col)
            }
        }
        var uv = width * height
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                out[uv++] = vValue(row, col)
                out[uv++] = uValue(row, col)
            }
        }
        return out
    }

    private fun yValue(row: Int, col: Int) = ((row * 31 + col * 7) and 0xFF).toByte()
    private fun uValue(row: Int, col: Int) = ((row * 5 + col * 13 + 40) and 0xFF).toByte()
    private fun vValue(row: Int, col: Int) = ((row * 3 + col * 11 + 90) and 0xFF).toByte()

    /** Planar YU12 layout: full Y plane, then U plane, then V plane. */
    private fun planarBuffers(width: Int, height: Int): Triple<ByteBuffer, ByteBuffer, ByteBuffer> {
        val y = ByteBuffer.wrap(ByteArray(width * height) { i ->
            yValue(i / width, i % width)
        })
        val uw = width / 2
        val uh = height / 2
        val u = ByteBuffer.wrap(ByteArray(uw * uh) { i ->
            uValue(i / uw, i % uw)
        })
        val v = ByteBuffer.wrap(ByteArray(uw * uh) { i ->
            vValue(i / uw, i % uw)
        })
        return Triple(y, u, v)
    }

    /** NV21 interleaved layout: full Y plane, then VU pairs — pixelStride 2, contiguous VU. */
    private fun interleavedBuffers(width: Int, height: Int): Triple<ByteBuffer, ByteBuffer, ByteBuffer> {
        val y = ByteBuffer.wrap(ByteArray(width * height) { i ->
            yValue(i / width, i % width)
        })
        val uvWidth = width / 2
        val uvHeight = height / 2
        // V plane starts one byte before U, as on real NV21 devices: the V
        // view covers the whole VU region, the U view skips the first byte.
        val interleaved = ByteArray(uvWidth * uvHeight * 2)
        var i = 0
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                interleaved[i++] = vValue(row, col)
                interleaved[i++] = uValue(row, col)
            }
        }
        val v = ByteBuffer.wrap(interleaved, 0, interleaved.size)
        val u = ByteBuffer.wrap(interleaved, 1, interleaved.size - 1)
        return Triple(y, u, v)
    }

    @Test
    fun `planar layout converts through the generic path`() {
        val width = 8
        val height = 8
        val (y, u, v) = planarBuffers(width, height)
        // Planar: pixelStride 1, U and V rows differ → generic per-pixel path.
        val result = YuvConverter.yuvToNv21(
            yBuffer = y, uBuffer = u, vBuffer = v,
            yRowStride = width, yPixelStride = 1,
            uRowStride = width / 2, uPixelStride = 1,
            vRowStride = width / 2, vPixelStride = 1,
            width = width, height = height, cropLeft = 0, cropTop = 0,
        )
        assertArrayEquals(expectedNv21(width, height), result)
    }

    @Test
    fun `interleaved nv21 layout converts through the bulk path`() {
        val width = 8
        val height = 8
        val (y, u, v) = interleavedBuffers(width, height)
        val result = YuvConverter.yuvToNv21(
            yBuffer = y, uBuffer = u, vBuffer = v,
            yRowStride = width, yPixelStride = 1,
            uRowStride = width, uPixelStride = 2,
            vRowStride = width, vPixelStride = 2,
            width = width, height = height, cropLeft = 0, cropTop = 0,
        )
        assertArrayEquals(expectedNv21(width, height), result)
    }

    @Test
    fun `padded row stride is honored on the y plane`() {
        val width = 8
        val height = 8
        val stride = width + 8 // padding between rows, as sensors often produce
        val padded = ByteArray(stride * height) { i ->
            val row = i / stride
            val col = i % stride
            if (col < width) yValue(row, col) else 0xEE.toByte()
        }
        val (u, v) = planarBuffers(width, height).let { it.second to it.third }
        val result = YuvConverter.yuvToNv21(
            yBuffer = ByteBuffer.wrap(padded), uBuffer = u, vBuffer = v,
            yRowStride = stride, yPixelStride = 1,
            uRowStride = width / 2, uPixelStride = 1,
            vRowStride = width / 2, vPixelStride = 1,
            width = width, height = height, cropLeft = 0, cropTop = 0,
        )
        assertArrayEquals(expectedNv21(width, height), result)
    }

    @Test
    fun `crop rectangle offsets read from the middle of the buffers`() {
        val full = 16
        val cropSize = 8
        val fullY = ByteArray(full * full) { i -> yValue(i / full, i % full) }
        val (u, v) = planarBuffers(full, full).let { it.second to it.third }
        val result = YuvConverter.yuvToNv21(
            yBuffer = ByteBuffer.wrap(fullY), uBuffer = u, vBuffer = v,
            yRowStride = full, yPixelStride = 1,
            uRowStride = full / 2, uPixelStride = 1,
            vRowStride = full / 2, vPixelStride = 1,
            width = cropSize, height = cropSize, cropLeft = 8, cropTop = 8,
        )
        val expected = ByteArray(cropSize * cropSize * 3 / 2)
        for (row in 0 until cropSize) {
            for (col in 0 until cropSize) {
                expected[row * cropSize + col] = yValue(row + 8, col + 8)
            }
        }
        // UV crops read quarter coordinates relative to the full image.
        var uv = cropSize * cropSize
        for (row in 0 until cropSize / 2) {
            for (col in 0 until cropSize / 2) {
                expected[uv++] = vValue(row + 4, col + 4)
                expected[uv++] = uValue(row + 4, col + 4)
            }
        }
        assertArrayEquals(expected, result)
    }

    @Test
    fun `degenerate dimensions return null`() {
        val (y, u, v) = planarBuffers(4, 4)
        assertNull(
            YuvConverter.yuvToNv21(
                y, u, v,
                yRowStride = 4, yPixelStride = 1,
                uRowStride = 2, uPixelStride = 1,
                vRowStride = 2, vPixelStride = 1,
                width = 0, height = 4, cropLeft = 0, cropTop = 0,
            )
        )
    }

    @Test
    fun `round trip sizes are consistent`() {
        val width = 64
        val height = 48
        val (y, u, v) = interleavedBuffers(width, height)
        val result = YuvConverter.yuvToNv21(
            yBuffer = y, uBuffer = u, vBuffer = v,
            yRowStride = width, yPixelStride = 1,
            uRowStride = width, uPixelStride = 2,
            vRowStride = width, vPixelStride = 2,
            width = width, height = height, cropLeft = 0, cropTop = 0,
        )
        assertEquals(width * height * 3 / 2, result!!.size)
        assertArrayEquals(expectedNv21(width, height), result)
    }

    // ── NV21 → NV12 / I420 (moved from H264Encoder) ──

    /** Opaque pattern buffer so any pair mix-up shows up; already valid NV21 shape. */
    private fun patternNv21(width: Int, height: Int): ByteArray =
        ByteArray(width * height * 3 / 2) { ((it * 37 + 11) and 0xFF).toByte() }

    @Test
    fun `nv21ToNv12 swaps every VU pair to UV and leaves Y untouched`() {
        val width = 8
        val height = 8
        val nv21 = patternNv21(width, height)
        val nv12 = YuvConverter.nv21ToNv12(nv21, width, height)

        assertEquals(nv21.size, nv12.size)
        val ySize = width * height
        for (i in 0 until ySize) {
            assertEquals("Y byte $i", nv21[i], nv12[i])
        }
        var pairs = 0
        for (i in ySize until nv21.size step 2) {
            assertEquals("U at pair $pairs", nv21[i + 1], nv12[i]) // U first in NV12
            assertEquals("V at pair $pairs", nv21[i], nv12[i + 1]) // V second
            pairs++
        }
        assertEquals(width / 2 * (height / 2), pairs)
    }

    @Test
    fun `nv21ToNv12 copies without mutating its input`() {
        val nv21 = patternNv21(4, 4)
        val before = nv21.copyOf()
        YuvConverter.nv21ToNv12(nv21, 4, 4)
        assertArrayEquals(before, nv21)
    }

    @Test
    fun `nv21ToNv12 handles non-square and odd widths`() {
        for (dims in listOf(6 to 4, 4 to 6, 5 to 4, 2 to 2)) {
            val (w, h) = dims
            val nv21 = patternNv21(w, h)
            val nv12 = YuvConverter.nv21ToNv12(nv21, w, h)
            assertEquals("$w x $h size", nv21.size, nv12.size)
            val ySize = w * h
            var i = ySize
            while (i < nv21.size) {
                assertEquals("$w x $h pair at $i", nv21[i + 1], nv12[i])
                assertEquals("$w x $h pair at $i", nv21[i], nv12[i + 1])
                i += 2
            }
        }
    }

    @Test
    fun `nv21ToI420 deinterleaves into a full U plane then a full V plane`() {
        val width = 8
        val height = 8
        val nv21 = patternNv21(width, height)
        val i420 = YuvConverter.nv21ToI420(nv21, width, height)

        val ySize = width * height
        val uvPlaneSize = ySize / 4
        assertEquals(ySize + uvPlaneSize * 2, i420.size)

        // Y plane is a straight copy.
        for (i in 0 until ySize) {
            assertEquals("Y byte $i", nv21[i], i420[i])
        }
        // I420 plane order: U first, V second; NV21 interleaves V then U.
        for (p in 0 until uvPlaneSize) {
            assertEquals("U sample $p", nv21[ySize + p * 2 + 1], i420[ySize + p])
            assertEquals("V sample $p", nv21[ySize + p * 2], i420[ySize + uvPlaneSize + p])
        }
    }

    @Test
    fun `nv21ToI420 handles non-square and odd widths`() {
        for (dims in listOf(6 to 4, 4 to 6, 5 to 4, 2 to 2)) {
            val (w, h) = dims
            val nv21 = patternNv21(w, h)
            val i420 = YuvConverter.nv21ToI420(nv21, w, h)
            val ySize = w * h
            val uvPlaneSize = ySize / 4
            assertEquals("$w x $h size", ySize + uvPlaneSize * 2, i420.size)
            for (p in 0 until minOf(uvPlaneSize, (nv21.size - ySize) / 2)) {
                assertEquals("$w x $h U $p", nv21[ySize + p * 2 + 1], i420[ySize + p])
                assertEquals("$w x $h V $p", nv21[ySize + p * 2], i420[ySize + uvPlaneSize + p])
            }
        }
    }

    @Test
    fun `nv21ToNv12 is an involution - swapping twice restores the original`() {
        val nv21 = patternNv21(8, 8)
        val nv12 = YuvConverter.nv21ToNv12(nv21, 8, 8)
        val back = YuvConverter.nv21ToNv12(nv12, 8, 8)
        assertArrayEquals(nv21, back) // swap(swap(x)) == x
    }

    // ── NV21 rotation (moved from the RTSP transport) ──

    @Test
    fun `rotation 0 and unknown angles return the source array unchanged`() {
        val src = patternNv21(4, 4)
        org.junit.Assert.assertSame(src, YuvConverter.rotateNv21(src, 4, 4, 0))
        org.junit.Assert.assertSame(src, YuvConverter.rotateNv21(src, 4, 4, 45))
        org.junit.Assert.assertSame(src, YuvConverter.rotateNv21(src, 4, 4, -90))
    }

    @Test
    fun `rotation 180 reverses Y and reverses chroma pairs preserving VU order`() {
        val width = 4
        val height = 4
        val src = patternNv21(width, height)
        val dst = YuvConverter.rotateNv21(src, width, height, 180)

        val ySize = width * height
        val uvSize = ySize / 2
        assertEquals(src.size, dst.size)
        for (i in 0 until ySize) {
            assertEquals("Y $i", src[ySize - 1 - i], dst[i])
        }
        var j = 0
        for (pair in (uvSize / 2 - 1) downTo 0) {
            assertEquals("V pair $pair", src[ySize + pair * 2], dst[ySize + j])
            assertEquals("U pair $pair", src[ySize + pair * 2 + 1], dst[ySize + j + 1])
            j += 2
        }
    }

    /** Independent 90°-clockwise reference: dst(x', y') = src(y', h-1-x'), dstW = h. */
    private fun expectedRotate90(src: ByteArray, w: Int, h: Int): ByteArray {
        val dstW = h
        val dst = ByteArray(src.size)
        val ySize = w * h
        for (y in 0 until h) {
            for (x in 0 until w) {
                dst[x * dstW + (dstW - 1 - y)] = src[y * w + x]
            }
        }
        for (cy in 0 until h / 2) {
            for (cx in 0 until w / 2) {
                val v = src[ySize + cy * w + cx * 2]
                val u = src[ySize + cy * w + cx * 2 + 1]
                val idx = ySize + cx * dstW + (dstW / 2 - 1 - cy) * 2
                dst[idx] = v
                dst[idx + 1] = u
            }
        }
        return dst
    }

    /** Independent 270°-clockwise reference: dst(x', y') = src(w-1-y', x), dstW = h. */
    private fun expectedRotate270(src: ByteArray, w: Int, h: Int): ByteArray {
        val dstW = h
        val dst = ByteArray(src.size)
        val ySize = w * h
        for (y in 0 until h) {
            for (x in 0 until w) {
                dst[(w - 1 - x) * dstW + y] = src[y * w + x]
            }
        }
        for (cy in 0 until h / 2) {
            for (cx in 0 until w / 2) {
                val v = src[ySize + cy * w + cx * 2]
                val u = src[ySize + cy * w + cx * 2 + 1]
                val idx = ySize + (w / 2 - 1 - cx) * dstW + cy * 2
                dst[idx] = v
                dst[idx + 1] = u
            }
        }
        return dst
    }

    @Test
    fun `rotation 90 matches a clockwise transform for square and non-square frames`() {
        for (dims in listOf(4 to 4, 4 to 2, 2 to 4)) {
            val (w, h) = dims
            val src = patternNv21(w, h)
            assertArrayEquals("$w x $h", expectedRotate90(src, w, h), YuvConverter.rotateNv21(src, w, h, 90))
        }
    }

    @Test
    fun `rotation 270 matches a counter-clockwise transform for square and non-square frames`() {
        for (dims in listOf(4 to 4, 4 to 2, 2 to 4)) {
            val (w, h) = dims
            val src = patternNv21(w, h)
            assertArrayEquals("$w x $h", expectedRotate270(src, w, h), YuvConverter.rotateNv21(src, w, h, 270))
        }
    }

    @Test
    fun `rotating 90 three times equals rotating 270 once`() {
        val src = patternNv21(4, 2)
        val threeTimes = YuvConverter.rotateNv21(
            YuvConverter.rotateNv21(YuvConverter.rotateNv21(src, 4, 2, 90), 2, 4, 90), 4, 2, 90
        )
        assertArrayEquals(YuvConverter.rotateNv21(src, 4, 2, 270), threeTimes)
    }
}
