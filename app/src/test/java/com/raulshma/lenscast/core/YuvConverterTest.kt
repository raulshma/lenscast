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
}
